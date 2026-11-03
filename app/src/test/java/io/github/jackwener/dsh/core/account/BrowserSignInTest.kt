package io.github.jackwener.dsh.core.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.jackwener.dsh.core.engine.EngineManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** #49: where the authorization page opens (in-app screen / browser) and the browser's return through the app link. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class BrowserSignInTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = AccountRepository(app, dagger.Lazy<EngineManager> { error("no engine in this test") })
    private val authorize = "https://platform.deepseek.com/oauth/authorize?state=s1"

    private fun view(phase: String, status: String = "signed-out") = Json.parseToJsonElement(
        """{"status":"$status","links":{},"attempt":{"id":"a1","phase":"$phase","authorizeUrl":"$authorize"}}""")

    @Test fun `in-app screen by default, closed by the completion link`() {
        repo.onEngineView(view("waiting-browser"))
        repo.openSignInPage()
        val page = repo.page.value!!
        assertTrue(page.signIn)
        assertTrue(page.url.startsWith(authorize) && "theme=light" in page.url)
        repo.onSignInReturned()
        assertNull(repo.page.value)
    }

    @Test fun `browser mode sends the page to the browser and the app link returns`() = runBlocking {
        repo.onEngineView(view("waiting-browser"))
        repo.useBrowser = true
        repo.openSignInPage()
        assertNull(repo.page.value)
        val url = withTimeout(1000) { repo.browserRequests.first() }
        assertTrue(url.startsWith(authorize) && "theme=" in url)
        // dsh-android://account/login?result=ok via MainActivity: nothing to close, state already known.
        repo.onSignInReturned()
        assertNull(repo.page.value)
        // The engine's stream settles the attempt (here: given up in the browser).
        repo.onEngineView(view("cancelled"))
        assertFalse(repo.state.value.active)
        assertNull(repo.page.value)
    }

    @Test fun `no browser falls back to the in-app screen`() {
        repo.onEngineView(view("waiting-browser"))
        repo.useBrowser = true
        repo.onBrowserUnavailable(authorize)
        assertFalse(repo.useBrowser)
        assertEquals(authorize, repo.page.value?.url)
    }
}
