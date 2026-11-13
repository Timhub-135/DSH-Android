package io.github.timhub135.dsh.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.feature.account.LocalAccount
import io.github.timhub135.dsh.feature.account.PlatformWebScreen
import io.github.timhub135.dsh.feature.automation.AutomationScreen
import io.github.timhub135.dsh.feature.automation.RuleDetailScreen
import io.github.timhub135.dsh.feature.conversation.HomeScreen
import io.github.timhub135.dsh.feature.conversation.HomeViewModel
import io.github.timhub135.dsh.feature.extensions.ExtensionsSubPage
import io.github.timhub135.dsh.feature.extensions.extensionsPageTitle
import io.github.timhub135.dsh.feature.settings.AppNotesScreen
import io.github.timhub135.dsh.feature.settings.EnhanceStep
import io.github.timhub135.dsh.feature.settings.ModeScreen
import io.github.timhub135.dsh.feature.settings.NotificationAppsScreen
import io.github.timhub135.dsh.feature.settings.Onboarding
import io.github.timhub135.dsh.feature.settings.ProviderScreen
import io.github.timhub135.dsh.feature.settings.SettingsIndexScreen
import io.github.timhub135.dsh.feature.settings.SettingsPage
import io.github.timhub135.dsh.feature.settings.SettingsSection
import io.github.timhub135.dsh.feature.settings.SettingsSectionScreen
import io.github.timhub135.dsh.feature.settings.shellAccessOf

/**
 * Every page of the app as a destination; home is the fixed start. Pages get Back
 * (`nav.up(entry)`, the same popBackStack system Back does) and `open` (a plain navigate one level
 * deeper). [home] is the activity-scoped conversation ViewModel.
 */
fun NavGraphBuilder.dshGraph(nav: NavHostController, home: () -> HomeViewModel) {
    val open: (Route) -> Unit = { nav.navigate(it) }
    composable<HomeRoute> { HomeScreen(home(), onSettings = { nav.openSettings(it) }, onAutomation = { nav.navigate(AutomationRoute) }) }
    composable<OnboardingRoute> { e -> OnboardingPage(nav, e, keyStep = false) }
    composable<OnboardingKeyRoute> { e -> OnboardingPage(nav, e, keyStep = true) }
    composable<EnhanceRoute> { e -> EnhanceStep(onDone = { nav.up(e) }) }
    composable<PlatformWebRoute> {
        val account = LocalAccount.current
        val page = account?.page?.collectAsStateWithLifecycle()?.value
        // Between closing and the pop (MainActivity): a blank page, not a flash of the page below.
        if (account != null && page != null) PlatformWebScreen(page, account) else Box(Modifier.fillMaxSize().background(Dsh.colors.bgBase))
    }
    composable<AutomationRoute> { e ->
        AutomationScreen(onBack = { nav.up(e) }, onNew = { prefill -> home().newSession(prefill); nav.toHome() }, onOpenRule = { open(RuleRoute(it.toString())) })
    }
    composable<RuleRoute> { e -> RuleDetailScreen(onBack = { nav.up(e) }, onOpenSession = { sid -> home().open(sid); nav.toHome() }) }
    navigation<SettingsGraph>(startDestination = SettingsRoute) {
        composable<SettingsRoute> { e -> InSettings(nav, e) { SettingsIndexScreen(onBack = { nav.up(e) }, open = open) } }
        composable<SectionRoute> { e ->
            val section = SettingsSection.of(e.toRoute<SectionRoute>().section)
            InSettings(nav, e) { top -> if (section != null) SettingsSectionScreen(section, onBack = { nav.up(e) }, open = open, onTop = top) }
        }
        composable<ProviderRoute> { e ->
            // 完成 / 删除 / 取消 return to 模型服务, also from a new provider's editor (picker → editor).
            InSettings(nav, e) { ProviderScreen(e.toRoute(), onBack = { nav.up(e) }, open = open, onDone = { nav.toSection() }) }
        }
        composable<ModeRoute> { e ->
            InSettings(nav, e) { ModeScreen(e.toRoute(), onBack = { nav.up(e) }, onReplace = { nav.replaceMode(it) }) }
        }
        composable<PluginRoute> { e -> ExtensionsPage(nav, e, e.toRoute<PluginRoute>()) }
        composable<MarketEntryRoute> { e -> ExtensionsPage(nav, e, e.toRoute<MarketEntryRoute>()) }
        composable<IndexesRoute> { e -> ExtensionsPage(nav, e, IndexesRoute) }
        composable<McpRoute> { e -> ExtensionsPage(nav, e, e.toRoute<McpRoute>()) }
        composable<AppNotesRoute> { e -> InSettings(nav, e) { AppNotesScreen(null, onBack = { nav.up(e) }, onOpen = { open(AppNotesGroupRoute(it)) }) } }
        composable<AppNotesGroupRoute> { e -> InSettings(nav, e) { AppNotesScreen(e.toRoute<AppNotesGroupRoute>().pkg, onBack = { nav.up(e) }) } }
        composable<NotifAppsRoute> { e -> InSettings(nav, e) { NotificationAppsScreen(onBack = { nav.up(e) }) } }
    }
}

/** Onboarding's credential choice, and its 使用 API Key step (the same screen with the key card open). */
@Composable
private fun OnboardingPage(nav: NavHostController, e: NavBackStackEntry, keyStep: Boolean) {
    val context = LocalContext.current
    Onboarding(showKey = keyStep, onShowKey = { show -> if (show && !keyStep) nav.navigate(OnboardingKeyRoute) { launchSingleTop = true } else if (!show && keyStep) nav.up(e) },
        onThirdParty = { nav.finishOnboarding(SectionRoute(SettingsSection.Model)) },
        // Optional 增强能力 step (Shizuku) unless it is already set up.
        onDone = { nav.finishOnboarding(if (shellAccessOf(context).refresh().ready) HomeRoute else EnhanceRoute) })
}

/**
 * A settings page: ViewModels resolve against the settings graph entry, so the pages share one
 * SettingsViewModel / ExtensionsViewModel / AppNotesViewModel like the single settings screen did.
 * The content gets whether this page is on top (sheets show on one page only, also mid-transition).
 */
@Composable
private fun InSettings(nav: NavHostController, e: NavBackStackEntry, content: @Composable (onTop: Boolean) -> Unit) {
    val graph = remember(e) { nav.getBackStackEntry<SettingsGraph>() }
    val top by nav.currentBackStackEntryAsState()
    CompositionLocalProvider(LocalViewModelStoreOwner provides graph) { content(top?.id == e.id) }
}

/** 扩展 sub-pages (plugin, index entry, 管理索引, MCP server). */
@Composable
private fun ExtensionsPage(nav: NavHostController, e: NavBackStackEntry, route: Route) = InSettings(nav, e) { top ->
    SettingsPage(extensionsPageTitle(route), onBack = { nav.up(e) }) {
        ExtensionsSubPage(route, onBack = { nav.up(e) }, toSection = { nav.toSection() }, onTop = top)
    }
}
