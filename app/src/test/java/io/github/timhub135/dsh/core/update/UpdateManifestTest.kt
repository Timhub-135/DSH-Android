package io.github.timhub135.dsh.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class UpdateManifestTest {
    private val sha = "aa1b6d28eeed618be26a6dd35c247f4be8378dfbc5ba5f56f1454f0faf0b796a"
    private val signer = "31c7e843953e3718ae633a6c7396068ddce8ca822f628cd3db4969f6ca3fc6ab"

    private fun manifest(
        format: String = "1", code: String = "22", minSdk: String = "29", urls: String = """["https://github.com/Timhub-135/DSH-Android/releases/download/v0.3.4/a.apk"]""",
        apkSha: String = "\"$sha\"", signerSha: String = "\"$signer\"", notes: String = """{"zh":"中文说明","en":"English notes"}""", extra: String = "",
    ) = """{"format":$format,"packageName":"io.github.timhub135.dsh","versionCode":$code,"versionName":"0.3.4","minSdk":$minSdk,
        "apk":{"name":"a.apk","size":100,"sha256":$apkSha,"urls":$urls},"signerSha256":$signerSha,"notes":$notes,"publishedAt":"2026-11-01T00:00:00Z"$extra}"""

    private fun invalidAt(text: String, pkg: String? = "io.github.timhub135.dsh"): String =
        try { UpdateManifest.parse(text, pkg); fail("accepted: $text"); "" } catch (e: UpdateManifest.Invalid) { e.path }

    @Test fun parsesAValidManifest() {
        val m = UpdateManifest.parse(manifest(), "io.github.timhub135.dsh")
        assertEquals(22L, m.versionCode)
        assertEquals("0.3.4", m.versionName)
        assertEquals(29, m.minSdk)
        assertEquals(UpdateManifest.Apk("a.apk", 100, sha, listOf("https://github.com/Timhub-135/DSH-Android/releases/download/v0.3.4/a.apk")), m.apk)
        assertEquals(signer, m.signerSha256)
        assertEquals("2026-11-01T00:00:00Z", m.publishedAt)
    }

    @Test fun digestsAreNormalized() {
        val colons = signer.uppercase().chunked(2).joinToString(":")
        val m = UpdateManifest.parse(manifest(apkSha = "\"${sha.uppercase()}\"", signerSha = "\"$colons\""))
        assertEquals(sha, m.apk.sha256)
        assertEquals(signer, m.signerSha256)
    }

    @Test fun unknownFieldsAreIgnored() {
        assertEquals(22L, UpdateManifest.parse(manifest(extra = ""","future":{"x":1}""")).versionCode)
    }

    @Test fun rejectsWhatTheAppCannotTrust() {
        assertEquals("$", invalidAt("not json"))
        assertEquals("$", invalidAt("[1]"))
        assertEquals("$.format", invalidAt(manifest(format = "2")))
        assertEquals("$.format", invalidAt(manifest(format = "\"1\"")))
        assertEquals("$.versionCode", invalidAt(manifest(code = "0")))
        assertEquals("$.versionCode", invalidAt(manifest(code = "\"22\"")))
        assertEquals("$.minSdk", invalidAt(manifest(minSdk = "-1")))
        assertEquals("$.apk.urls[0]", invalidAt(manifest(urls = """["http://example.com/a.apk"]""")))
        assertEquals("$.apk.urls[1]", invalidAt(manifest(urls = """["https://a.example/a.apk","https://user:pw@b.example/a.apk"]""")))
        assertEquals("$.apk.urls", invalidAt(manifest(urls = "[]")))
        assertEquals("$.apk.sha256", invalidAt(manifest(apkSha = "\"abc\"")))
        assertEquals("$.signerSha256", invalidAt(manifest(signerSha = "null")))
        assertEquals("$.packageName", invalidAt(manifest(), pkg = "io.github.timhub135.dsh.debug"))
    }

    @Test fun notesPickTheUiLanguage() {
        val m = UpdateManifest.parse(manifest())
        assertEquals("中文说明", m.notesFor("zh"))
        assertEquals("English notes", m.notesFor("en"))
        assertEquals("English notes", m.notesFor("fr"))
        assertEquals("only", UpdateManifest.parse(manifest(notes = "\"only\"")).notesFor("zh"))
        assertEquals("中文", UpdateManifest.parse(manifest(notes = """{"zh":"中文"}""")).notesFor("en"))
        assertNull(UpdateManifest.parse(manifest(notes = "{}")).notesFor("zh"))
    }

    @Test fun offerComparesVersionSdkAndSkip() {
        val m = UpdateManifest.parse(manifest(code = "22", minSdk = "30"))
        assertEquals(Offer.Offer, Offer.of(m, installedCode = 21, sdk = 30, skippedCode = null))
        assertEquals(Offer.NotNewer, Offer.of(m, installedCode = 22, sdk = 34, skippedCode = null))
        assertEquals(Offer.NotNewer, Offer.of(m, installedCode = 23, sdk = 34, skippedCode = null))
        assertEquals(Offer.Unsupported, Offer.of(m, installedCode = 21, sdk = 29, skippedCode = null))
        assertEquals(Offer.Skipped, Offer.of(m, installedCode = 21, sdk = 34, skippedCode = 22))
        // Skipping 22 does not hide a later release.
        assertEquals(Offer.Offer, Offer.of(UpdateManifest.parse(manifest(code = "23")), installedCode = 21, sdk = 34, skippedCode = 22))
    }

    /** The committed update/latest.json (what raw.githubusercontent.com / jsDelivr serve) must stay readable by the app. */
    @Test fun committedManifestIsValidWhenPresent() {
        val f = File("../update/latest.json")
        // No public release yet on this fork: tools/make-update-manifest.py recreates it on release.
        if (!f.isFile) return
        assertTrue("run from the app module: ${f.absolutePath}", f.isFile)
        val m = UpdateManifest.parse(f.readText(), "io.github.timhub135.dsh")
        assertEquals(signer, m.signerSha256)
        assertTrue(m.apk.urls.first().startsWith("https://github.com/Timhub-135/DSH-Android/releases/download/v${m.versionName}/"))
        assertTrue(m.notes.keys.containsAll(listOf("zh", "en")))
    }

    @Test fun sourcesStartWithTheReleaseAssetAndAreHttps() {
        assertEquals("https://github.com/Timhub-135/DSH-Android/releases/latest/download/update.json", UpdateManifest.SOURCES.first())
        assertTrue(UpdateManifest.SOURCES.all { it.startsWith("https://") })
        assertTrue(UpdateManifest.SOURCES.any { it.startsWith("https://cdn.jsdelivr.net/gh/Timhub-135/DSH-Android@main/update/latest.json") })
    }

    @Test fun policyChecksOncePerDay() {
        val day = UpdatePolicy.DAY_MS
        assertTrue(UpdatePolicy.autoCheckDue(true, 0, 1_000))
        assertTrue(!UpdatePolicy.autoCheckDue(false, 0, 10 * day))
        assertTrue(!UpdatePolicy.autoCheckDue(true, 5 * day, 5 * day + day - 1))
        assertTrue(UpdatePolicy.autoCheckDue(true, 5 * day, 6 * day))
        // Clock set back: due rather than stuck for the difference.
        assertTrue(UpdatePolicy.autoCheckDue(true, 5 * day, 4 * day))
        assertTrue(!UpdatePolicy.promptDue(5 * day, 5 * day + 1))
        assertTrue(UpdatePolicy.promptDue(5 * day, 6 * day))
        assertTrue(UpdatePolicy.promptDue(0, 1))
    }
}
