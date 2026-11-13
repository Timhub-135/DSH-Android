package io.github.timhub135.dsh.core.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import io.github.timhub135.dsh.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Per-app language (#40): 设置 → 通用 → 语言 (跟随系统 / one of the shipped languages).
 *
 * The shipped languages are res/xml/locales_config.xml (android:localeConfig), the same list
 * Android 13+ shows under system Settings → Apps → DSH → Language; adding a language is a
 * values-xx/strings.xml plus one line there. Android 13+: the framework LocaleManager stores the
 * choice and recreates the activities. Android 10–12 (minSdk 29) have no per-app language: the
 * choice is stored here (what AppCompat's autoStoreLocales does) and applied to every activity
 * ([wrap]) and to the process ([applyToProcess]: services, notifications, Locale.getDefault(),
 * which the engine gets as DSH_ANDROID_LOCALE).
 */
object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** Language tags the app ships, in locales_config order (the first is the default `values/`). */
    fun supported(context: Context): List<String> {
        val out = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "locale")
                    p.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let(out::add)
            }
        }
        return out
    }

    /** A language's own name for itself (中文, English), for the picker. */
    fun displayName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
    }

    /**
     * The picker's selection: 0 = 跟随系统, else 1 + the index in [supported] of [current]'s language
     * (the system settings may store a regional tag such as zh-CN for the shipped zh).
     */
    fun selectedIndex(current: String?, supported: List<String>): Int {
        val lang = current?.let { Locale.forLanguageTag(it).language } ?: return 0
        return supported.indexOfFirst { Locale.forLanguageTag(it).language == lang } + 1
    }

    /** The chosen language tag; null = follow the system. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        else stored(context)

    /**
     * Sets the app language ([tag] null = follow the system). Android 13+ recreates the activities
     * itself; before that the process is updated and [activity] recreated here (others pick it up when they start).
     */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        store(activity, tag)
        applyToProcess(activity.applicationContext)
        activity.recreate()
    }

    /** Activities' attachBaseContext (Android 10–12): [base] with the chosen language; unchanged when following the system. */
    fun wrap(base: Context): Context = if (Build.VERSION.SDK_INT >= 33) base else legacyWrap(base)

    /** [wrap] before Android 13 (any API level, for tests). Shipped as APKs, so every language's resources are present (no bundle splits). */
    @SuppressLint("AppBundleLocaleChanges")
    internal fun legacyWrap(base: Context): Context {
        val locale = stored(base)?.let(Locale::forLanguageTag) ?: return base
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(cfg)
    }

    /**
     * Android 10–12: the chosen language on the process's shared resources (services, notifications)
     * and as Locale.getDefault(). The system resets both on a configuration change, so the
     * Application calls this again from onConfigurationChanged. Android 13+: carries a choice made
     * before an OS upgrade over to LocaleManager (once).
     */
    @SuppressLint("AppBundleLocaleChanges")
    fun applyToProcess(app: Context) {
        val tag = stored(app)
        if (Build.VERSION.SDK_INT >= 33) {
            if (tag != null) {
                app.getSystemService(LocaleManager::class.java)?.let { lm -> if (lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(tag) }
                store(app, null)
            }
            return
        }
        val locale = tag?.let(Locale::forLanguageTag) ?: android.content.res.Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        val res = app.resources
        if (res.configuration.locales[0] == locale) return
        val cfg = Configuration(res.configuration).apply { setLocales(LocaleList(locale)) }
        @Suppress("DEPRECATION") res.updateConfiguration(cfg, res.displayMetrics)
    }

    internal fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }

    internal fun store(context: Context, tag: String?) {
        // commit: the very next attachBaseContext (the recreate) reads it.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag) }
    }
}
