package io.github.timhub135.dsh.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

/**
 * The release manifest the in-app updater reads (`update.json`, format 1; written by
 * tools/make-update-manifest.py, docs/RELEASE.md). One per release: uploaded as a release asset
 * and committed as `update/latest.json`, so GitHub, raw.githubusercontent.com and jsDelivr all
 * serve the same file ([SOURCES]). Everything the updater trusts is checked again on the phone:
 * the APK's SHA-256 against [Apk.sha256], then its package, versionCode and signer against the
 * installed app (ApkCheck) — [signerSha256] only lets the app refuse early, before a download.
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apk: Apk,
    /** SHA-256 of the release signing certificate (lowercase hex, no colons). */
    val signerSha256: String,
    /** Release notes (Markdown) by language (`zh`, `en`). */
    val notes: Map<String, String>,
    val publishedAt: String?,
    /** The raw text, kept so a found update survives a process restart without a new fetch. */
    val raw: String,
) {
    data class Apk(val name: String, val size: Long, val sha256: String, val urls: List<String>)

    /** Notes in the UI language ([lang] `zh` / `en` …), else English, else any. */
    fun notesFor(lang: String): String? = notes[lang] ?: notes["en"] ?: notes.values.firstOrNull()

    class Invalid(val path: String, reason: String) : Exception("$path: $reason")

    companion object {
        const val FORMAT = 1L
        private const val REPO = "Timhub-135/DSH-Android"

        /**
         * Where `update.json` is fetched from, in order (first valid one wins, the next starts a few
         * seconds after the previous: UpdateFetch.firstSuccess). The release asset is the source of
         * truth; the repository copy covers networks where github.com is slow or blocked (the same
         * jsDelivr CDNs the engine falls back to, js/packages/plugin-extensions/fetch-sources.js).
         */
        val SOURCES = listOf(
            "https://github.com/$REPO/releases/latest/download/update.json",
            "https://raw.githubusercontent.com/$REPO/main/update/latest.json",
            "https://cdn.jsdelivr.net/gh/$REPO@main/update/latest.json",
            "https://fastly.jsdelivr.net/gh/$REPO@main/update/latest.json",
            "https://gcore.jsdelivr.net/gh/$REPO@main/update/latest.json",
            "https://testingcf.jsdelivr.net/gh/$REPO@main/update/latest.json",
        )
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and validates; [packageName]: when the manifest names a package it must be this one. */
        fun parse(text: String, packageName: String? = null): UpdateManifest {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: throw Invalid("$", "not a JSON object")
            if (root.long("format") != FORMAT) throw Invalid("$.format", "must be $FORMAT")
            root.str("packageName")?.let { if (packageName != null && it != packageName) throw Invalid("$.packageName", "is $it, not $packageName") }
            val code = root.long("versionCode")?.takeIf { it > 0 } ?: throw Invalid("$.versionCode", "must be a positive integer")
            val name = root.str("versionName")?.takeIf { it.isNotBlank() } ?: throw Invalid("$.versionName", "required")
            val minSdk = root.long("minSdk")?.takeIf { it in 1..1000 }?.toInt() ?: throw Invalid("$.minSdk", "must be an API level")
            val apk = root["apk"] as? JsonObject ?: throw Invalid("$.apk", "must be an object")
            val size = apk.long("size")?.takeIf { it > 0 } ?: throw Invalid("$.apk.size", "must be a positive integer")
            val sha = hex64(apk.str("sha256"), "$.apk.sha256")
            val urls = (apk["urls"] as? JsonArray)?.mapIndexed { i, u ->
                val s = (u as? JsonPrimitive)?.takeIf { it.isString }?.content
                val uri = s?.let { runCatching { URI(it) }.getOrNull() }
                if (uri?.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null) throw Invalid("$.apk.urls[$i]", "must be an https URL")
                s
            } ?: throw Invalid("$.apk.urls", "must be an array")
            if (urls.isEmpty()) throw Invalid("$.apk.urls", "must not be empty")
            val apkName = apk.str("name")?.takeIf { it.isNotBlank() } ?: "DSH-Android-$name.apk"
            val signer = hex64(root.str("signerSha256"), "$.signerSha256")
            val notes = when (val n = root["notes"]) {
                null -> emptyMap()
                is JsonPrimitive -> n.contentOrNull?.takeIf { n.isString && it.isNotBlank() }?.let { mapOf("en" to it) } ?: throw Invalid("$.notes", "must be text")
                is JsonObject -> n.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
                else -> throw Invalid("$.notes", "must be an object of texts")
            }
            return UpdateManifest(code, name, minSdk, Apk(apkName, size, sha, urls), signer, notes, root.str("publishedAt"), text)
        }

        /** 64 hex digits; colons (keytool / apksigner style) and case are normalized away. */
        private fun hex64(s: String?, path: String): String =
            s?.replace(":", "")?.lowercase()?.takeIf { HEX64.matches(it) } ?: throw Invalid(path, "must be a SHA-256 hex digest")

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}

/** Whether a valid manifest is an update to offer this device. */
enum class Offer {
    /** Newer than the installed build, runs here, not skipped. */
    Offer,
    /** Same or older versionCode (a stale mirror, or already updated). */
    NotNewer,
    /** Needs a newer Android than this device has. */
    Unsupported,
    /** The user chose 跳过此版本 for this versionCode. */
    Skipped;

    companion object {
        fun of(m: UpdateManifest, installedCode: Long, sdk: Int, skippedCode: Long?): Offer = when {
            m.versionCode <= installedCode -> NotNewer
            m.minSdk > sdk -> Unsupported
            skippedCode != null && m.versionCode == skippedCode -> Skipped
            else -> Offer
        }
    }
}
