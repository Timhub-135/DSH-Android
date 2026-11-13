package io.github.timhub135.dsh.core.update

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections

class UpdateFetchTest {
    @get:Rule val tmp = TemporaryFolder()

    // ── source race ──

    @Test fun firstSourceWinsWhenItAnswers() = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val (url, v) = firstSuccess(listOf("a", "b", "c"), staggerMs = 500) { started += it; delay(20); it.uppercase() }
        assertEquals("a" to "A", url to v)
        assertEquals(listOf("a"), started)
    }

    @Test fun aFailureStartsTheNextAtOnce() = runBlocking {
        val t0 = System.nanoTime()
        val (url, _) = firstSuccess(listOf("a", "b"), staggerMs = 5_000) { if (it == "a") throw IOException("refused") else "ok" }
        assertEquals("b", url)
        assertTrue("did not wait for the stagger", (System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun aSlowSourceIsRacedNotDropped() = runBlocking {
        // a is slow but answers before b (which starts after the stagger) does: a still wins.
        val (url, _) = firstSuccess(listOf("a", "b"), staggerMs = 50) { if (it == "a") delay(150) else delay(1_000); it }
        assertEquals("a", url)
        // a hangs: b (started after the stagger) wins, and a is cancelled.
        var aCancelled = false
        val (url2, _) = firstSuccess(listOf("a", "b"), staggerMs = 50) {
            if (it == "a") try { delay(60_000) } finally { aCancelled = true }
            it
        }
        assertEquals("b", url2)
        assertTrue(aCancelled)
    }

    @Test fun invalidAnswersCountAsFailuresAndAllFailedListsThem() = runBlocking {
        try {
            firstSuccess(listOf("https://x.example/1", "https://y.example/2"), staggerMs = 10) { UpdateManifest.parse("{\"format\":9}") }
            fail("no source succeeded")
        } catch (e: AllSourcesFailed) {
            assertEquals(listOf("https://x.example/1", "https://y.example/2"), e.failures.map { it.first })
            assertTrue(e.failures.all { it.second is UpdateManifest.Invalid })
            assertTrue(e.message!!.contains("x.example"))
        }
    }

    // ── Range bookkeeping ──

    @Test fun rangeStartFollowsTheStatus() {
        assertEquals(0L, rangeStart(200, null, 500))
        assertEquals(500L, rangeStart(206, "bytes 500-999/1000", 500))
        assertNull(rangeStart(206, "bytes 400-999/1000", 500))
        assertNull(rangeStart(206, null, 500))
        assertNull(rangeStart(404, null, 0))
        assertEquals(1000L, totalLength(206, "bytes 500-999/1000", 500))
        assertNull(totalLength(206, "bytes 500-999/*", 500))
        assertEquals(1000L, totalLength(200, null, 1000))
        assertNull(totalLength(200, null, -1))
    }

    @Test fun sha256OfKnownInput() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of(ByteArrayInputStream("abc".toByteArray())))
    }

    // ── resumable download ──

    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val dataSha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun apk(vararg urls: String, sha: String = dataSha, size: Long = data.size.toLong()) = UpdateManifest.Apk("a.apk", size, sha, urls.toList())

    /** Serves [bodies] per URL; [cutAfter] bytes of a response, then an IOException (a dropped connection). */
    private inner class FakeNet(
        val bodies: Map<String, ByteArray>,
        val cutAfter: MutableList<Int> = mutableListOf(),
        val ignoresRange: Set<String> = emptySet(),
        val failing: Set<String> = emptySet(),
    ) : UpdateNet {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        override fun text(url: String) = throw IOException("unused")
        override fun open(url: String, from: Long): RangeBody {
            requests += url to from
            if (url in failing) throw IOException("HTTP 503")
            val body = bodies.getValue(url)
            if (from > body.size) throw RangeNotSatisfiable()
            val start = if (url in ignoresRange) 0L else from
            val cut = cutAfter.removeFirstOrNull()
            val bytes = body.copyOfRange(start.toInt(), body.size)
            val stream: InputStream = object : InputStream() {
                var pos = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (cut != null && pos >= cut) throw IOException("connection reset")
                    if (pos >= bytes.size) return -1
                    val n = minOf(len, bytes.size - pos, if (cut != null) cut - pos else Int.MAX_VALUE)
                    System.arraycopy(bytes, pos, b, off, n); pos += n
                    return n
                }
            }
            return RangeBody(start, body.size.toLong(), stream)
        }
    }

    private fun downloader(net: UpdateNet) = ApkDownloader(net, retries = 2, backoffMs = { 1 })

    @Test fun downloadsAndVerifies() = runBlocking {
        val net = FakeNet(mapOf("https://a/x" to data))
        var last = 0L
        val f = downloader(net).download(apk("https://a/x"), tmp.root) { done, _ -> last = done }
        assertArrayEquals(data, f.readBytes())
        assertEquals("$dataSha.apk", f.name)
        assertEquals(data.size.toLong(), last)
        assertFalse(java.io.File(tmp.root, "$dataSha.part").exists())
    }

    @Test fun aDroppedConnectionResumesWithRange() = runBlocking {
        val net = FakeNet(mapOf("https://a/x" to data), cutAfter = mutableListOf(100_000, 70_000))
        val f = downloader(net).download(apk("https://a/x"), tmp.root) { _, _ -> }
        assertArrayEquals(data, f.readBytes())
        assertEquals(listOf("https://a/x" to 0L, "https://a/x" to 100_000L, "https://a/x" to 170_000L), net.requests)
    }

    @Test fun aServerIgnoringRangeRestartsTheFile() = runBlocking {
        val net = FakeNet(mapOf("https://a/x" to data), cutAfter = mutableListOf(120_000), ignoresRange = setOf("https://a/x"))
        val f = downloader(net).download(apk("https://a/x"), tmp.root) { _, _ -> }
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun aPartFromAnEarlierRunIsResumed() = runBlocking {
        java.io.File(tmp.root, "$dataSha.part").writeBytes(data.copyOfRange(0, 200_000))
        val net = FakeNet(mapOf("https://a/x" to data))
        val f = downloader(net).download(apk("https://a/x"), tmp.root) { _, _ -> }
        assertArrayEquals(data, f.readBytes())
        assertEquals(listOf("https://a/x" to 200_000L), net.requests)
    }

    @Test fun aCompleteVerifiedFileIsReused() = runBlocking {
        java.io.File(tmp.root, "$dataSha.apk").writeBytes(data)
        val net = FakeNet(emptyMap())
        downloader(net).download(apk("https://a/x"), tmp.root) { _, _ -> }
        assertTrue(net.requests.isEmpty())
    }

    @Test fun aWrongDigestMovesToTheNextUrlAndDeletesTheBytes() = runBlocking {
        val tampered = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val net = FakeNet(mapOf("https://evil/x" to tampered, "https://good/x" to data))
        val f = downloader(net).download(apk("https://evil/x", "https://good/x"), tmp.root) { _, _ -> }
        assertArrayEquals(data, f.readBytes())
        // The tampered bytes were not resumed into the good download.
        assertEquals(listOf("https://evil/x" to 0L, "https://good/x" to 0L), net.requests)
    }

    @Test fun failingUrlsAreRetriedThenTheNextIsTried() = runBlocking {
        val net = FakeNet(mapOf("https://b/x" to data), failing = setOf("https://a/x"))
        downloader(net).download(apk("https://a/x", "https://b/x"), tmp.root) { _, _ -> }
        assertEquals(3, net.requests.count { it.first == "https://a/x" }) // first try + 2 retries
    }

    @Test fun allUrlsFailing() = runBlocking {
        val tampered = data.copyOf().also { it[0] = 1 }
        val net = FakeNet(mapOf("https://a/x" to tampered), failing = setOf("https://b/x"))
        try { downloader(net).download(apk("https://a/x", "https://b/x"), tmp.root) { _, _ -> }; fail() }
        catch (e: AllSourcesFailed) {
            assertTrue(e.failures[0].second is DigestMismatch)
            assertEquals("https://b/x", e.failures[1].first)
        }
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".apk") })
    }

    @Test fun aSizeOtherThanTheManifestsIsRefused() = runBlocking {
        val net = FakeNet(mapOf("https://a/x" to data))
        try { downloader(net).download(apk("https://a/x", size = 1000), tmp.root) { _, _ -> }; fail() }
        catch (e: AllSourcesFailed) { assertTrue(e.message!!, e.failures.single().second.message!!.contains("manifest")) }
    }

    @Test fun cancellingKeepsThePartForLater() = runBlocking {
        val slow = object : UpdateNet {
            override fun text(url: String) = throw IOException()
            override fun open(url: String, from: Long) = RangeBody(from, data.size.toLong(), object : InputStream() {
                var pos = from.toInt()
                override fun read() = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    Thread.sleep(2)
                    if (pos >= data.size) return -1
                    val n = minOf(len, 4096, data.size - pos); System.arraycopy(data, pos, b, off, n); pos += n; return n
                }
            })
        }
        var job: Job? = null
        job = launch(kotlinx.coroutines.Dispatchers.IO) {
            downloader(slow).download(apk("https://a/x"), tmp.root) { done, _ -> if (done > 50_000) job?.cancel() }
        }
        withTimeout(10_000) { job.join() }
        val part = java.io.File(tmp.root, "$dataSha.part")
        assertTrue(part.length() in 50_000L until data.size.toLong())
        // And the next attempt resumes from there.
        val net = FakeNet(mapOf("https://a/x" to data))
        val f = downloader(net).download(apk("https://a/x"), tmp.root) { _, _ -> }
        assertArrayEquals(data, f.readBytes())
        assertTrue(net.requests.single().second > 0)
    }
}
