package io.github.timhub135.dsh.core.platform

import android.app.Application
import android.app.LocaleManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import io.github.timhub135.dsh.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** #40: the in-app language picker (设置 → 通用 → 语言). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class AppLanguageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After fun tearDown() { AppLanguage.store(app, null) }

    @Test fun pickerListsTheShippedLanguagesFromLocalesConfig() {
        val langs = AppLanguage.supported(app)
        assertEquals(listOf("zh", "en", "ru"), langs)
        assertEquals(listOf("中文", "English", "Русский"), langs.map(AppLanguage::displayName))
    }

    @Test fun selectionFollowsTheStoredTag() {
        val langs = listOf("zh", "en")
        assertEquals(0, AppLanguage.selectedIndex(null, langs))
        assertEquals(1, AppLanguage.selectedIndex("zh", langs))
        // The system's per-app picker may store a regional tag.
        assertEquals(1, AppLanguage.selectedIndex("zh-Hans-CN", langs))
        assertEquals(2, AppLanguage.selectedIndex("en-US", langs))
        // Not shipped (any more): shown as 跟随系统.
        assertEquals(0, AppLanguage.selectedIndex("ru", langs))
    }

    @Test fun android13UsesTheFrameworkLocaleManager() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        AppLanguage.set(activity, "en")
        assertEquals("en", app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        assertEquals("en", AppLanguage.current(app))
        AppLanguage.set(activity, null)
        assertNull(AppLanguage.current(app))
        // Nothing of our own is stored: the system keeps the choice (and shows it in its settings).
        assertNull(AppLanguage.stored(app))
    }

    @Test fun android13AdoptsAChoiceMadeBeforeAnOsUpgradeOnce() {
        AppLanguage.store(app, "en")
        AppLanguage.applyToProcess(app)
        assertEquals("en", AppLanguage.current(app))
        assertNull(AppLanguage.stored(app))
    }

    @Test fun beforeAndroid13ActivitiesGetTheStoredLanguage() {
        // Following the system: the context is left alone.
        assertSame(app, AppLanguage.legacyWrap(app))
        AppLanguage.store(app, "en")
        assertEquals("Language", AppLanguage.legacyWrap(app).getString(R.string.set_language))
        assertEquals("en", java.util.Locale.getDefault().language)
        AppLanguage.store(app, "zh")
        assertEquals("语言", AppLanguage.legacyWrap(app).getString(R.string.set_language))
        assertEquals("zh", java.util.Locale.getDefault().language)
    }
}
