package io.github.timhub135.dsh.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Every source failed; [failures] (url → reason) in the order they failed. */
class AllSourcesFailed(val failures: List<Pair<String, Throwable>>) :
    IOException(failures.joinToString("; ") { (u, e) -> "${hostOf(u)}: ${e.message ?: e.javaClass.simpleName}" })

internal fun hostOf(url: String) = runCatching { URL(url).host }.getOrDefault(url)

/**
 * Tries [sources] in order, the next one [staggerMs] after the previous started (at once when one
 * fails); earlier attempts keep running, the first success wins and the rest are cancelled. The
 * engine's fetchFirst (fetch-sources.js) does the same for plugin indexes: a slow-but-working
 * source is never given up for a later one, only raced by it. [attempt] throws for an invalid answer.
 */
suspend fun <T> firstSuccess(sources: List<String>, staggerMs: Long, attempt: suspend (String) -> T): Pair<String, T> = coroutineScope {
    require(sources.isNotEmpty())
    val winner = CompletableDeferred<Pair<String, T>>()
    val failures = mutableListOf<Pair<String, Throwable>>()
    val failed = Channel<Unit>(Channel.CONFLATED)
    val jobs = mutableListOf<Job>()
    val launcher = launch {
        sources.forEachIndexed { i, url ->
            if (i > 0) withTimeoutOrNull(staggerMs) { failed.receive() }
            jobs += launch {
                try { winner.complete(url to attempt(url)) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) {
                    val all = synchronized(failures) { failures += url to e; failures.size == sources.size }
                    if (all) winner.completeExceptionally(AllSourcesFailed(synchronized(failures) { failures.toList() }))
                    failed.trySend(Unit)
                }
            }
        }
    }
    try { winner.await() } finally { launcher.cancel(); jobs.forEach { it.cancel() } }
}

/** One HTTP response body; [start] is the file offset of its first byte, [total] the full length when known. */
class RangeBody(val start: Long, val total: Long?, val stream: InputStream, private val onClose: () -> Unit = {}) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) { runCatching { stream.close() }; onClose() } }
}

/** The HTTP the updater needs; [HttpUpdateNet] in the app, a fake in tests. */
interface UpdateNet {
    /** GET [url] as text (size-capped); throws on a non-2xx status or a network failure. */
    fun text(url: String): String
    /**
     * GET [url] from byte [from] (a `Range` request when > 0). Returns where the body actually
     * starts ([rangeStart]); throws [RangeNotSatisfiable] for 416, IOException otherwise.
     */
    fun open(url: String, from: Long): RangeBody
}

class RangeNotSatisfiable : IOException("HTTP 416")

/**
 * Where a response body starts given what was asked: 206 must answer `bytes <from>-…`; a 200 is
 * the whole file (the server ignored the Range); anything else is an error (null).
 */
fun rangeStart(code: Int, contentRange: String?, from: Long): Long? = when (code) {
    200 -> 0L
    206 -> Regex("""^bytes (\d+)-\d+/(\d+|\*)$""").find(contentRange?.trim().orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it == from }
    else -> null
}

/** Total length from `Content-Range: bytes a-b/total` (206) or Content-Length (200). */
fun totalLength(code: Int, contentRange: String?, contentLength: Long): Long? =
    if (code == 206) contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull() else contentLength.takeIf { it >= 0 }

/**
 * HttpURLConnection, like the app's other requests (ModelVerify, Providers): it takes Android's
 * proxy for the default network (Wi-Fi / global / VPN proxy) through the system ProxySelector,
 * the same proxy the engine is given (EngineProxy). Redirects (github.com → its asset CDN) are
 * followed within https; the Range header goes along.
 */
class HttpUpdateNet(private val userAgent: String, private val connectMs: Int = 15_000, private val readMs: Int = 30_000) : UpdateNet {
    private fun connect(url: String, configure: HttpURLConnection.() -> Unit = {}): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs; c.readTimeout = readMs; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        c.configure()
        return c
    }

    override fun text(url: String): String {
        val c = connect(url) { setRequestProperty("Accept", "application/json"); setRequestProperty("Cache-Control", "no-cache") }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            if (c.url.protocol != "https") throw IOException("redirected off https")
            val bytes = c.inputStream.use { it.readNBytesCompat(MAX_MANIFEST) }
            return bytes.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    override fun open(url: String, from: Long): RangeBody {
        val c = connect(url) {
            setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        try {
            val code = c.responseCode
            if (code == 416) throw RangeNotSatisfiable()
            if (c.url.protocol != "https") throw IOException("redirected off https")
            val range = c.getHeaderField("Content-Range")
            val start = rangeStart(code, range, from) ?: throw IOException("HTTP $code${range?.let { " ($it)" } ?: ""}")
            return RangeBody(start, totalLength(code, range, c.contentLengthLong), c.inputStream) { c.disconnect() }
        } catch (t: Throwable) { c.disconnect(); throw t }
    }

    companion object { const val MAX_MANIFEST = 256 * 1024 }
}

private fun InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf); if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) throw IOException("response larger than $max bytes")
    }
    return out.toByteArray()
}

