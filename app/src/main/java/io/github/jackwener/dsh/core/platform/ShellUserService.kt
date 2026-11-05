package io.github.jackwener.dsh.core.platform

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku user service: instantiated by Shizuku in a process running as the shell user (uid 2000),
 * which has the privileges ordinary apps lack (input injection, `svc`, `settings`, trusted displays,
 * task moves across displays). Hidden APIs are called through reflection and fail soft.
 */
class ShellUserService : IShellService.Stub() {
    private class Screen(val display: VirtualDisplay, val reader: ImageReader) {
        /** The last frame read: ImageReader only hands out a frame once, and a still screen sends no new ones. */
        var last: Bitmap? = null
        /** Keeps the display's own power group on (see [keepAwake]); null when the display shares the user's group. */
        var awake: PowerManager.WakeLock? = null
    }
    private val displays = HashMap<Int, Screen>()
    private val muted = MuteLedger(File("/data/local/tmp/dsh_audio_mute"))

    init {
        // A previous service process died with apps still muted: give them their sound back (usually nothing to do).
        runCatching { muted.restoreAll(::appops) }
    }

    override fun destroy() {
        runCatching { muted.restoreAll(::appops) }
        synchronized(displays) { displays.values.forEach { it.awake?.release(); it.display.release(); it.reader.close() } }
        exitProcess(0)
    }

