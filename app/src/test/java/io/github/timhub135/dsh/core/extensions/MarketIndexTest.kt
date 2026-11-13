package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.RpcJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarketIndexTest {
    private val url = "https://market.example/dsh/index.json"

    /** The curated index this repository publishes (served through jsDelivr, §9.5). */
    @Test fun `the curated index in the repository is a valid format 1 index`() {
        val file = listOf(File("../plugins/index.json"), File("plugins/index.json")).first { it.isFile }
        val doc = MarketIndex.parse(file.readText(), MarketIndex.CURATED_URL)
        assertEquals("DSH-Android 精选", doc.title.get("zh-CN"))
        assertTrue(doc.plugins.isNotEmpty())
        doc.plugins.forEach { p ->
            val v = p.installVersion
            assertTrue("${p.name} has an installable latest", v != null)
            assertTrue("${p.name} declares engines.dsh", v!!.engines["dsh"]!!.isNotBlank())
            assertEquals("npm", v.source?.type)
            assertEquals("${p.name}@${v.version}", MarketIndex.installSpec(p.name, v))
        }
    }

    private fun doc(plugins: String, metadata: String = "{}") = """{"format":1,"title":{"en":"M","zh":"市场"},"metadata":$metadata,"plugins":[$plugins]}"""
    private fun plugin(name: String = "dsh-x", extra: String = "", version: String = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"npm"},"engines":{"dsh":"*"}}""") =
        """{"name":"$name"$extra,"versions":[$version]}"""

    @Test fun `format 1 example parses with tags, metadata and resolved icons`() {
        val d = MarketIndex.parse(doc(
            plugin(extra = ""","title":{"en":"X","zh":"某"},"icon":"icons/x.svg","metadata":{"category":"c","labels":["l"],"sortKeys":{"downloads":3}},"dist-tags":{"latest":"1.0.0"}"""),
            """{"categories":{"c":{"title":"C"}},"labels":{"l":{"title":{"en":"L"}}},"sortKeys":{"downloads":{"title":"D","order":"desc"}}}"""), url)
        val p = d.plugins.single()
        assertEquals("某", p.title("zh"))
        assertEquals("X", p.title("fr"))
        assertEquals("https://market.example/dsh/icons/x.svg", p.icon)
        assertEquals("1.0.0", p.installVersion?.version)
        assertEquals(3.0, p.metadata.sortKeys["downloads"])
    }

    @Test fun `reader rules reject the whole index with a path`() {
        fun bad(text: String, path: String) {
            val e = assertThrows(MarketIndex.InvalidIndex::class.java) { MarketIndex.parse(text, url) }
            assertTrue("${e.message} names $path", e.path.startsWith(path))
        }
        bad("""{"format":2,"title":"M","plugins":[]}""", "$.format")
        bad("""{"format":1,"plugins":[]}""", "$.title")
        bad("""{"format":1,"title":{"zh":"仅中文"},"plugins":[]}""", "$.title")
        bad(doc(plugin("Bad Name")), "$.plugins[0].name")
        bad(doc(plugin() + "," + plugin()), "$.plugins[1].name")
        bad(doc(plugin(extra = ""","dist-tags":{"latest":"9.9.9"}""")), "$.plugins[0].dist-tags")
        bad(doc(plugin(extra = ""","metadata":{"category":"nope"}""")), "$.plugins[0].metadata.category")
        bad(doc(plugin(version = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"npm"},"engines":{}}""")), "$.plugins[0].versions[0].engines.dsh")
        bad(doc(plugin(version = """{"version":"latest","publishTimestamp":1,"source":{"type":"npm"},"engines":{"dsh":"*"}}""")), "$.plugins[0].versions[0].version")
        bad(doc(plugin(version = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"svn"},"engines":{"dsh":"*"}}""")), "$.plugins[0].versions[0].source.type")
        bad(doc(plugin(version = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"git","url":"https://github.com/o/r","commit":"abc"},"engines":{"dsh":"*"}}""")), "$.plugins[0].versions[0].source.commit")
        bad(doc(plugin(extra = ""","icon":"http://other.example/x.png"""")), "$.plugins[0].icon")
        bad(doc(plugin(), metadata = "[]"), "$.metadata")
    }

    @Test fun `git versions install by pinned commit`() {
        val v = MarketVersion("1.0.0", 1, MarketVersionSource("git", url = "https://github.com/o/r", commit = "3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4", path = "packages/foo"))
        assertEquals("git+https://github.com/o/r.git#3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4&path:/packages/foo", MarketIndex.installSpec("foo", v))
        assertEquals("x@2", MarketIndex.installSpec("foo", v.copy(install = MarketInstallTarget("x@2"))))
    }

    @Test fun `local search matches words and Chinese pieces`() {
        val hay = listOf("dsh-web-search", "fangchenghao", "免密钥的网页搜索与网页抓取")
        assertTrue(MarketIndex.matches("web search", hay))
        assertTrue(MarketIndex.matches("网页搜索", hay))
        assertTrue(MarketIndex.matches("搜索 网页", hay))
        assertTrue(MarketIndex.matches("网页抓取", hay))
        assertFalse(MarketIndex.matches("ollama", hay))
        assertTrue(MarketIndex.matches("  ", hay))
    }

    @Test fun `engine search results decode with listing fields`() {
        val r = RpcJson.decodeFromString(MarketSearchResult.serializer(), """
            {"url":"$url","title":"M","metadata":{},"fetchedAt":1,"total":1,"nextOffset":null,"entries":[
              {"name":"a","title":"A","dist-tags":{"latest":"1.0.0"},"defaultVersion":"1.0.0","installation":{"status":"update-available","installedVersion":"0.9.0"},
               "versions":[{"version":"1.0.0","publishTimestamp":1,"source":{"type":"npm"},"engines":{"dsh":">=0.3"},
                 "install":{"spec":"a@1.0.0"},"compatibility":{"compatible":false,"runtimeVersion":"0.2.1","requirements":{"engines.dsh":">=0.3"},"exempted":false}}]}]}
        """)
        val e = r.entries.single()
        assertTrue(e.installed)
        assertEquals(false, e.installVersion?.compatibility?.compatible)
        assertEquals("a@1.0.0", MarketIndex.installSpec(e.name, e.installVersion!!))
    }
}
