package io.github.timhub135.dsh.core.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.engine.EngineManager
import io.github.timhub135.dsh.core.session.o
import io.github.timhub135.dsh.core.session.s
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app side of DeepSeek account login (dsh ui-settings-account's plugin state, over the SDK
 * channel instead of Typert Remote; see docs/ENGINE-CONTRACT.md §2.4).
 *
 * The engine owns everything secret: PKCE, the loopback callback on 127.0.0.1, the token in
 * `$DSH_HOME`'s credential store, model/search authentication. This class mirrors the safe view,
 * asks the UI to show the authorization page in the in-app sign-in screen ([page]; a WebView, so
 * the flow never leaves the app whatever the default browser is), closes it when the attempt
 * settles, and keeps a small display cache (name / contact / avatar) so the drawer and the
 * onboarding decision work while the engine is stopped.
 *
 * 用浏览器登录 ([signInWithBrowser], #49): providers that refuse embedded WebViews (Google's
 * `disallowed_useragent`) get the whole flow in a real browser instead ([browserRequests],
 * opened by MainActivity as a Custom Tab). The engine side is the same: Platform redirects that
 * browser to the loopback callback, and the completion page's `dsh-android://account` link
 * returns to MainActivity ([onSignInReturned]).
 */
@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: Lazy<EngineManager>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val avatarFile = File(context.filesDir, "account-avatar.img")
    private val refreshLock = Mutex()

    private val _state = MutableStateFlow(AccountUi(signedIn = prefs.getBoolean(K_SIGNED_IN, false), cached = readCache()))
    val state: StateFlow<AccountUi> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<AccountNotice>(extraBufferCapacity = 4)
    /** Live toasts (dsh AccountMenu): session expired, model needs sign-in. Not replayed. */
    val notices: SharedFlow<AccountNotice> = _notices.asSharedFlow()

    private val _page = MutableStateFlow<PlatformPage?>(null)
    /** The Platform page MainActivity shows full-screen in its WebView (sign-in, 充值, 用量), or null. */
    val page: StateFlow<PlatformPage?> = _page.asStateFlow()

    private val _browser = Channel<String>(Channel.CONFLATED)
    /**
     * Authorization pages to open in the browser (用浏览器登录); MainActivity collects them while
     * started. Buffered (latest only), so a request made in the background is opened on return.
     */
    val browserRequests: Flow<String> = _browser.receiveAsFlow()
    /** The user chose 用浏览器登录: this process's attempts open in the browser, not the in-app screen. */
    @Volatile var useBrowser: Boolean = false
        internal set

    private val _avatar = MutableStateFlow(loadAvatar())
    val avatar: StateFlow<Bitmap?> = _avatar.asStateFlow()

    /** The attempt this UI started (and therefore opens the browser for / returns from). */
    @Volatile private var ownAttempt: String? = null
    /** A start request is in flight: the next new active attempt is ours. */
    @Volatile private var claiming = false
    /** The dialog was dismissed before the engine answered the start request. */
    @Volatile private var abandoned = false
    @Volatile private var openedFor: String? = null
    /** Theme passed to Platform's login page (dsh authorizeUrlWithTheme). */
    @Volatile var darkTheme: Boolean = false

    val signedIn: Boolean get() = _state.value.signedIn
    /** A login attempt is in progress; the engine must stay up. */
    val busy: Boolean get() = _state.value.active

    // ---- UI operations ------------------------------------------------------------------------

    fun showLogin(visible: Boolean) = _state.update { it.copy(loginVisible = visible, loginFailed = if (visible) it.loginFailed else false) }

    /** dsh `start`: open the dialog and begin (or join) a sign-in attempt in the engine. */
    fun startSignIn() {
        _state.update { it.copy(loginVisible = true, loginFailed = false) }
        scope.launch {
            val previous = _state.value.attempt
            try {
                // A new attempt replaces a finished one; an active one is joined (dsh startSignIn).
                if (previous?.phase?.active == true && previous.id != PENDING) { ownAttempt = previous.id; openSignInPage(); return@launch }
                if (previous?.id == PENDING) return@launch
                engine.get().cancelIdleShutdown()
                // Placeholder so the dialog shows 正在发起登录… while the engine boots.
                _state.update { it.copy(attempt = SignInAttempt(PENDING, SignInPhase.Initializing)) }
                claiming = true
                abandoned = false
                apply(call("account/sign-in/start", clientParams()))
                // Dismissed while the engine was still booting: drop the attempt it just created.
                if (abandoned) { claiming = false; cancel() }
            } catch (e: Exception) {
                Log.w(TAG, "sign-in start failed", e)
                _state.update { it.copy(loginFailed = true, attempt = it.attempt?.takeUnless { a -> a.id == PENDING }) }
                engine.get().scheduleIdleShutdown()
            } finally {
                claiming = false
            }
        }
    }

    /** Show the authorization page of the active attempt (dsh "打开浏览器"). */
    fun openSignInPage() {
        val a = _state.value.attempt ?: return
        val url = a.authorizeUrl ?: return
        if (a.phase == SignInPhase.WaitingBrowser) show(url)
    }

    /**
     * 用浏览器登录: run the whole sign-in in the browser. An attempt already shown in the in-app
     * screen is cancelled and a fresh one started (its authorization page may be bound to the
     * WebView's cookies); later attempts in this process go to the browser directly.
     */
    fun signInWithBrowser() {
        useBrowser = true
        val a = _state.value.attempt
        if (_page.value?.signIn == true) _page.value = null
        _state.update { it.copy(loginVisible = true, loginFailed = false) }
        if (a == null || !a.phase.active) { startSignIn(); return }
        if (a.id == PENDING || a.phase != SignInPhase.WaitingBrowser) return
        scope.launch {
            cancel()
            if (!_state.value.active) startSignIn()
        }
    }

    /** No browser could take the authorization page: back to the in-app screen. */
    fun onBrowserUnavailable(url: String) {
        useBrowser = false
        if (_state.value.attempt?.phase == SignInPhase.WaitingBrowser) _page.value = PlatformPage(url, signIn = true)
    }

    private fun show(authorizeUrl: String) {
        val url = themed(authorizeUrl)
        if (useBrowser) _browser.trySend(url) else _page.value = PlatformPage(url, signIn = true)
    }

    /** Show a plain Platform page (充值 / 用量 / account) in the in-app screen. */
    fun openPage(url: String) { _page.value = PlatformPage(url, signIn = false) }

    /**
     * The user closed the screen (close button, or Back with no page history left). Leaving the
     * sign-in page abandons the attempt (account cancel) unless it is already being committed.
     */
    fun closePage() {
        val p = _page.value ?: return
        _page.value = null
        if (!p.signIn) return
        val a = _state.value.attempt
        if (a != null && a.phase.active && a.phase != SignInPhase.Committing) scope.launch { cancel() }
    }

    /**
     * The sign-in page reached the engine's completion page, which navigates to
     * `dsh-android://account/login?result=…`: close the screen and re-read the account state
     * (the attempt's own stream frames normally got there first).
     */
    fun onSignInReturned() {
        if (_page.value?.signIn == true) _page.value = null
        onReturnedFromBrowser()
    }

    fun authorizeUrl(): String? = _state.value.attempt?.authorizeUrl?.let(::themed)

    suspend fun cancel() {
        val a = _state.value.attempt ?: return
        if (a.id == PENDING) { abandoned = true; return }
        runCatching { apply(call("account/sign-in/cancel", buildJsonObject { put("attemptId", a.id) })) }
            .onFailure { Log.w(TAG, "cancel failed", it) }
        engine.get().scheduleIdleShutdown()
    }

    /** dsh `hasRunningAccountTasks`; null when it cannot be determined. */
    suspend fun hasRunningTasks(): Boolean? = runCatching {
        (call("account/tasks/running").let { it as? JsonObject }?.get("running") as? JsonPrimitive)?.booleanOrNull
    }.getOrNull()

    /** Local sign-out (remote revocation continues in the engine); API keys are untouched. */
    suspend fun signOut() {
        apply(call("account/sign-out", clientParams()))
        clearCache()
        engine.get().scheduleIdleShutdown()
    }

    /** dsh `refreshAccount`: re-read profile and balance (starts the engine if needed). */
    fun refresh() {
        if (!_state.value.signedIn) return
        scope.launch {
            if (!refreshLock.tryLock()) return@launch
            try {
                val d = call("account/details", clientParams()) as? JsonObject ?: return@launch
                val profile = AccountJson.profile(d.o("profile"))
                val balance = AccountJson.balance(d.o("balance"))
                _state.update { it.copy(profile = profile ?: it.profile, balance = balance ?: it.balance) }
                (profile as? Loaded.Ready)?.value?.let { cache(it) }
            } catch (e: Exception) {
                Log.w(TAG, "account refresh failed", e)
                _state.update { it.copy(profile = it.profile ?: Loaded.Failed, balance = it.balance ?: Loaded.Failed) }
            } finally {
                refreshLock.unlock()
                engine.get().scheduleIdleShutdown()
            }
        }
    }

    /** The completion page's app link arrived (in-app screen, or a browser the link was copied to). */
    fun onReturnedFromBrowser() {
        if (_state.value.known) return
        scope.launch { runCatching { apply(call("account/state")) } }
    }

    // ---- engine side ----------------------------------------------------------------------------

    /** `account/state` read by EngineManager right after start, before `initialize`. */
    fun onEngineView(view: JsonElement) = apply(view)

    suspend fun onNotification(method: String, params: JsonObject) {
        when (method) {
            "account.changed" -> params.o("view")?.let(::apply)
            "account.session-expired" -> { clearCache(); _notices.emit(AccountNotice.SessionExpired) }
            "account.signed-out" -> { clearCache(); _state.update { it.copy(signedIn = false, profile = null, balance = null) } }
            "account.model-sign-in-required" -> _notices.emit(AccountNotice.ModelSignInRequired)
            "account.callback" -> Log.i(TAG, "login callback answered: ${params.s("result")}")
        }
    }

    /** The engine went away: an attempt it owned is gone with it. */
    fun onEngineStopped() {
        _state.update { s ->
            if (s.active) s.copy(attempt = s.attempt?.copy(phase = SignInPhase.Failed, errorCode = "no-response"), known = false)
            else s.copy(known = false)
        }
        if (_page.value?.signIn == true) _page.value = null
    }

    private fun apply(view: JsonElement) {
        val v = view as? JsonObject ?: return
        val signedIn = v.s("status") == "credential-stored"
        val before = _state.value
        // RPC answers and stream frames interleave; never step an attempt backwards.
        val attempt = AccountJson.attempt(v.o("attempt")).let { a ->
            val b = before.attempt
            if (a != null && b != null && a.id == b.id && a.phase.ordinal < b.phase.ordinal) b else a
        }
        val links = v.o("links")
        if (claiming && attempt != null && attempt.phase.active && attempt.id != ownAttempt) { ownAttempt = attempt.id; claiming = false }
        _state.update { s ->
            s.copy(
                known = true, signedIn = signedIn, attempt = attempt,
                usageUrl = links?.s("usageUrl") ?: s.usageUrl, topUpUrl = links?.s("topUpUrl") ?: s.topUpUrl,
                profile = if (signedIn) s.profile else null, balance = if (signedIn) s.balance else null,
                loginVisible = if (signedIn && !before.signedIn) false else s.loginVisible,
                loginFailed = if (attempt != null && attempt.id != before.attempt?.id) false else s.loginFailed,
            )
        }
        prefs.edit().putBoolean(K_SIGNED_IN, signedIn).apply()
        if (!signedIn && before.signedIn) clearCache()
        if (attempt != null && attempt.id == ownAttempt) {
            maybeOpenSignIn(attempt)
            if (!attempt.phase.active && before.attempt?.phase?.active == true) onAttemptSettled()
        }
        // The attempt behind an open sign-in page is over (signed in, cancelled elsewhere, expired,
        // failed): the dialog takes over with the result.
        if (_page.value?.signIn == true && (attempt == null || !attempt.phase.active || signedIn)) _page.value = null
        if (signedIn && !before.signedIn) {
            // New login: the next prompt switches the engine route (EngineManager.sdk) when no API key is set.
            refresh()
        }
    }

    private fun maybeOpenSignIn(a: SignInAttempt) {
        val url = a.authorizeUrl ?: return
        if (a.phase != SignInPhase.WaitingBrowser || openedFor == a.id) return
        openedFor = a.id
        show(url)
    }

    /** dsh closes its authorization tab when the attempt settles; the sign-in page is closed in [apply]. */
    private fun onAttemptSettled() {
        ownAttempt = null
        engine.get().scheduleIdleShutdown()
    }

    private fun themed(url: String): String = runCatching {
        android.net.Uri.parse(url).buildUpon().clearQuery().apply {
            val uri = android.net.Uri.parse(url)
            uri.queryParameterNames.filter { it != "theme" }.forEach { k -> uri.getQueryParameters(k).forEach { v -> appendQueryParameter(k, v) } }
            appendQueryParameter("theme", if (darkTheme) "dark" else "light")
        }.build().toString()
    }.getOrDefault(url)

    private suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement =
        engine.get().control().request(method, params)

    /** dsh AccountClientMetadata minus `version` (the engine reports its dsh version). */
    private fun clientParams() = buildJsonObject {
        putJsonObject("client") {
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezoneOffsetSeconds", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
        }
    }

    // ---- display cache ----------------------------------------------------------------------------

    private fun readCache(): AccountProfile? {
        if (!prefs.getBoolean(K_SIGNED_IN, false)) return null
        val name = prefs.getString(K_NAME, null); val contact = prefs.getString(K_CONTACT, null); val avatar = prefs.getString(K_AVATAR, null)
        return if (name == null && contact == null && avatar == null) null else AccountProfile(prefs.getString(K_ID, null), name, contact, avatar)
    }

    private fun cache(p: AccountProfile) {
        val oldAvatar = prefs.getString(K_AVATAR, null)
        prefs.edit().putString(K_ID, p.id).putString(K_NAME, p.name).putString(K_CONTACT, p.contact).putString(K_AVATAR, p.avatarUrl).apply()
        _state.update { it.copy(cached = p) }
        if (p.avatarUrl.isNullOrBlank()) { avatarFile.delete(); _avatar.value = null }
        else if (p.avatarUrl != oldAvatar || _avatar.value == null) scope.launch { fetchAvatar(p.avatarUrl) }
    }

    private fun clearCache() {
        prefs.edit().remove(K_ID).remove(K_NAME).remove(K_CONTACT).remove(K_AVATAR).putBoolean(K_SIGNED_IN, false).apply()
        avatarFile.delete()
        _avatar.value = null
        _state.update { it.copy(cached = null, signedIn = false, profile = null, balance = null) }
    }

    private fun loadAvatar(): Bitmap? = runCatching { if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.path) else null }.getOrNull()

    /** The profile picture is a public HTTPS image; no referrer, no credentials, size-capped. */
    private suspend fun fetchAvatar(url: String) = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = true
            val bytes = c.inputStream.use { input ->
                // InputStream.readNBytes(int) is API 33; read at most MAX_AVATAR + 1 bytes by hand.
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (out.size() <= MAX_AVATAR) { val n = input.read(buf, 0, minOf(buf.size, MAX_AVATAR + 1 - out.size())); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            c.disconnect()
            require(bytes.size <= MAX_AVATAR)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not an image")
            avatarFile.writeBytes(bytes)
            _avatar.value = bmp
        }.onFailure { Log.i(TAG, "avatar unavailable: ${it.message}") }
    }

    private companion object {
        const val TAG = "Account"
        const val PENDING = "pending"
        const val K_SIGNED_IN = "signed_in"
        const val K_ID = "id"
        const val K_NAME = "name"
        const val K_CONTACT = "contact"
        const val K_AVATAR = "avatar_url"
        const val MAX_AVATAR = 2 * 1024 * 1024
    }
}