    override fun exec(cmd: String, timeoutMs: Long): String {
        // Run from "/" like `adb shell`, so relative paths mean what the engine's hardline check assumed.
        val p = ProcessBuilder("sh", "-c", cmd).directory(File("/")).redirectErrorStream(true).start()
        // Read on a separate thread: readText() blocks until stdout closes, so a hung command would
        // never reach waitFor/destroy and would hold a Shizuku binder thread for ever.
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) { val n = r.read(buf); if (n < 0) break; synchronized(out) { if (out.length < 64_000) out.append(buf, 0, minOf(n, 64_000 - out.length)) } }
                }
            }
        }.apply { isDaemon = true; start() }
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        reader.join(if (done) 2_000 else 500)
        return "${if (done) p.exitValue() else 124}\n${synchronized(out) { out.toString() }}"
    }

    /** The shell identity every system-service call made here goes through (see [ShellContext]). */
    private val shellContext: ShellContext by lazy { ShellContext.create() }
    @Volatile private var displayError: String? = null

    @SuppressLint("WrongConstant")
    override fun createDisplay(width: Int, height: Int, dpi: Int): Int {
        val failures = ArrayList<String>()
        try {
            if (Looper.myLooper() == null) Looper.prepare()
            val dm = shellContext.getSystemService(DisplayManager::class.java) ?: error("no DisplayManager")
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            // Newest flag set first; an older or stricter build that rejects it gets the previous one.
            for (flags in displayFlags()) {
                val vd = try { dm.createVirtualDisplay("dsh-agent", width, height, dpi, reader.surface, flags) } catch (t: Throwable) {
                    Log.w(TAG, "createVirtualDisplay(flags=0x${flags.toString(16)}) failed", t)
                    failures += "flags 0x${flags.toString(16)}: ${ShellIdentity.describe(t)}"; null
                } ?: continue
                val id = vd.display.displayId
                val screen = Screen(vd, reader)
                if (flags and OWN_DISPLAY_GROUP != 0) screen.awake = keepAwake(id)
                synchronized(displays) { displays[id] = screen }
                setImePolicy(id)
                displayError = null
                return id
            }
            reader.close()
        } catch (t: Throwable) {
            Log.w(TAG, "createDisplay failed", t)
            failures += ShellIdentity.describe(t)
        }
        displayError = DisplayFailure.summary(failures)
        return -1
    }

    override fun lastDisplayError(): String? = displayError

    override fun wakeDisplay(displayId: Int): Boolean {
        val s = synchronized(displays) { displays[displayId] } ?: return false
        synchronized(s) {
            val old = s.awake ?: return false
            old.release()
            s.awake = keepAwake(displayId)
            return s.awake != null
        }
    }

    // ---- Android/data and Android/obb (FileTools) ----------------------------------------------

    private val files = ShellFiles()

    override fun fsStat(path: String): String? = files.stat(path)
    override fun fsList(path: String, recursive: Boolean, limit: Int): String = files.list(path, recursive, limit)
    override fun fsMkdirs(path: String): String = files.mkdirs(path)
    override fun fsMove(from: String, to: String, copy: Boolean): String = files.move(from, to, copy)
    override fun fsDelete(path: String): String = files.delete(path)
    override fun fsSearch(dir: String, nameGlob: String?, content: String?, regex: Boolean, limit: Int, sort: String?): String =
        files.search(dir, nameGlob, content, regex, limit, sort)

    override fun fsOpen(path: String, mode: String): ParcelFileDescriptor {
        val f = if (mode == "r") files.openable(path) else files.prepareWrite(path)
        val m = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            else -> throw IllegalArgumentException("mode must be r, w or wa")
        }
        return try { ParcelFileDescriptor.open(f, m) } catch (t: Throwable) { throw IllegalStateException(ShellIdentity.describe(t)) }
    }

    override fun releaseDisplay(displayId: Int) {
        val s = synchronized(displays) { displays.remove(displayId) } ?: return
        synchronized(s) {
            s.awake?.release(); s.display.release(); s.reader.close()
            s.last?.let { b -> synchronized(b) { b.recycle() } }
        }
    }

    override fun captureDisplay(displayId: Int, maxWidth: Int): ByteArray? {
        val frame = frame(displayId) ?: return null
        val out = ByteArrayOutputStream()
        synchronized(frame) {
            if (frame.isRecycled) return null
            val scaled = if (maxWidth in 1 until frame.width) Bitmap.createScaledBitmap(frame, maxWidth, frame.height * maxWidth / frame.width, true) else frame
            scaled.compress(Bitmap.CompressFormat.JPEG, if (scaled === frame) 80 else 70, out)
            if (scaled !== frame) scaled.recycle()
        }
        return out.toByteArray()
    }

    override fun frameBlank(displayId: Int): Int {
        val bmp = frame(displayId) ?: return -1
        synchronized(bmp) {
            if (bmp.isRecycled) return -1
            for (y in 0 until 32) for (x in 0 until 32) {
                val p = bmp.getPixel(x * (bmp.width - 1) / 31, y * (bmp.height - 1) / 31)
                if ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff) > 6) return 0
            }
        }
        return 1
    }

    /** The newest frame of [displayId] (or the last one when nothing changed since). */
    private fun frame(displayId: Int): Bitmap? {
        val s = synchronized(displays) { displays[displayId] } ?: return null
        synchronized(s) {
            val image = runCatching { s.reader.acquireLatestImage() }.getOrNull() ?: return s.last
            image.use {
                val plane = it.planes[0]
                val padding = plane.rowStride - plane.pixelStride * it.width
                val bmp = Bitmap.createBitmap(it.width + padding / plane.pixelStride, it.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (padding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, it.width, it.height).also { bmp.recycle() }
                s.last?.let { old -> synchronized(old) { old.recycle() } }
                s.last = cropped
                return cropped
            }
        }
    }

    // ---- tasks --------------------------------------------------------------------------------

    @get:SuppressLint("PrivateApi") private val taskManager: Any by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!! }
    @get:SuppressLint("PrivateApi") private val taskInfo: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

    override fun tasks(): String? = try {
        val f = { name: String -> runCatching { taskInfo.getField(name) }.getOrNull() }
        val id = f("taskId")!!; val display = f("displayId")!!
        val top = f("topActivity"); val base = f("baseActivity"); val type = f("topActivityType"); val visible = f("isVisible")
        allTasks().joinToString("\n") { t ->
            val topC = top?.get(t) as? ComponentName
            val pkg = topC?.packageName ?: (base?.get(t) as? ComponentName)?.packageName ?: ""
            val standard = type?.getInt(t)?.let { it == 0 || it == 1 } ?: true // ACTIVITY_TYPE_UNDEFINED / STANDARD
            val vis = visible?.getBoolean(t)?.let { if (it) 1 else 0 } ?: -1
            "${id.getInt(t)}\t${display.getInt(t)}\t$pkg\t${topC?.className ?: ""}\t${if (standard) 1 else 0}\t$vis"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "tasks failed", t); null
    }

    /** IActivityTaskManager.getTasks, whichever overload this Android has (the display filter, when present, gets INVALID_DISPLAY). */
    private fun allTasks(): List<Any> {
        val m = taskManager.javaClass.methods.filter { it.name == "getTasks" }.maxByOrNull { it.parameterTypes.size } ?: return emptyList()
        var ints = 0
        val args: List<Any?> = m.parameterTypes.map { p ->
            when (p) {
                Int::class.javaPrimitiveType -> if (ints++ == 0) MAX_TASKS else -1
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        return (m.invoke(taskManager, *args.toTypedArray()) as? List<*>).orEmpty().filterNotNull()
    }

    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        val viaApi = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java).invoke(taskManager, taskId, displayId)
            } else {
                // Android 10/11 move stacks, not tasks: look the task's stack up first.
                val t = allTasks().first { taskInfo.getField("taskId").getInt(it) == taskId }
                val stack = taskInfo.getField("stackId").getInt(t)
                taskManager.javaClass.getMethod("moveStackToDisplay", Int::class.java, Int::class.java).invoke(taskManager, stack, displayId)
            }
            true
        }.onFailure { Log.w(TAG, "moveTask via API failed, trying am", it) }.getOrDefault(false)
        return viaApi || exec("am display move-stack $taskId $displayId", 10_000).substringBefore('\n') == "0"
    }

    // ---- sound --------------------------------------------------------------------------------

    override fun setAudioMuted(pkg: String, muted: Boolean): Boolean {
        if (!PKG.matches(pkg)) return false
        val user = Binder.getCallingUid() / 100_000
        return synchronized(this.muted) {
            if (muted) {
                if (!this.muted.contains(pkg)) {
                    val got = exec("appops get --user $user $pkg PLAY_AUDIO", 10_000)
                    if (got.substringBefore('\n') != "0") return false
                    this.muted.put(pkg, MuteLedger.parseMode(got.substringAfter('\n')), user)
                }
                appops(pkg, "ignore", user)
            } else {
                val entry = this.muted.get(pkg) ?: return true
                appops(pkg, MuteLedger.restoreMode(entry.first), entry.second).also { if (it) this.muted.remove(pkg) }
            }
        }
    }

    override fun restoreAudio() {
        runCatching { muted.restoreAll(::appops) }
    }

    private fun appops(pkg: String, mode: String, user: Int): Boolean = exec("appops set --user $user $pkg PLAY_AUDIO $mode", 10_000).substringBefore('\n') == "0"

    // ---- display setup ----------------------------------------------------------------------------

    /** Most capable first: Android 14 adds its own focus (no keyboard stealing), 13 its own display group. */
    private fun displayFlags(): List<Int> {
        val base = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL or TRUSTED
        val t = base or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        val u = t or OWN_FOCUS or STEAL_TOP_FOCUS_DISABLED
        return listOfNotNull(
            u.takeIf { Build.VERSION.SDK_INT >= 34 },
            t.takeIf { Build.VERSION.SDK_INT >= 33 },
            base,
            PUBLIC or OWN_CONTENT_ONLY or TRUSTED,
        )
    }

    /**
     * A screen wake lock bound to [displayId] (the hidden PowerManager.newWakeLock(flags, tag, displayId), Android 13+).
     * A display with its own display group is its own power group, which Android turns off after the
     * screen-off timeout without user activity on it; reads and screenshots are none, so an agent that only
     * watched for ~10 minutes was left with a black, OFF display (#56). ACQUIRE_CAUSES_WAKEUP also turns a
     * group that already went off back on (where the system honours it for shell). Only for displays with
     * their own group: on the user's group it would keep their screen on.
     */
    @SuppressLint("WakelockTimeout")
    private fun keepAwake(displayId: Int): PowerManager.WakeLock? = runCatching {
        val pm = shellContext.getSystemService(PowerManager::class.java) ?: error("no PowerManager")
        @Suppress("DEPRECATION") val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        val m = PowerManager::class.java.getMethod("newWakeLock", Int::class.java, String::class.java, Int::class.java)
        (m.invoke(pm, flags, "dsh:agent-screen", displayId) as PowerManager.WakeLock).apply { setReferenceCounted(false); acquire() }
    }.onFailure { Log.w(TAG, "agent display wake lock failed", it) }.getOrNull()

    /** IWindowManager.setDisplayImePolicy(LOCAL): the keyboard opens on the agent display, never on the user's. */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, 0)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    private companion object {
        const val TAG = "DshShell"
        const val MAX_TASKS = 100
        val PKG = Regex("[A-Za-z0-9_.]+")
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden)
        const val PUBLIC = 1 shl 0
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val ROTATES_WITH_CONTENT = 1 shl 7
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }
}

