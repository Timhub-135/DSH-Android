package io.github.timhub135.dsh.feature.account

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.account.AccountRepository
import io.github.timhub135.dsh.core.account.PlatformPage
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DTopBar
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.HDivider
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.Radius

/**
 * DeepSeek Platform inside the app: the sign-in authorization page and plain Platform pages
 * (充值 / 用量 / account) in a WebView under a DSH top bar, so the flow never depends on how the
 * default browser treats Custom Tabs (some OEM browsers open them as a full browser task whose
 * Back lands on the browser's home page).
 *
 * Sign-in: Platform redirects to the engine's loopback callback (`http://127.0.0.1:<port>/oauth/callback`,
 * cleartext allowed only for loopback in network_security_config); the engine validates and
 * exchanges the code and answers with its completion page, which navigates to
 * `dsh-android://account/login?result=…` (as an `intent:` URL when the engine knows the package).
 * That navigation is intercepted here and closes the screen; the attempt settling closes it too
 * (AccountRepository.apply). Back walks the page history, then pops the screen; closing cancels the attempt.
 *
 * Every http(s) navigation, popups included (a child WebView over the page, so `window.opener`
 * works), stays in this WebView session: the OAuth steps (Platform → Google / WeChat → loopback)
 * are bound to its cookies (SignInRouting, #13 / #49). Non-web schemes go to the app that handles them.
 *
 * Providers that refuse embedded browsers (Google's `disallowed_useragent` / "This browser or app
 * may not be secure") are detected from the URL or page text, and the screen offers 用浏览器登录:
 * the whole flow again in a Custom Tab (AccountRepository.signInWithBrowser). The link below the
 * page offers the same at any time.
 */
@Composable
fun PlatformWebScreen(page: PlatformPage, account: AccountRepository) {
    val c = Dsh.colors
    var web by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (popup != null) popup = null else account.closePage() }
    // This screen is a destination (PlatformWebRoute): Back walks the popup's and then the page's
    // history here, and with none left the NavHost pops the destination (MainActivity then closes the page).
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(popup != null || (canGoBack && web != null)) {
        blocked = false
        val p = popup
        when {
            p == null -> web?.goBack()
            p.canGoBack() -> p.goBack()
            else -> popup = null
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val w = web
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> w?.onResume(); Lifecycle.Event.ON_PAUSE -> w?.onPause(); else -> Unit }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val title = if (page.signIn) stringResource(R.string.web_sign_in_title)
        else pageTitle?.takeUnless { it.isBlank() || it.startsWith("http") || it.startsWith("data:") } ?: stringResource(R.string.web_platform_title)
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().navigationBarsPadding().imePadding()) {
        DTopBar(title, onBack = null) {
            IconBtn(R.drawable.dsw_close_outline, close, contentDescription = stringResource(R.string.close), iconSize = 18.dp, tint = c.labelPrimary)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress in 1..99) Box(Modifier.fillMaxWidth(progress / 100f).fillMaxHeight().background(c.brandPrimary))
        }
        HDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(page) {
                AndroidView(
                    factory = { ctx ->
                        platformWebView(ctx, page, c.bgBase.toArgb(), account,
                            onProgress = { progress = it }, onTitle = { pageTitle = it }, onFailed = { failed = it }, onHistory = { canGoBack = it },
                            onGone = { web = null; popup = null; close() }, onPopup = { popup = it }, onBlocked = { if (page.signIn) blocked = true },
                        ).also { web = it; it.loadUrl(page.url) }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { w ->
                        if (web === w) web = null
                        popup = null
                        w.stopLoading()
                        CookieManager.getInstance().flush()
                        w.destroy()
                    },
                )
            }
            popup?.let { p ->
                key(p) {
                    AndroidView(factory = { p }, modifier = Modifier.fillMaxSize(), onRelease = { w -> w.stopLoading(); w.destroy() })
                }
            }
            if (failed) Column(Modifier.fillMaxSize().background(c.bgBase).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.web_load_failed), style = DshType.s14, color = c.labelSecondary, textAlign = TextAlign.Center)
                DButton(stringResource(R.string.retry), { failed = false; (popup ?: web)?.reload() }, variant = BtnVariant.Outline)
            }
            if (blocked) Column(Modifier.fillMaxSize().background(c.bgBase).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.web_blocked_title), style = DshType.base16m, color = c.labelPrimary, textAlign = TextAlign.Center)
                Text(stringResource(R.string.web_blocked_desc), style = DshType.s14, color = c.labelSecondary, textAlign = TextAlign.Center)
                DButton(stringResource(R.string.web_use_browser), { blocked = false; account.signInWithBrowser() })
                DButton(stringResource(R.string.back), {
                    blocked = false
                    when { popup != null -> popup = null; web?.canGoBack() == true -> web?.goBack() }
                }, variant = BtnVariant.Outline)
            }
        }
        if (page.signIn) {
            HDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.web_use_browser_hint), style = DshType.xs13, color = c.labelTertiary)
                Text(stringResource(R.string.web_use_browser), Modifier.clip(Radius.sm).clickable { account.signInWithBrowser() }.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = DshType.xs13, color = c.link)
            }
        }
    }
}

private const val TAG = "PlatformWeb"

