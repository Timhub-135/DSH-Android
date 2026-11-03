package io.github.jackwener.dsh.feature.account

import android.content.Intent
import android.net.Uri
import java.util.Base64

/** What the Platform WebView does with a main-frame navigation (or a popup's first one). */
internal enum class WebNav {
    /** Load it in this WebView. */
    Load,
    /** `dsh-android://account/…` (directly or as an `intent:` URL): the sign-in completion page returned to the app. */
    Returned,
    /** An `intent:` URL for some other app (started without a component, or its fallback URL). */
    IntentUrl,
    /** Another scheme (mailto:, tel:, market:, …): the app that handles it. */
    Outside,
    /** Swallow it (a `dsh-android:` link that is not the account callback). */
    Drop,
}

/**
 * The sign-in WebView's routing decisions and the "Google refuses embedded browsers" signals,
 * kept free of WebView state so they are unit-tested (SignInRoutingTest).
 *
 * Every http(s) navigation stays in the WebView, on every Platform page, whether tapped,
 * scripted or a server redirect: the OAuth flow (Platform → AWS WAF → Google / WeChat → the
 * engine's loopback callback) is bound to this WebView's cookies and to server-side session ids
 * (Google's `signin/oauth/v3/consent?as=…&xsrf=…`), so a step handed to another browser fails
 * (#13, #49: Google answers 400 malformed).
 */
internal object SignInRouting {
    const val APP_SCHEME = "dsh-android"
    private const val APP_HOST = "account"

    /** The engine's completion link `dsh-android://account/login?result=…` (the scheme is case-insensitive). */
    fun isCallback(uri: Uri?): Boolean = uri != null && uri.scheme?.lowercase() == APP_SCHEME && uri.host?.lowercase() == APP_HOST

    fun route(uri: Uri): WebNav = when (uri.scheme?.lowercase()) {
        "http", "https", "about", "data", "blob", "javascript" -> WebNav.Load
        APP_SCHEME -> if (isCallback(uri)) WebNav.Returned else WebNav.Drop
        "intent" -> if (isCallbackIntent(uri.toString())) WebNav.Returned else WebNav.IntentUrl
        else -> WebNav.Outside
    }

    /** `intent://account/login?…#Intent;scheme=dsh-android;package=…;end` (what the engine's completion page opens). */
    fun isCallbackIntent(url: String): Boolean {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
        return isCallback(intent.data) || intent.scheme?.lowercase() == APP_SCHEME
    }

    /**
     * Google refuses OAuth in embedded WebViews (its "secure browsers" policy, RFC 8252): it
     * answers `Error 403: disallowed_useragent` on `accounts.google.com/signin/oauth/error…`
     * (the reason is in the base64 protobuf `authError` parameter) or the "This browser or app
     * may not be secure" page (`…/signin/rejected`, `…/deniedsigninrejected`). These URLs mean
     * the sign-in can only continue in a real browser.
     */
    fun embeddedBlocked(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!isGoogleAccounts(uri.host)) return false
        if (url.contains(DISALLOWED, ignoreCase = true)) return true
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith("/rejected") || "deniedsigninrejected" in path) return true
        val authError = runCatching { uri.getQueryParameter("authError") }.getOrNull() ?: return false
        return decodeBase64(authError)?.contains(DISALLOWED, ignoreCase = true) == true
    }

    /** The same refusal, read from the text of a Google page (its URL does not always carry it). */
    fun blockedPageText(text: String?): Boolean =
        !text.isNullOrEmpty() && BLOCKED_TEXT.any { text.contains(it, ignoreCase = true) }

    /** Google Accounts pages, where [blockedPageText] is worth checking. */
    fun isGoogleAccounts(host: String?): Boolean = host?.lowercase()?.let { it == "accounts.google.com" || it.startsWith("accounts.google.") } == true

    private const val DISALLOWED = "disallowed_useragent"
    private val BLOCKED_TEXT = listOf(
        DISALLOWED,
        "This browser or app may not be secure",
        "此浏览器或应用可能不安全",
        "此瀏覽器或應用程式可能不安全",
        "Этот браузер или приложение могут быть небезопасны",
    )

    private fun decodeBase64(s: String): String? {
        val clean = s.trim().trimEnd('=')
        val bytes = runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(clean.replace(' ', '+')) }.getOrNull()
            ?: return null
        return String(bytes, Charsets.ISO_8859_1)
    }
}
