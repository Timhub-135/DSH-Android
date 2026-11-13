package io.github.timhub135.dsh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pages never handle Back themselves: Back is the NavHost's popBackStack (BackNavigationTest). A
 * back handler is only allowed for transient layers that are not pages; anything else (a page
 * faked with local state) has to become a destination instead of joining this list.
 */
class BackHandlerAllowlistTest {
    private val allowed = mapOf(
        // The navigation drawer while open; the 对话 / 轨迹 toggle of the same session.
        "feature/conversation/HomeScreen.kt" to 2,
        // In-WebView page history on the Platform screen (the screen itself is a destination).
        "feature/account/PlatformWebScreen.kt" to 1,
    )
    private val pattern = Regex("""\b(BackHandler|PredictiveBackHandler|NavigationEventHandler|OnBackPressedCallback|onBackPressedDispatcher|onBackPressed)\s*[({]""")

    @Test fun backHandlersOnlyInTransientLayers() {
        val root = File("src/main/java/io/github/timhub135/dsh")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val found = root.walk().filter { it.isFile && it.extension == "kt" }.associate { f ->
            f.relativeTo(root).invariantSeparatorsPath to f.readLines().count { l -> !l.trimStart().startsWith("import") && !l.trimStart().startsWith("//") && pattern.containsMatchIn(l) }
        }.filterValues { it > 0 }
        assertEquals("back handlers outside the transient-layer allowlist (make the page a nav destination instead)", allowed, found)
    }
}