@SuppressLint("SetJavaScriptEnabled")
private fun platformWebView(
    context: Context,
    page: PlatformPage,
    background: Int,
    account: AccountRepository,
    onProgress: (Int) -> Unit,
    onTitle: (String?) -> Unit,
    onFailed: (Boolean) -> Unit,
    onHistory: (canGoBack: Boolean) -> Unit,
    onGone: () -> Unit,
    onPopup: (WebView?) -> Unit,
    onBlocked: () -> Unit,
): WebView = WebView(context).apply {
    setUp(this, page, background, account, onProgress, onTitle, onFailed, onHistory, onGone, onPopup, onBlocked, main = true)
}

/** Settings and clients of the page's WebView and of its popups (the same session and routing). */
@SuppressLint("SetJavaScriptEnabled")
private fun setUp(
    web: WebView,
    page: PlatformPage,
    background: Int,
    account: AccountRepository,
    onProgress: (Int) -> Unit,
    onTitle: (String?) -> Unit,
    onFailed: (Boolean) -> Unit,
    onHistory: (canGoBack: Boolean) -> Unit,
    onGone: () -> Unit,
    onPopup: (WebView?) -> Unit,
    onBlocked: () -> Unit,
    main: Boolean,
): Unit = with(web) {
    val context = web.context
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(background)
    with(settings) {
        // What a sign-in provider's pages need in a WebView: JavaScript, DOM storage, popups
        // (multiple windows; scripted window.open on the sign-in page, where providers open their
        // popup after an async step), and cookies incl. third-party ones (below).
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION") run { allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false }
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = page.signIn
        setSupportMultipleWindows(true)
        setSupportZoom(false)
        // The mobile Chrome UA minus the WebView markers ("; wv", "Version/4.0"): Platform's login
        // (and the AWS WAF challenge in front of it) is meant for a regular mobile browser.
        userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/[\\d.]+"), "")
    }
    CookieManager.getInstance().let { it.setAcceptCookie(true); it.setAcceptThirdPartyCookies(web, true) }

    /** true = handled here (the WebView must not load it). */
    fun route(view: WebView, uri: Uri): Boolean {
        if (page.signIn && SignInRouting.embeddedBlocked(uri.toString())) onBlocked()
        return when (SignInRouting.route(uri)) {
            WebNav.Load -> false
            WebNav.Returned -> { account.onSignInReturned(); true }
            WebNav.IntentUrl -> { openIntentUrl(view, uri.toString()); true }
            WebNav.Outside -> { openOutside(context, Intent(Intent.ACTION_VIEW, uri)); true }
            WebNav.Drop -> true
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && route(view, request.url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            onFailed(false)
            if (page.signIn && SignInRouting.embeddedBlocked(url)) onBlocked()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Google's refusal page does not always say so in its URL: read the page text.
            if (!page.signIn || !SignInRouting.isGoogleAccounts(url?.let(Uri::parse)?.host)) return
            view.evaluateJavascript(BODY_TEXT) { text -> if (SignInRouting.blockedPageText(text)) onBlocked() }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { if (main) onHistory(view.canGoBack()) }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) { Log.i(TAG, "load failed: ${error.errorCode} ${error.description}"); onFailed(true) }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            // An error page from Google Accounts itself (400 malformed, 403 disallowed_useragent):
            // the browser is the way out.
            if (page.signIn && request.isForMainFrame && response.statusCode >= 400 && SignInRouting.isGoogleAccounts(request.url.host)) {
                Log.i(TAG, "Google answered ${response.statusCode} on ${request.url.path}")
                onBlocked()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone (crash=${detail.didCrash()})")
            onGone()
            return true
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
        override fun onReceivedTitle(view: WebView, title: String?) { if (main) onTitle(title) }

        /**
         * target=_blank / window.open: a child WebView over the page, in the same session and with
         * the same routing (a sign-in provider's popup reports back to its opener).
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture && !page.signIn) return false
            val child = WebView(view.context)
            setUp(child, page, background, account, onProgress, onTitle, onFailed, onHistory, onGone, onPopup, onBlocked, main = false)
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            onPopup(child)
            return true
        }

        override fun onCloseWindow(window: WebView) { if (!main) onPopup(null) }
    }
}

private const val BODY_TEXT = "(function(){return document.body?document.body.innerText.slice(0,4000):''})()"

/**
 * `intent:` URLs for other apps (the completion page's app link is [WebNav.Returned]): started as a
 * browsable intent with no explicit component; with no app for it, its `S.browser_fallback_url`
 * loads here, in the same session.
 */
private fun openIntentUrl(view: WebView, url: String) {
    val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
    intent.component = null
    intent.selector = null
    if (!openOutside(view.context, intent)) intent.getStringExtra("browser_fallback_url")
        ?.takeIf { SignInRouting.route(Uri.parse(it)) == WebNav.Load && it.startsWith("http", ignoreCase = true) }
        ?.let { view.post { runCatching { view.loadUrl(it) } } }
}

private fun openOutside(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Log.i(TAG, "no handler for ${intent.data?.scheme}: ${e.message}"); false
} catch (e: SecurityException) {
    Log.i(TAG, "not allowed: ${e.message}"); false
}
