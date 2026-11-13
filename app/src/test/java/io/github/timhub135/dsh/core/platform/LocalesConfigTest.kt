package io.github.timhub135.dsh.core.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #40: android:localeConfig lists exactly the languages the app ships (values/ = zh, the default, plus
 * every values-xx with strings), so adding a language is values-xx/strings*.xml + one line there.
 */
class LocalesConfigTest {
    private val res = File("src/main/res")

    @Test fun localesConfigMatchesTheTranslatedResourceDirs() {
        val config = File(res, "xml/locales_config.xml").readText()
        val listed = Regex("""<locale\s+android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        val shipped = res.listFiles().orEmpty().filter { it.isDirectory && File(it, "strings.xml").exists() }.map { d ->
            // values → the default language (zh); values-en / values-b+zh+Hant → their language tag.
            d.name.removePrefix("values").removePrefix("-").ifEmpty { "zh" }.removePrefix("b+").replace('+', '-').replace("-r", "-")
        }
        assertEquals(shipped.sorted(), listed.sorted())
        assertEquals("the default values/ language comes first", "zh", listed.first())
    }
}
