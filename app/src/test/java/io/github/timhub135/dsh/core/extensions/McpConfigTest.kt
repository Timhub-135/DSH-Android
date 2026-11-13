package io.github.timhub135.dsh.core.extensions

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class McpConfigTest {
    @Test fun `pasted mcpServers JSON is classified per entry`() {
        val list = McpImport.parse("""
        {"mcpServers": {
          "remote": {"url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer t"}},
          "typed": {"type": "http", "url": "https://x.example/mcp"},
          "sse": {"type": "sse", "url": "https://x.example/sse"},
          "sse2": {"url": "https://x.example/sse/"},
          "everything": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-everything", "--flag"], "env": {"A": "1"}},
          "dlx": {"command": "pnpm", "args": ["dlx", "some-mcp"]},
          "py": {"command": "uvx", "args": ["mcp-server-fetch"]},
          "docker": {"command": "docker", "args": ["run", "-i", "x"]},
          "local": {"command": "node", "args": ["/Users/me/server.js"]},
          "bin": {"command": "/usr/local/bin/thing"}
        }}""").associateBy { it.name }
        assertEquals(McpCandidate.Remote("remote", "https://mcp.example.com/mcp", mapOf("Authorization" to "Bearer t")), list["remote"])
        assertTrue(list["typed"] is McpCandidate.Remote)
        assertEquals("mcp-sse-only", (list["sse"] as McpCandidate.Unsupported).code)
        assertEquals("mcp-sse-only", (list["sse2"] as McpCandidate.Unsupported).code)
        assertEquals(McpCandidate.Npm("everything", "@modelcontextprotocol/server-everything", listOf("--flag"), mapOf("A" to "1")), list["everything"])
        assertEquals("some-mcp", (list["dlx"] as McpCandidate.Npm).pkg)
        assertEquals(McpCandidate.Unsupported("py", "mcp-runtime", "Python"), list["py"])
        assertEquals("Docker", (list["docker"] as McpCandidate.Unsupported).runtime)
        assertEquals("mcp-local-path", (list["local"] as McpCandidate.Unsupported).code)
        assertEquals("/usr/local/bin/thing", (list["bin"] as McpCandidate.Unsupported).runtime)
    }

    @Test fun `a single server object or a bare map also works, non-JSON fails`() {
        assertEquals(1, McpImport.parse("""{"url":"https://a.example/mcp"}""").size)
        assertEquals(listOf("a", "b"), McpImport.parse("""{"a":{"url":"https://a/mcp"},"b":{"command":"npx","args":["x"]}}""").map { it.name })
        assertThrows(IllegalArgumentException::class.java) { McpImport.parse("not json") }
    }

    private val remote = McpServer("github", "GitHub", McpServer.KIND_REMOTE, url = "https://api.example.com/mcp", headerNames = listOf("Authorization", "X-Team"))
    private val npm = McpServer("everything", kind = McpServer.KIND_NPM, `package` = "@modelcontextprotocol/server-everything",
        bin = "@modelcontextprotocol/server-everything/dist/index.js", args = listOf("stdio"))

    @Test fun `overlay rows render remote and stdio servers, secrets only as env references`() {
        val yaml = McpRows.render(listOf(remote, npm, remote.copy(id = "off", enabled = false)), "/data/dsh-home/mcp-servers")
        val expected = """
            - insert:
                - id: "mcp-github"
                  name: "@deepseek-ai/dsh-mcp-client"
                  config:
                    serverName: "github"
                    transport: streamable-http
                    url: "https://api.example.com/mcp"
                    headers:
                      "Authorization": !!js process.env.DSH_MCP_GITHUB_AUTHORIZATION
                      "X-Team": !!js process.env.DSH_MCP_GITHUB_X_TEAM
                    failOnStartupError: false
                - id: "mcp-everything"
                  name: "@deepseek-ai/dsh-mcp-client"
                  config:
                    serverName: "everything"
                    transport: stdio
                    command: !!js "process.env.DSH_NODE_BIN || (process.env.DSH_NATIVE_DIR + '/libdshnode.so')"
                    args:
                      - "--require"
                      - !!js "process.env.DSH_ANDROID_ENV_CJS || process.argv[1].replace(/[^/]*${'$'}/, 'android-env.cjs')"
                      - "/data/dsh-home/mcp-servers/node_modules/@modelcontextprotocol/server-everything/dist/index.js"
                      - "stdio"
                    failOnStartupError: false
        """.trimIndent() + "\n"
        assertEquals(expected, yaml)
        assertEquals("", McpRows.render(emptyList(), "/x"))
    }

    @Test fun `local servers beyond the limit are left out`() {
        val many = (1..6).map { npm.copy(id = "s$it") }
        val yaml = McpRows.render(many, "/r")
        assertEquals(McpServer.MAX_LOCAL_ENABLED, Regex("transport: stdio").findAll(yaml).count())
    }

    @Test fun `header secrets map to env names, test config carries real values`() {
        val env = McpRows.secretEnv(listOf(remote, npm)) { name -> if (name == "mcp:github:Authorization") "Bearer abc" else null }
        assertEquals(mapOf("DSH_MCP_GITHUB_AUTHORIZATION" to "Bearer abc"), env)
        val cfg = McpRows.testConfig(remote) { h -> "v-$h" }
        assertEquals(JsonPrimitive("remote"), cfg["kind"])
        assertEquals("""{"Authorization":"v-Authorization","X-Team":"v-X-Team"}""", cfg["headers"].toString())
        assertEquals(JsonPrimitive("@modelcontextprotocol/server-everything/dist/index.js"), McpRows.testConfig(npm) { null }["bin"])
    }

    @Test fun `rows join the generated layer, including an empty one`() {
        val rows = "- insert:\n    - id: x\n"
        assertEquals("# Generated\n- insert:\n    - id: x\n", appendPatchRows("# Generated\n[]\n", rows))
        assertEquals("# G\n- id: tool-web\n  config: {}\n- insert:\n    - id: x\n", appendPatchRows("# G\n- id: tool-web\n  config: {}\n", rows))
        assertEquals("# G\n[]\n", appendPatchRows("# G\n[]\n", ""))
    }

    @Test fun `ids are derived from names and stay unique`() {
        assertEquals("github", McpServer.newId("GitHub", emptyList()))
        assertEquals("github_2", McpServer.newId("GitHub", listOf("github")))
        assertEquals("my_server", McpServer.newId("My Server!", emptyList()))
        assertEquals("server", McpServer.newId("中文", emptyList()))
    }

    @Test fun `registry plan by region and mode`() {
        val cn = Locale.SIMPLIFIED_CHINESE
        assertEquals(listOf(PluginRegistries.NPMMIRROR, PluginRegistries.NPMJS), PluginRegistries.plan(RegistryMode.Auto, "", cn, "UTC"))
        assertEquals(listOf(PluginRegistries.NPMMIRROR, PluginRegistries.NPMJS), PluginRegistries.plan(RegistryMode.Auto, "", Locale.US, "Asia/Shanghai"))
        assertEquals(listOf(PluginRegistries.NPMJS, PluginRegistries.NPMMIRROR), PluginRegistries.plan(RegistryMode.Auto, "", Locale.US, "Europe/Berlin"))
        assertEquals(listOf(PluginRegistries.NPMJS, PluginRegistries.NPMMIRROR), PluginRegistries.plan(RegistryMode.Auto, "", Locale.TRADITIONAL_CHINESE, "Asia/Taipei"))
        assertEquals(listOf(PluginRegistries.NPMJS, PluginRegistries.NPMMIRROR), PluginRegistries.plan(RegistryMode.Npmjs, "", cn, "Asia/Shanghai"))
        assertEquals(listOf("https://npm.corp.example/"), PluginRegistries.plan(RegistryMode.Custom, " https://npm.corp.example ", cn, "UTC"))
        // An unusable custom URL falls back to automatic.
        assertEquals(PluginRegistries.plan(RegistryMode.Auto, "", cn, "UTC"), PluginRegistries.plan(RegistryMode.Custom, "ftp://x", cn, "UTC"))
        assertEquals(mapOf("DSH_PLUGIN_REGISTRY" to PluginRegistries.NPMJS, "DSH_PLUGIN_FALLBACK_REGISTRIES" to PluginRegistries.NPMMIRROR),
            PluginRegistries.env(listOf(PluginRegistries.NPMJS, PluginRegistries.NPMMIRROR)))
        assertEquals(null, PluginRegistries.normalize("https://user:pw@x.example"))
        assertEquals(RegistryMode.Auto, RegistryMode.of("bogus"))
    }

    @Test fun `engine inputs combine registry env and MCP secrets`() {
        val env = ExtensionsEngineInputs.env(RegistryMode.Npmmirror, "", listOf(remote), { if (it == "mcp:github:X-Team") "t" else null }, Locale.US, "UTC")
        assertEquals(PluginRegistries.NPMMIRROR, env["DSH_PLUGIN_REGISTRY"])
        assertEquals("t", env["DSH_MCP_GITHUB_X_TEAM"])
    }
}
