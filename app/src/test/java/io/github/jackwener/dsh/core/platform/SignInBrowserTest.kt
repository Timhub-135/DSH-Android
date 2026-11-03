package io.github.jackwener.dsh.core.platform

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** #49 用浏览器登录: the authorization page goes to a Custom Tab when the browser offers one, else a plain VIEW. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class SignInBrowserTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val url = "https://platform.deepseek.com/oauth/authorize?state=s&theme=dark"

    private fun installBrowser(pkg: String, customTabs: Boolean) {
        val pm = shadowOf(app.packageManager)
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main" } })
        if (customTabs) pm.addResolveInfoForIntent(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(pkg),
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; name = "$pkg.Tabs" } })
    }

    @Test fun `custom tab of the default browser, on top of the activity's task`() {
        installBrowser("com.android.chrome", customTabs = true)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val i = Browser.signInIntent(activity, url, dark = true, toolbar = 0xFF000000.toInt())
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(url, i.dataString)
        assertEquals("com.android.chrome", i.`package`)
        assertTrue(i.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(CustomTabsIntent.COLOR_SCHEME_DARK, i.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1))
        assertEquals(0, i.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun `plain browser view when no custom tabs provider`() {
        installBrowser("org.example.browser", customTabs = false)
        val i = Browser.signInIntent(app, url, dark = false, toolbar = 0)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(url, i.dataString)
        assertNull(i.`package`)
        assertFalse(i.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        // Not an activity: its own task.
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
