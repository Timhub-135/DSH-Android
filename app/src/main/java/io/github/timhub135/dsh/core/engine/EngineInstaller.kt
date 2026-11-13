package io.github.timhub135.dsh.core.engine

import android.content.Context
import android.system.Os
import java.io.File
import java.util.zip.ZipInputStream

/** Unpacks assets/engine.zip into files/engine/<sha> once per engine build. Runs in :engine. */
object EngineInstaller {
    fun install(context: Context): File {
        val sha = context.assets.open("engine.sha256").bufferedReader().readText().trim().take(16)
        val root = File(context.filesDir, "engine")
        val dir = File(root, sha)
        val marker = File(dir, ".installed")
        if (!marker.exists()) {
            root.listFiles()?.filter { it.name != sha }?.forEach { it.deleteRecursively() }
            dir.deleteRecursively()
            dir.mkdirs()
            ZipInputStream(context.assets.open("engine.zip").buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    val out = File(dir, e.name).canonicalFile
                    require(out.path.startsWith(dir.canonicalPath)) { "bad entry ${e.name}" }
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            marker.writeText(sha)
        }
        linkTools(context)
        linkPython(context, dir)
        return dir
    }

    /** files/bin holds symlinks to executables shipped as lib*.so (the only exec-allowed location). */
    private fun linkTools(context: Context) {
        val bin = File(context.filesDir, "bin").apply { mkdirs() }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        mapOf("rg" to "librg.so", "node" to "libdshnode.so", "python" to "libdshpython.so").forEach { (name, lib) ->
            val link = File(bin, name)
            val target = File(nativeDir, lib)
            if (!target.exists()) return@forEach
            runCatching { if (Os.readlink(link.path) == target.path) return@forEach }
            link.delete()
            Os.symlink(target.path, link.path)
        }
    }
    /**
     * Python extension modules ship in nativeLibraryDir as lib*.so (the only place Android lets an app
     * load/execute code); the Python tree gets symlinks with the names CPython's import machinery looks
     * for. `python/ext-map.txt` has one `<path under python/> TAB <lib file name>` line per module.
     */
    private fun linkPython(context: Context, engineDir: File) {
        val pythonRoot = File(engineDir, "python")
        val map = File(pythonRoot, "ext-map.txt")
        if (!map.isFile) return
        val nativeDir = context.applicationInfo.nativeLibraryDir
        map.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split('\t')
                if (parts.size != 2) return@forEach
                val target = File(nativeDir, parts[1].trim())
                if (!target.exists()) return@forEach
                val link = File(pythonRoot, parts[0].trim())
                link.parentFile?.mkdirs()
                runCatching { if (Os.readlink(link.path) == target.path) return@forEach }
                link.delete()
                Os.symlink(target.path, link.path)
            }
        }
    }

    /**
     * pip's vendored certifi points at Termux's CA store; replace it with the Android trust store the
     * engine exports for Node (EngineRuntimeEnv.CaBundle), so `python -m pip` and requests trust HTTPS.
     */
    fun materializePythonCa(context: Context, engineDir: File) {
        val ca = File(context.filesDir, "certs/android-ca.pem")
        if (!ca.isFile) return
        val sitePackages = File(engineDir, "python/lib/python3.14/site-packages")
        listOf("pip/_vendor/certifi/cacert.pem", "certifi/cacert.pem").forEach { rel ->
            val dest = File(sitePackages, rel)
            if (dest.parentFile?.isDirectory == true) runCatching {
                dest.delete()
                ca.copyTo(dest)
            }
        }
    }
}
