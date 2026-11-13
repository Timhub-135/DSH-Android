package io.github.timhub135.dsh.core.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Pages that are not part of the app's own flows (GitHub releases, a Platform page's privacy
 * policy, …) open in the user's browser as its own task. DeepSeek Platform pages (sign-in, 充值,
 * 用量) do not come here: they use the in-app WebView screen (feature/account/PlatformWebScreen.kt)
 * so Back always returns to DSH, whatever the default browser does with Custom Tabs. The one
 * exception is [signInIntent]: 用浏览器登录, for providers that refuse embedded WebViews (#49).
 */
object Browser {
    /** Opens [url] (http/https) in the default browser; false when nothing could handle it. */
    fun openExternal(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (e: ActivityNotFoundException) {
            Log.w("Browser", "no browser for $url", e); false
        }
    }

    /**
     * The whole sign-in flow in a real browser (RFC 8252): a Custom Tab of the default browser
     * (or another Custom Tabs provider) when there is one — launched from the activity so it sits
     * on top of MainActivity's task, and the completion page's `dsh-android://account` link
     * (MainActivity, singleTask) clears it — else a plain VIEW in the default browser.
     * Platform's redirect to the engine's loopback callback (`http://127.0.0.1:<port>`) works
     * from any browser on the device; the engine (a foreground service) answers it.
     */
    fun signInIntent(context: Context, url: String, dark: Boolean, toolbar: Int): Intent {
        val uri = Uri.parse(url)
        val provider = runCatching { CustomTabsClient.getPackageName(context, emptyList()) }.getOrNull()
        val intent = if (provider != null) {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar).build())
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build().intent.setPackage(provider).setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** Starts [signInIntent]; a failed Custom Tab falls back to a plain VIEW. False when no browser could take it. */
    fun openSignIn(context: Context, url: String, dark: Boolean, toolbar: Int): Boolean {
        val intent = signInIntent(context, url, dark, toolbar)
        return try { context.startActivity(intent); true } catch (e: ActivityNotFoundException) {
            Log.w("Browser", "sign-in browser unavailable (${intent.`package`})", e)
            intent.`package` != null && openExternal(context, url)
        } catch (e: SecurityException) {
            Log.w("Browser", "sign-in browser not allowed", e); openExternal(context, url)
        }
    }
}
