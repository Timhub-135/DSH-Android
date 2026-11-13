package io.github.timhub135.dsh.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DiagnosticsTest {
    private fun r(s: String) = Redactor.redact(s)

    @Test fun redactsAddressesAndCredentials() {
        assertEquals("ECONNREFUSED <ip> (connection refused)", r("ECONNREFUSED 192.168.1.5:8080 (connection refused)"))
        assertEquals("connect to <ip> failed", r("connect to 10.0.0.2 failed"))
        assertEquals("host <ip> and <ip> and <ip>", r("host fe80::1c2:3ff:fe4d:5 and [::1]:11434 and 2001:db8:0:0:0:0:2:1"))
        assertEquals("mail <email> please", r("mail someone.name+x@example.co.uk please"))
        assertEquals("Authorization: <redacted> <redacted>", r("Authorization: Bearer abcdef123456"))
        assertEquals("key sk-<redacted> used", r("key sk-proj-AbC123xyz789 used"))
        assertEquals("api_key=<redacted>&model=x", r("api_key=s3cr3t&model=x"))
        assertEquals("""{"access_token": "<redacted>", "x": 1}""", r("""{"access_token": "abc.def", "x": 1}"""))
        assertEquals("https://<redacted>@host/v1?key=<redacted>", r("https://user:pass@host/v1?key=AIzaSyXX"))
        assertEquals("password: <redacted>", r("password: hunter2"))
        assertEquals("jwt <redacted> end", r("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig end"))
        assertEquals("token <redacted>", r("token " + "a1".repeat(24)))
    }

    @Test fun leavesOrdinaryTextAlone() {
        listOf("max_tokens=1024", "version: 0.2.5 (15) debug", "engine: 0.2.1-rc.2-ad86b59ac98", "2026-10-30 12:30:45 failed",
            "android: 15 (SDK 35)", "model: Xiaomi 23127PN0CC", "kimi / kimi-k3", "deepseek-ai/DeepSeek-V4-Flash", "std::string",
            "prompt_tokens: 120").forEach { assertEquals(it, r(it)) }
    }

    private fun input(runs: List<RunOutcome> = emptyList(), errors: List<String> = emptyList()) = DiagnosticsInput(
        appVersion = "0.2.6", versionCode = 16, buildType = "release", engineVersion = "0.2.1-rc.2-ad86b59ac98",
        device = "Xiaomi 2312", androidRelease = "15", sdk = 35, abis = listOf("arm64-v8a"),
        shizuku = "Shizuku is ready", accessibility = true, overlay = false, batteryExempt = true, notifications = true,
        agentScreenUnavailable = null, agentScreenSetting = true, deepseekCredential = "api key",
        roles = listOf("main model" to "ollama / qwen3:8b (passed test)"),
        providers = listOf(
            ProviderFacts("ollama", "ollama", true, "http://192.168.1.5:11434/v1", listOf("qwen3:8b", "gemma3"), listOf("qwen3:8b", "gone"), "openai-responses"),
            ProviderFacts("kimi", "kimi", false, "https://api.moonshot.cn/v1/secret-path?token=abc", listOf("kimi-k3"), emptyList()),
        ),
        runs = runs, engineState = "ready", engineErrors = errors, lastEngineExit = null,
        generatedAt = 1_793_000_000_000, timeZone = TimeZone.getTimeZone("UTC"),
    )

    @Test fun agentScreenLine() {
        assertTrue(DiagnosticsReport.build(input()).contains("agent screen: supported, setting on"))
        val degraded = input().copy(sdk = 31, agentScreenDegraded = "untrusted display: no ADD_TRUSTED_DISPLAY, SDK 31")
        assertTrue(DiagnosticsReport.build(degraded).contains("agent screen: degraded (untrusted display: no ADD_TRUSTED_DISPLAY, SDK 31), setting on"))
        val failed = input().copy(agentScreenError = "flags 0x9: SecurityException: nope")
        assertEquals("unsupported (creation failed, see last failure)", DiagnosticsReport.agentScreenStatus(failed))
        assertTrue(DiagnosticsReport.build(failed).contains("agent screen last failure: flags 0x9: SecurityException: nope"))
        assertEquals("unavailable (no Shizuku)", DiagnosticsReport.agentScreenStatus(failed.copy(agentScreenUnavailable = "no Shizuku")))
    }

    @Test fun reportHasFixedSectionsAndNoSecrets() {
        val runs = listOf(
            RunOutcome(1_793_000_000_000, "failed", "llm/TRANSPORT", "llm-service-lite: provider transport failed: ECONNREFUSED 192.168.1.5:11434 (connection refused)"),
            RunOutcome(1_792_999_000_000, "completed"),
            RunOutcome(1_792_998_000_000, "stopped by user"),
            RunOutcome(1_792_997_000_000, "completed"),
        )
        val text = DiagnosticsReport.build(input(runs, listOf("Error: request failed Authorization: Bearer sk-abcdef123456 to dev@example.com")))
        listOf("== App ==", "== Device ==", "== Phone access ==", "== Models ==", "== Providers ==", "== Recent runs (newest first) ==", "== Engine ==")
            .fold(-1) { at, header -> text.indexOf(header).also { assertTrue("$header in order", it > at) } }
        assertTrue(text.contains("version: 0.2.6 (16) release"))
        assertTrue(text.contains("engine: 0.2.1-rc.2-ad86b59ac98"))
        assertTrue(text.contains("- ollama [ollama] on · openai-responses · http · IP (LAN)"))
        assertTrue(text.contains("- kimi [kimi] off · openai-chat-completions · https · api.moonshot.cn\n"))
        assertTrue(text.contains("passed test: qwen3:8b\n"))
        assertTrue(text.contains("main model: ollama / qwen3:8b (passed test)"))
        assertTrue(text.contains("failed · llm/TRANSPORT · llm-service-lite: provider transport failed: ECONNREFUSED <ip> (connection refused)"))
        assertEquals(3, text.lines().count { it.startsWith("- 2026") || it.startsWith("- 2027") })
        for (secret in listOf("192.168", "secret-path", "token=abc", "sk-abcdef", "dev@example.com", "11434/v1")) assertFalse(secret, text.contains(secret))
    }

    @Test fun endpointLabels() {
        assertEquals("https · api.deepseek.com", DiagnosticsReport.endpointLabel("https://API.deepseek.com/v1/x?y=z"))
        assertEquals("http · IP (this phone)", DiagnosticsReport.endpointLabel("http://127.0.0.1:1234/v1"))
        assertEquals("http · IP (LAN)", DiagnosticsReport.endpointLabel("http://[fd00::5]:8080/v1"))
        assertEquals("https · IP (public)", DiagnosticsReport.endpointLabel("https://8.8.8.8/v1"))
        assertEquals("invalid address", DiagnosticsReport.endpointLabel("not a url"))
    }

    @Test fun runOutcomesNeverReadThePrompt() {
        val err = DiagnosticsReport.outcomeOf("app/error", 5, """{"kind":"engine","code":"TRANSPORT","message":"boom","retryText":"my private prompt"}""")!!
        assertEquals(RunOutcome(5, "failed", "engine/TRANSPORT", "boom"), err)
        assertEquals(RunOutcome(6, "failed", "llm/RATE_LIMITED", "slow down"),
            DiagnosticsReport.outcomeOf("turn/end", 6, """{"turn":2,"reason":{"kind":"error","error":{"code":"RATE_LIMITED","message":"slow down"}}}"""))
        assertEquals(RunOutcome(7, "completed"), DiagnosticsReport.outcomeOf("turn/end", 7, """{"reason":{"kind":"completed"}}"""))
        assertEquals(RunOutcome(8, "stopped by user"), DiagnosticsReport.outcomeOf("app/stopped", 8, "{}"))
        assertNull(DiagnosticsReport.outcomeOf("message/assistant", 9, """{"content":"hello"}"""))
    }

    @Test fun engineErrorLines() {
        val log = "starting\nok\nError: ECONNREFUSED\n  at foo\nwarning: x\nUnhandled rejection\n"
        assertEquals(listOf("Error: ECONNREFUSED", "Unhandled rejection"), DiagnosticsReport.errorLines(log))
        assertEquals(emptyList<String>(), DiagnosticsReport.errorLines(null))
    }
}
