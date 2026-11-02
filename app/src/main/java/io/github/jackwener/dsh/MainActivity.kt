package io.github.jackwener.dsh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.core.account.AccountRepository
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.feature.account.AccountToastHost
import io.github.jackwener.dsh.feature.account.LocalAccount
import io.github.jackwener.dsh.feature.account.SignInDialog
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.feature.conversation.HomeViewModel
import io.github.jackwener.dsh.feature.settings.SettingsSection
import io.github.jackwener.dsh.core.account.PlatformPage
import io.github.jackwener.dsh.nav.HomeRoute
import io.github.jackwener.dsh.nav.OnboardingKeyRoute
import io.github.jackwener.dsh.nav.PlatformWebRoute
import io.github.jackwener.dsh.nav.dshGraph
import io.github.jackwener.dsh.nav.onboardingOnStack
import io.github.jackwener.dsh.nav.startOnboarding
import io.github.jackwener.dsh.nav.toHome
import io.github.jackwener.dsh.service.TapTargets
import io.github.jackwener.dsh.nav.openSettings
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository, secrets: SecretStore, account: AccountRepository) : ViewModel() {
    private val deepSeek = secrets.read() != null || account.signedIn
    /** null = still loading (keeps the splash up). Second: a model credential exists — an API key, a
     *  DeepSeek account login (cached from the engine), or an enabled third-party provider. */
    val boot = settings.settings.map { it.theme to (deepSeek || it.providers.any { p -> p.enabled }) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * The app opens straight into the conversation (dsh home). Onboarding is only the credential
 * choice when there is none (dsh: 登录 DeepSeek 账号 / 使用 API Key); everything else (Android
 * permissions) is asked just in time from the composer slot.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var account: AccountRepository
    @Inject lateinit var extensions: io.github.jackwener.dsh.core.extensions.ExtensionsRepository
    @Inject lateinit var updater: io.github.jackwener.dsh.core.update.AppUpdater
    @Inject lateinit var sessions: io.github.jackwener.dsh.core.session.SessionStore
    private var pendingSession by mutableStateOf<String?>(null)
    /** Settings section to open (EXTRA_SECTION, e.g. the quick panel's 登录 / 添加 API Key / 打开自检). */
    private var pendingSection by mutableStateOf<String?>(null)
    private var navReady by mutableStateOf(false)

    /** Android 10–12: the in-app language (AppLanguage); 13+ does it itself. */
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(io.github.jackwener.dsh.core.platform.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only for a fresh launch: after recreation (rotation, theme) the user may have moved on.
        if (savedInstanceState == null) {
            pendingSession = sessionOf(intent)
            pendingSection = intent.getStringExtra(EXTRA_SECTION)
        }
        handleAppLink(intent)
        if (savedInstanceState == null) handleExtensionShare(intent)
        setContent {
            val vm: MainViewModel = hiltViewModel()
            val boot by vm.boot.collectAsStateWithLifecycle()
            val theme = boot?.first
            // Until the theme is known and the first page chosen (home / onboarding).
            splash.setKeepOnScreenCondition { theme == null || !navReady }
            DshTheme(theme ?: ThemeMode.System) {
                val c = Dsh.colors
                LaunchedEffect(c.isDark) { account.darkTheme = c.isDark }
                // Platform pages (sign-in, 充值, 用量): a destination of their own (PlatformPageSync).
                val platformPage by account.page.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalAccount provides account) {
                    Box(Modifier.fillMaxSize().background(c.bgBase)) {
                        boot?.let { AppNav(it.second, platformPage) }
                        AccountToastHost(account)
                        // The update prompt (AppUpdater), over whatever page is showing.
                        io.github.jackwener.dsh.feature.update.UpdateHost(updater)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSession = sessionOf(intent)
        pendingSection = intent.getStringExtra(EXTRA_SECTION)
        handleAppLink(intent)
        handleExtensionShare(intent)
    }

    /** The conversation to show: EXTRA_SESSION, or for EXTRA_OPEN_RUNNING the one running / waiting now (null: leave the page as it is). */
    private fun sessionOf(intent: Intent): String? =
        intent.getStringExtra(EXTRA_SESSION) ?: if (intent.getBooleanExtra(EXTRA_OPEN_RUNNING, false)) TapTargets.running(sessions.status.value) else null

    /**
     * 分享 → 安装为 DSH 扩展 (the `.ExtensionInstall` alias): copy a shared file into the app (the
     * share grant ends with this activity), then open 设置 → 扩展, which starts the install / import.
     */
    private fun handleExtensionShare(intent: Intent?) {
        if (intent?.component?.className?.endsWith(".ExtensionInstall") != true) return
        @Suppress("DEPRECATION")
        val stream = (intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)) ?: intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (stream == null && text.isNullOrEmpty()) return
        lifecycleScope.launch {
            val input = if (stream != null) runCatching { extensions.stage(stream).path }.getOrNull() else text
            if (input != null) { extensions.pendingInput.value = input; pendingSection = SettingsSection.Extensions.name }
        }
    }

    /**
     * `dsh-android://account/login?result=…` from the engine's sign-in completion page when it was
     * opened outside the app (the copied sign-in link); the in-app screen intercepts it itself.
     */
    private fun handleAppLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "dsh-android" && data.host == "account") account.onSignInReturned()
    }

    /**
     * The page tree (nav.DshNavGraph), home as the fixed start. Settings links from outside the tree
     * (EXTRA_SECTION, share, the sign-in dialog) use nav.openSettings: home → 设置 → section.
     */
    @Composable
    private fun AppNav(hasCredentials: Boolean, platformPage: PlatformPage?) {
        val nav = rememberNavController()
        val home: HomeViewModel = hiltViewModel()
        val accountState by account.state.collectAsStateWithLifecycle()
        // Home is the fixed start; without a credential the first launch goes to onboarding (once, not after recreation).
        var routed by rememberSaveable { mutableStateOf(false) }
        // First, so the links below already see onboarding.
        LaunchedEffect(Unit) {
            if (!routed && !hasCredentials) nav.startOnboarding()
            routed = true; navReady = true
        }
        LaunchedEffect(pendingSession) {
            // Never a plugin's background session (#43; e.g. an old notification of one): home stays as it is.
            pendingSession?.let { id -> if (!sessions.isForeign(id)) home.open(id); nav.toHome(); pendingSession = null }
        }
        LaunchedEffect(pendingSection) {
            // Ignored during onboarding (home is not on the stack yet), as before.
            pendingSection?.let { sec -> pendingSection = null; nav.openSettings(sec) }
        }
        PlatformPageSync(nav, platformPage)
        // The dialog is a window of its own: hidden while the Platform screen is up.
        if (accountState.loginVisible && platformPage == null) SignInDialog(account, onUseApiKey = {
            if (nav.onboardingOnStack()) nav.navigate(OnboardingKeyRoute) { launchSingleTop = true } else nav.openSettings(SettingsSection.Model.name)
        })
        NavHost(nav, startDestination = HomeRoute, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            dshGraph(nav) { home }
        }
    }

    /**
     * AccountRepository.page (sign-in, 充值, 用量) ⇄ the PlatformWebRoute destination: a page pushes
     * it over the current one, closing the page pops it, and Back popping it closes the page.
     */
    @Composable
    private fun PlatformPageSync(nav: NavHostController, page: PlatformPage?) {
        val stack by nav.currentBackStack.collectAsStateWithLifecycle()
        val shown = stack.any { it.destination.hasRoute(PlatformWebRoute::class) }
        var pushed by remember { mutableStateOf(false) }
        LaunchedEffect(page != null, shown) {
            when {
                page != null && shown -> pushed = true
                page != null && !pushed -> nav.navigate(PlatformWebRoute) { launchSingleTop = true }
                page != null -> { pushed = false; account.closePage() }
                shown -> { pushed = false; nav.popBackStack(PlatformWebRoute, inclusive = true) }
                else -> pushed = false
            }
        }
    }

    companion object {
        const val EXTRA_SESSION = "session"
        /** A SettingsSection name to open on arrival. */
        const val EXTRA_SECTION = "settings_section"
        /** The ongoing notifications' tap (Notifications.openRunning): the conversation running at tap time. */
        const val EXTRA_OPEN_RUNNING = "open_running"
    }
}