object Sha256 {
    fun of(file: File): String = file.inputStream().use { of(it) }
    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A downloaded APK did not match the manifest's SHA-256 (corrupt transfer or a tampered mirror). */
class DigestMismatch(val url: String) : IOException("SHA-256 mismatch from ${hostOf(url)}")

/**
 * Resumable APK download into [dir]: bytes go to `<sha256>.part` (so a different release never
 * resumes this one), each attempt continues from the part's length with an HTTP Range request,
 * a URL is retried [retries] times with backoff before the next one is tried, and a complete file
 * is accepted only when its SHA-256 matches; it is then renamed to `<sha256>.apk`. Cancelling the
 * coroutine stops at the next buffer and keeps the part for later.
 */
class ApkDownloader(
    private val net: UpdateNet,
    private val retries: Int = 3,
    private val backoffMs: (Int) -> Long = { attempt -> 2_000L * (1 shl attempt) },
) {
    fun finalFile(dir: File, apk: UpdateManifest.Apk) = File(dir, "${apk.sha256}.apk")
    fun partFile(dir: File, apk: UpdateManifest.Apk) = File(dir, "${apk.sha256}.part")

    /** [onProgress] (bytes on disk, total) runs on the caller's thread after every buffer. */
    suspend fun download(apk: UpdateManifest.Apk, dir: File, onProgress: (Long, Long) -> Unit): File {
        dir.mkdirs()
        val done = finalFile(dir, apk)
        if (done.isFile && done.length() == apk.size && Sha256.of(done) == apk.sha256) return done
        done.delete()
        // Leftovers of other releases.
        dir.listFiles()?.forEach { if (it.name != partFile(dir, apk).name) it.delete() }
        val part = partFile(dir, apk)
        val failures = mutableListOf<Pair<String, Throwable>>()
        for (url in apk.urls) {
            var attempt = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    fetchInto(url, apk, part, onProgress)
                    if (Sha256.of(part) != apk.sha256) { part.delete(); throw DigestMismatch(url) }
                    if (!part.renameTo(done)) throw IOException("cannot move the download into place")
                    return done
                } catch (e: CancellationException) { throw e }
                catch (e: DigestMismatch) { failures += url to e; break } // a retry would fetch the same bytes: next URL
                catch (e: Throwable) {
                    if (++attempt > retries) { failures += url to e; break }
                    delay(backoffMs(attempt - 1))
                }
            }
        }
        throw AllSourcesFailed(failures)
    }

    private suspend fun fetchInto(url: String, apk: UpdateManifest.Apk, part: File, onProgress: (Long, Long) -> Unit) {
        if (part.length() > apk.size) part.delete()
        var have = part.length()
        if (have == apk.size) return
        val body = try { net.open(url, have) } catch (e: RangeNotSatisfiable) {
            // The part is stale or the server cannot resume it: start over once from zero.
            if (have == 0L) throw e
            part.delete(); have = 0; net.open(url, 0)
        }
        // A cancel closes the stream, so a read stalled on a dead connection ends now, not at its timeout.
        coroutineScope {
            val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { body.close() } }
            try { copy(body, apk, part, have, onProgress).also { have = it } } finally { closer.cancel(); body.close() }
        }
        if (have != apk.size) throw IOException("connection closed at $have of ${apk.size} bytes")
    }

    /** Writes [body] into [part]; returns the bytes on disk afterwards. */
    private suspend fun copy(body: RangeBody, apk: UpdateManifest.Apk, part: File, had: Long, onProgress: (Long, Long) -> Unit): Long {
        var have = had
        body.total?.let { t -> if (t != apk.size) throw IOException("server reports $t bytes, manifest says ${apk.size}") }
        val append = body.start > 0
        if (!append) have = 0
        FileOutputStream(part, append).use { out ->
            val buf = ByteArray(1 shl 16)
            val ctx = currentCoroutineContext()
            while (true) {
                ctx.ensureActive()
                val n = body.stream.read(buf); if (n < 0) break
                if (have + n > apk.size) throw IOException("more data than the manifest's ${apk.size} bytes")
                out.write(buf, 0, n); have += n
                onProgress(have, apk.size)
            }
            out.fd.sync()
        }
        return have
    }
}