/**
 * Packages muted on the agent display, with their previous PLAY_AUDIO mode and user, kept in a file
 * so a restarted service can give the sound back. Lines: "pkg\tmode\tuser".
 */
internal class MuteLedger(private val file: File) {
    private val entries = LinkedHashMap<String, Pair<String, Int>>()

    init {
        runCatching {
            file.takeIf { it.exists() }?.readLines()?.forEach { l ->
                val p = l.split('\t')
                if (p.size >= 2) entries[p[0]] = p[1] to (p.getOrNull(2)?.toIntOrNull() ?: 0)
            }
        }
    }

    fun contains(pkg: String) = pkg in entries
    fun get(pkg: String) = entries[pkg]
    fun put(pkg: String, mode: String, user: Int) { entries[pkg] = mode to user; save() }
    fun remove(pkg: String) { entries.remove(pkg); save() }

    fun restoreAll(set: (String, String, Int) -> Boolean) = synchronized(this) {
        entries.entries.toList().forEach { (pkg, e) -> if (set(pkg, restoreMode(e.first), e.second)) entries.remove(pkg) }
        save()
    }

    private fun save() { runCatching { file.writeText(entries.entries.joinToString("") { "${it.key}\t${it.value.first}\t${it.value.second}\n" }) } }

    companion object {
        /** `appops get` output → mode ("PLAY_AUDIO: ignore; time=…" → ignore); not set → default. */
        fun parseMode(output: String): String = Regex("PLAY_AUDIO: ([a-z_]+)").find(output)?.groupValues?.get(1) ?: "default"
        /** "default" (never set) is written back as allow, which is what default means for PLAY_AUDIO. */
        fun restoreMode(mode: String) = if (mode == "default") "allow" else mode
    }
}

/** createDisplay's failure text for the app: the last flag set's error first (the most compatible one), then the others. */
internal object DisplayFailure {
    fun summary(failures: List<String>): String =
        if (failures.isEmpty()) "no virtual display flags to try" else (listOf(failures.last()) + failures.dropLast(1).reversed()).joinToString("; ").take(ShellIdentity.MAX_ERROR * 2)
}
