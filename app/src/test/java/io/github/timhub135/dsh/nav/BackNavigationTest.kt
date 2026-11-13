package io.github.timhub135.dsh.nav

import android.app.Application
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import io.github.timhub135.dsh.feature.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * The real page graph (dshGraph) on a TestNavHostController: every page's Back lands on its
 * logical parent, links from outside build the parent stack, and only the roots leave the app.
 * Pages open sub-pages with a plain navigate (as the UI does), so [hierarchy] is the contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
// No Robolectric native runtime (not available on every host, e.g. Linux arm64); nothing here draws or uses SQLite.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class BackNavigationTest {
    private lateinit var nav: TestNavHostController

    @Before fun setUp() {
        nav = TestNavHostController(ApplicationProvider.getApplicationContext())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = HomeRoute) { dshGraph(nav) { error("not composed in tests") } }
    }

    private val routeClasses = listOf(HomeRoute::class, OnboardingRoute::class, OnboardingKeyRoute::class, EnhanceRoute::class, PlatformWebRoute::class,
        AutomationRoute::class, RuleRoute::class, SettingsRoute::class, SectionRoute::class, ProviderRoute::class, ModeRoute::class, PluginRoute::class,
        MarketEntryRoute::class, IndexesRoute::class, McpRoute::class, AppNotesRoute::class, AppNotesGroupRoute::class, NotifAppsRoute::class)

    private fun routeOf(e: NavBackStackEntry): Any = e.toRoute(routeClasses.first { e.destination.hasRoute(it) })
    private fun pages(): List<Any> = nav.currentBackStack.value.filter { it.destination !is NavGraph }.map(::routeOf)
    /** System / predictive Back: what the NavHost's back callback does (only enabled above the root). */
    private fun back(): Boolean = pages().size > 1 && nav.popBackStack()

    private fun section(s: SettingsSection) = SectionRoute(s)
    private val home = listOf(HomeRoute)
    private val settings = home + SettingsRoute

    /** Every page with the path that leads to it (each step a plain navigate from the previous page). */
    private val hierarchy: List<List<Route>> = buildList {
        add(home + AutomationRoute)
        add(home + AutomationRoute + RuleRoute("7"))
        add(settings)
        SettingsSection.entries.forEach { add(settings + section(it)) }
        val model = settings + section(SettingsSection.Model)
        add(model + ProviderRoute("p1"))
        add(model + ProviderRoute())
        add(model + ProviderRoute() + ProviderRoute(preset = "openrouter"))
        val modes = settings + section(SettingsSection.Modes)
        add(modes + ModeRoute("m1")); add(modes + ModeRoute())
        val ext = settings + section(SettingsSection.Extensions)
        add(ext + PluginRoute("a")); add(ext + MarketEntryRoute("https://x/index.json", "b")); add(ext + IndexesRoute)
        add(ext + McpRoute("s1")); add(ext + McpRoute()); add(ext + McpRoute(npm = true))
        val agent = settings + section(SettingsSection.Agent)
        add(agent + AppNotesRoute); add(agent + AppNotesRoute + AppNotesGroupRoute("com.example"))
        add(settings + section(SettingsSection.Phone) + NotifAppsRoute)
    }

    @Test fun startsAtHomeAndHomeIsTheRoot() {
        assertEquals(home, pages())
        assertFalse("Back on home leaves the app", back())
    }

    @Test fun everyPageBacksUpToItsParentThenHome() {
        for (path in hierarchy) {
            nav.toHome()
            path.drop(1).forEach { nav.navigate(it) }
            assertEquals(path, pages())
            // Back (system, predictive or the toolbar arrow: all popBackStack) walks up one level at a time.
            for (n in path.size - 1 downTo 1) { assertTrue(back()); assertEquals(path.take(n), pages()) }
            assertFalse(back())
        }
    }

    /** A conversation link (EXTRA_SESSION: the floating ball, its capsule, a notification, #39) from any page: home as the root. */
    @Test fun conversationLinksLandOnHomeAsTheRoot() {
        for (path in hierarchy) {
            nav.toHome()
            path.drop(1).forEach { nav.navigate(it) }
            nav.toHome()
            assertEquals(home, pages())
            assertFalse("Back on the conversation leaves the app", back())
        }
        // During onboarding the link does not skip it (home is not on the stack yet).
        nav.startOnboarding(); nav.toHome()
        assertEquals(listOf<Any>(OnboardingRoute), pages())
    }

    @Test fun toolbarUpIsBackAndIgnoresStaleEntries() {
        nav.openSettings("Model")
        nav.navigate(ProviderRoute())
        val provider = nav.currentBackStackEntry!!
        nav.up(provider)
        assertEquals(settings + section(SettingsSection.Model), pages())
        // A late 完成 / LaunchedEffect from the page that is already gone must not pop another level.
        nav.up(provider)
        assertEquals(settings + section(SettingsSection.Model), pages())
    }

    @Test fun settingsLinksBuildTheParentStack() {
        for (s in SettingsSection.entries) {
            nav.toHome()
            assertTrue(nav.openSettings(s.name))
            assertEquals(settings + section(s), pages())
            assertTrue(back()); assertEquals(settings, pages())
            assertTrue(back()); assertEquals(home, pages())
        }
        nav.openSettings(null); assertEquals(settings, pages())
        nav.toHome(); nav.openSettings(""); assertEquals(settings, pages())
        nav.toHome(); nav.openSettings("WebSearch"); assertEquals(settings + section(SettingsSection.Agent), pages())
        nav.toHome(); nav.openSettings("phone"); assertEquals(settings + section(SettingsSection.Phone), pages())
    }

    @Test fun aNewLinkReplacesWhateverIsOpenNeverAStaleSection() {
        nav.openSettings("Extensions"); nav.navigate(PluginRoute("a")); nav.navigate(PlatformWebRoute)
        nav.openSettings("Phone")
        assertEquals(settings + section(SettingsSection.Phone), pages())
        nav.openSettings("About")
        assertEquals(settings + section(SettingsSection.About), pages())
        // Already there (e.g. a share while 扩展 is open): the page stays as it is.
        nav.openSettings("Extensions")
        val entry = nav.currentBackStackEntry!!.id
        nav.openSettings("Extensions")
        assertEquals(entry, nav.currentBackStackEntry!!.id)
        assertEquals(settings + section(SettingsSection.Extensions), pages())
    }

    @Test fun onboardingIsTheRootUntilDoneAndIgnoresSettingsLinks() {
        nav.startOnboarding()
        assertEquals(listOf(OnboardingRoute), pages())
        assertFalse(nav.openSettings("Model"))
        assertEquals(listOf(OnboardingRoute), pages())
        nav.navigate(OnboardingKeyRoute) { launchSingleTop = true }
        nav.navigate(OnboardingKeyRoute) { launchSingleTop = true }
        assertEquals(listOf(OnboardingRoute, OnboardingKeyRoute), pages())
        assertTrue("Back on the API key step returns to the choice", back())
        assertEquals(listOf(OnboardingRoute), pages())
        assertFalse("Back on the first onboarding screen leaves the app", back())
    }

    @Test fun finishingOnboardingLeavesHomeUnderTheNextStep() {
        nav.startOnboarding(); nav.navigate(OnboardingKeyRoute)
        nav.finishOnboarding(EnhanceRoute)
        assertEquals(home + EnhanceRoute, pages())
        nav.finishOnboarding(EnhanceRoute) // a second 完成 (signed-in effect) is a no-op
        assertEquals(home + EnhanceRoute, pages())
        assertTrue(back()); assertEquals(home, pages())

        nav.startOnboarding(); nav.finishOnboarding(SectionRoute(SettingsSection.Model))
        assertEquals(settings + section(SettingsSection.Model), pages())

        nav.toHome(); nav.startOnboarding(); nav.finishOnboarding()
        assertEquals(home, pages())
    }

    @Test fun inPageReturns() {
        // New provider: picker → editor; 完成 / 取消 return to 模型服务, not the picker.
        nav.openSettings("Model"); nav.navigate(ProviderRoute()); nav.navigate(ProviderRoute(preset = "openrouter"))
        nav.toSection()
        assertEquals(settings + section(SettingsSection.Model), pages())
        // 复制 replaces the mode page.
        nav.openSettings("Modes"); nav.navigate(ModeRoute("a")); nav.replaceMode("b")
        assertEquals(settings + section(SettingsSection.Modes) + ModeRoute("b"), pages())
        // A rule's session / 新建: back to the conversation.
        nav.toHome(); nav.navigate(AutomationRoute); nav.navigate(RuleRoute("1")); nav.toHome()
        assertEquals(home, pages())
    }

    @Test fun platformPageSitsOnTopOfTheCurrentPage() {
        nav.openSettings("Account"); nav.navigate(PlatformWebRoute) { launchSingleTop = true }
        nav.navigate(PlatformWebRoute) { launchSingleTop = true }
        assertEquals(settings + section(SettingsSection.Account) + PlatformWebRoute, pages())
        assertTrue(back()); assertEquals(settings + section(SettingsSection.Account), pages())
    }
}
