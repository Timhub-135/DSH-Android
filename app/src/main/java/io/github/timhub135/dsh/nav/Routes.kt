package io.github.timhub135.dsh.nav

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import io.github.timhub135.dsh.feature.settings.SettingsSection
import kotlinx.serialization.Serializable

/*
 * The page hierarchy lives in one place: the Navigation back stack (Android "Principles of
 * navigation"). Home is the fixed start destination; every full-screen page (own title + back
 * arrow) is a type-safe route; a page opens its sub-pages with a plain navigate, and its toolbar
 * arrow is the same popBackStack system / predictive Back does. Links from outside the page tree
 * (intents, the drawer, error rows, the sign-in dialog) go through [openSettings], which builds
 * the same stack navigating there by hand would have: home → 设置 → section.
 */

/** A page of the app (a NavHost destination); feature screens get `open: (Route) -> Unit` to go one level deeper. */
sealed interface Route

@Serializable data object HomeRoute : Route

/** First run without a credential (pushed over home, which it replaces as the root). */
@Serializable data object OnboardingRoute : Route
/** Onboarding's 使用 API Key step (the key card expanded). */
@Serializable data object OnboardingKeyRoute : Route

/** Platform pages (sign-in, 充值, 用量) in the in-app WebView, over the current page while AccountRepository.page is set. */
@Serializable data object PlatformWebRoute : Route

@Serializable data object AutomationRoute : Route
/** A rule; id as a string (AutomationViewModel reads it from the SavedStateHandle). */
@Serializable data class RuleRoute(val id: String) : Route

/** The settings graph: its entry scopes the ViewModels the settings pages share. */
@Serializable data object SettingsGraph

/** The settings section index (the settings graph's start). */
@Serializable data object SettingsRoute : Route
/** One section (a [SettingsSection] name). */
@Serializable data class SectionRoute(val section: String) : Route {
    constructor(s: SettingsSection) : this(s.name)
}
/** 模型服务 → an existing provider ([id]), the preset picker (both null), or a new provider from [preset] (opened from the picker). */
@Serializable data class ProviderRoute(val id: String? = null, val preset: String? = null) : Route
/** 会话模式 → a mode, or a new one (null). */
@Serializable data class ModeRoute(val id: String? = null) : Route
/** 扩展 → an installed plugin. */
@Serializable data class PluginRoute(val name: String) : Route
/** 扩展 → a plugin from an index (not installed). */
@Serializable data class MarketEntryRoute(val url: String, val name: String) : Route
/** 扩展 → 管理索引. */
@Serializable data object IndexesRoute : Route
/** 扩展 → MCP editor: a server ([id]) or a new one (remote, or npm with [npm]). */
@Serializable data class McpRoute(val id: String? = null, val npm: Boolean = false) : Route
/** 能力 → App 经验. */
@Serializable data object AppNotesRoute : Route
/** App 经验 → one app's notes. */
@Serializable data class AppNotesGroupRoute(val pkg: String) : Route
/** 手机权限 → 通知读取范围. */
@Serializable data object NotifAppsRoute : Route

/** 设置 at [section] (a SettingsSection name; blank / unknown = the index). */
fun settingsRoute(section: String?): Route = SettingsSection.of(section)?.let { SectionRoute(it) } ?: SettingsRoute

private fun NavController.has(entry: (NavBackStackEntry) -> Boolean) = currentBackStack.value.any(entry)

fun NavController.homeOnStack() = has { it.destination.hasRoute(HomeRoute::class) }
fun NavController.onboardingOnStack() = has { it.destination.hasRoute(OnboardingRoute::class) }

/**
 * Opens 设置 at [section] from anywhere (EXTRA_SECTION intents, share → 扩展, the drawer, error
 * rows, the sign-in dialog) with the stack manual navigation builds: home → 设置 → section. Whatever
 * was above home is replaced (so no stale section survives a new link); already there, nothing
 * changes. false during onboarding (home is not on the stack yet): the link is ignored, as before.
 */
fun NavController.openSettings(section: String?): Boolean {
    if (!homeOnStack()) return false
    val target = settingsRoute(section)
    val top = currentBackStackEntry
    val here = when (target) {
        is SectionRoute -> top?.destination?.hasRoute(SectionRoute::class) == true && top.toRoute<SectionRoute>() == target
        else -> top?.destination?.hasRoute(SettingsRoute::class) == true
    }
    if (here) return true
    popBackStack(HomeRoute, inclusive = false)
    navigate(SettingsRoute)
    if (target != SettingsRoute) navigate(target)
    return true
}

/** Back to the conversation (a rule's run, 新建 from automation, a session link); a no-op without home. */
fun NavController.toHome() { popBackStack(HomeRoute, inclusive = false) }

/** Back to the settings section a sub-page belongs to (provider 完成 from picker → editor, the install sheet's MCP button). */
fun NavController.toSection() { popBackStack<SectionRoute>(inclusive = false) }

/** 复制 on a mode page: the copy replaces it (Back still lands on 会话模式). */
fun NavController.replaceMode(id: String) = navigate(ModeRoute(id)) { popUpTo<ModeRoute> { inclusive = true } }

/** Up one level from [entry]: the toolbar arrow and in-page 完成 / 取消; a no-op once [entry] is no longer on top. */
fun NavController.up(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** First run: onboarding replaces home as the root (Back there leaves the app); home stays the graph's start. */
fun NavController.startOnboarding() = navigate(OnboardingRoute) { popUpTo(HomeRoute) { inclusive = true } }

/** Leaves onboarding for home, then 设置 → 模型服务 over it. No-op once onboarding is gone. */
fun NavController.finishOnboarding(then: Route = HomeRoute) {
    if (!onboardingOnStack()) return
    navigate(HomeRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
    when (then) {
        is SectionRoute -> openSettings(then.section)
        else -> {}
    }
}
