package io.github.timhub135.dsh.core.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceTest {
    private fun c(s: String) = InstallSource.classify(s)

    @Test fun `npm names and ranges pass through unchanged`() {
        assertEquals(InstallInput(SourceKind.Npm, "ds-bot", "ds-bot", null), c("ds-bot"))
        assertEquals(InstallInput(SourceKind.Npm, "ds-bot@0.2.4", "ds-bot", "0.2.4"), c("ds-bot@0.2.4"))
        assertEquals(InstallInput(SourceKind.Npm, "@scope/name@^1", "@scope/name", "^1"), c("  @scope/name@^1 "))
        assertEquals("@fffangchenghao/dsh-web-search", c("@fffangchenghao/dsh-web-search").name)
        assertEquals(SourceKind.Unknown, c("Not A Package").kind)
        assertEquals(SourceKind.Unknown, c("UPPER").kind)
    }

    @Test fun `npm and npmmirror package pages become name at version`() {
        assertEquals("ds-bot", c("https://www.npmjs.com/package/ds-bot").spec)
        assertEquals("ds-bot@0.2.4", c("https://www.npmjs.com/package/ds-bot/v/0.2.4").spec)
        assertEquals("@scope/pkg", c("https://www.npmjs.com/package/@scope/pkg").spec)
        assertEquals("@scope/pkg@1.0.0", c("https://npmjs.com/package/%40scope%2Fpkg/v/1.0.0").let { it.spec })
        assertEquals("dsh-plugin-guide", c("https://npmmirror.com/package/dsh-plugin-guide").spec)
        assertEquals("dsh-plugin-guide", c("npmmirror.com/package/dsh-plugin-guide").spec)
        assertEquals(SourceKind.Npm, c("https://www.npmjs.com/package/ds-bot?activeTab=readme").kind)
    }

    @Test fun `github links are canonicalized for the engine to resolve`() {
        assertEquals(InstallInput(SourceKind.Github, "https://github.com/Fei2411/DS-Bot"), c("https://github.com/Fei2411/DS-Bot"))
        assertEquals("https://github.com/o/r", c("https://github.com/o/r.git").spec)
        assertEquals("https://github.com/o/r", c("https://github.com/o/r/").spec)
        assertEquals("https://github.com/o/r/tree/main/packages/x", c("https://github.com/o/r/tree/main/packages/x").spec)
        assertEquals("https://github.com/o/r", c("https://github.com/o/r/issues/3").spec)
        assertEquals("https://github.com/o/r", c("github:o/r").spec)
        assertEquals("https://github.com/o/r", c("github.com/o/r").spec)
        assertEquals(SourceKind.Unknown, c("https://github.com/onlyowner").kind)
    }

    @Test fun `tarballs zips and local files`() {
        assertEquals(SourceKind.Tarball, c("https://example.com/p/plugin-1.0.0.tgz").kind)
        assertEquals(SourceKind.Tarball, c("https://github.com/o/r/releases/download/v1/p.tgz").kind)
        assertEquals(SourceKind.Tarball, c("https://example.com/p.tar.gz").kind)
        assertEquals(SourceKind.Zip, c("https://example.com/p.zip").kind)
        assertEquals(InstallInput(SourceKind.File, "/data/user/0/x/cache/ext-staging/p.tgz"), c("/data/user/0/x/cache/ext-staging/p.tgz"))
        assertEquals(SourceKind.File, c("file:///data/x/p.zip").kind)
        assertEquals("/data/x/p.zip", c("file:///data/x/p.zip").spec)
        assertEquals(SourceKind.Unknown, c("/data/x/readme.txt").kind)
        assertEquals(SourceKind.Unknown, c("https://example.com/page").kind)
    }

    @Test fun `git transports are refused before any network`() {
        for (s in listOf("git+ssh://git@github.com/o/r.git", "git@github.com:o/r.git", "git://github.com/o/r", "git+https://github.com/o/r.git", "ssh://git@host/r")) {
            val r = c(s)
            assertEquals(s, SourceKind.Rejected, r.kind)
            assertEquals("needs-git", r.code)
            assertFalse(r.installable)
        }
    }

    @Test fun `pasted install commands are unwrapped`() {
        assertEquals("ds-bot", c("npm i ds-bot").spec)
        assertEquals("ds-bot@1", c("pnpm add ds-bot@1").spec)
        assertEquals("@a/b", c("npx -y @a/b").spec)
        assertEquals("foo", c("dsh plugin --profile web add foo").spec)
        assertEquals("foo", c("npm install --save-exact foo").spec)
        assertEquals("ds-bot", c("`ds-bot`").spec)
    }

    @Test fun `mcp json goes to the MCP tab`() {
        assertEquals(SourceKind.McpJson, c("{\"mcpServers\":{}}").kind)
        assertFalse(c("{}").installable)
    }

    @Test fun `search box offers direct install only for things that look like packages or links`() {
        assertNull(InstallSource.looksInstallable("search"))
        assertNull(InstallSource.looksInstallable("网页 搜索"))
        assertEquals("ds-bot", InstallSource.looksInstallable("ds-bot")?.spec)
        assertEquals("@a/b", InstallSource.looksInstallable("@a/b")?.spec)
        assertEquals(SourceKind.Github, InstallSource.looksInstallable("https://github.com/o/r")?.kind)
        assertEquals(SourceKind.Rejected, InstallSource.looksInstallable("git@github.com:o/r.git")?.kind)
        assertTrue(InstallSource.looksInstallable("npm i foo") != null)
    }
}
