package io.github.timhub135.dsh.core.provider

import io.github.timhub135.dsh.core.engine.EngineProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetDiagnosisTest {
    private fun engine(detail: String) = NetDiagnose.ofEngineError("TRANSPORT", "llm-service-lite: provider transport failed: $detail")

    @Test fun engineTransportCausesMapToHints() {
        val refused = engine("ECONNREFUSED 192.168.1.5:8080 (connection refused)")!!
        assertEquals(NetCause.Refused, refused.cause)
        assertEquals("ECONNREFUSED 192.168.1.5:8080 (connection refused)", refused.detail)
        for (c in listOf("ETIMEDOUT", "UND_ERR_CONNECT_TIMEOUT", "EHOSTUNREACH", "ENETUNREACH")) assertEquals(c, NetCause.Unreachable, engine("$c h:1 (x)")!!.cause)
        for (c in listOf("ENOTFOUND", "EAI_AGAIN")) assertEquals(c, NetCause.Dns, engine("$c h:443 (DNS lookup failed)")!!.cause)
        for (c in listOf("UNABLE_TO_VERIFY_LEAF_SIGNATURE", "ERR_TLS_CERT_ALTNAME_INVALID", "DEPTH_ZERO_SELF_SIGNED_CERT", "SELF_SIGNED_CERT_IN_CHAIN"))
            assertEquals(c, NetCause.Tls, engine("$c h:443 (TLS certificate rejected: x)")!!.cause)
        val other = engine("UND_ERR_HEADERS_TIMEOUT h:1 (no response headers in time)")!!
        assertEquals(NetCause.Other, other.cause)
        assertNull(other.cause.hint)
        assertEquals(NetCause.Other, engine("h:1 (unexpected redirect)")!!.cause)
    }

    @Test fun nonTransportOrCauselessErrorsKeepTheGenericRow() {
        assertNull(NetDiagnose.ofEngineError("TRANSPORT", "llm-service-lite: provider transport failed"))
        assertNull(NetDiagnose.ofEngineError("TRANSPORT", "DeepSeek Messages transport failed"))
        assertNull(NetDiagnose.ofEngineError("AUTH", "llm-service-lite: provider transport failed: ECONNREFUSED h:1"))
        assertNull(NetDiagnose.ofEngineError(null, "boom"))
    }

    @Test fun causelessTransportStillShowsTheErrorName() {
        // js patch 16: a fetch failure without any cause names itself instead of a bare host:port (#28).
        val d = engine("integrate.api.nvidia.com:443 (TypeError: fetch failed)")!!
        assertEquals(NetCause.Other, d.cause)
        assertEquals("integrate.api.nvidia.com:443 (TypeError: fetch failed)", d.detail)
    }

    @Test fun providerFormatErrorsAreNotNetworkErrors() {
        val msg = "llm-service-lite: could not parse the provider's response: TypeError: Cannot read properties of null (reading 'prompt_tokens')"
        assertEquals("TypeError: Cannot read properties of null (reading 'prompt_tokens')", NetDiagnose.formatErrorOf("MALFORMED_RESPONSE", msg))
        assertNull(NetDiagnose.ofEngineError("MALFORMED_RESPONSE", msg))
        assertEquals("provider stream contains invalid JSON", NetDiagnose.formatErrorOf("MALFORMED_RESPONSE", "llm-service-lite: provider stream contains invalid JSON"))
        assertNull(NetDiagnose.formatErrorOf("TRANSPORT", msg))
        assertNull(NetDiagnose.formatErrorOf(null, msg))
    }

    @Test fun javaExceptionsFromTheConnectionTest() {
        assertEquals(NetCause.Refused, NetDiagnose.ofException(ConnectException("failed to connect to /192.168.1.5 (port 8080) after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)"))!!.cause)
        assertEquals(NetCause.Unreachable, NetDiagnose.ofException(ConnectException("failed to connect: isConnected failed: EHOSTUNREACH (No route to host)"))!!.cause)
        assertEquals(NetCause.Refused, NetDiagnose.ofException(ConnectException("Connection refused"))!!.cause)
        assertEquals(NetCause.Unreachable, NetDiagnose.ofException(SocketTimeoutException("failed to connect to /10.0.0.2 (port 80) after 10000ms"))!!.cause)
        assertEquals(NetCause.Dns, NetDiagnose.ofException(UnknownHostException("Unable to resolve host \"gw.lan\": No address associated with hostname"))!!.cause)
        assertEquals(NetCause.Tls, NetDiagnose.ofException(SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."))!!.cause)
        assertEquals(NetCause.Tls, NetDiagnose.ofException(IOException("wrapped", SSLHandshakeException("x")))!!.cause)
        assertEquals(NetCause.Other, NetDiagnose.ofException(IOException("unexpected end of stream"))!!.cause)
        assertTrue(NetDiagnose.ofException(ConnectException("Connection refused"))!!.detail.startsWith("ConnectException: "))
        // HTTP status / bad JSON are not network failures.
        assertNull(NetDiagnose.ofException(IllegalStateException("HTTP 401 unauthorized")))
        assertNull(NetDiagnose.ofException(IllegalArgumentException("unexpected /models response")))
    }

    @Test fun onDeviceBaseUrlsWarn() {
        for (u in listOf("http://localhost:8080/v1", "http://127.0.0.1:1234/v1", "http://127.1.2.3/v1", "http://[::1]:8080/v1", "http://0.0.0.0:8080/v1", "https://LOCALHOST/v1", " http://127.0.0.1:8080/v1 "))
            assertTrue(u, NetDiagnose.isOnDeviceAddress(u))
        for (u in listOf("http://192.168.1.5:8080/v1", "https://api.openai.com/v1", "http://10.0.2.2:8080/v1", "", "not a url", "http://127.0.0.1.example.com/v1"))
            assertFalse(u, NetDiagnose.isOnDeviceAddress(u))
    }

    @Test fun proxyEnvMirrorsAndroid() {
        assertTrue(EngineProxy.env(null).isEmpty())
        assertTrue(EngineProxy.env(EngineProxy.Proxy("", 8080, emptyList())).isEmpty())
        val env = EngineProxy.env(EngineProxy.Proxy("192.168.1.1", 3128, listOf("192.168.*", "*.lan", " corp.example ", "")))
        assertEquals("http://192.168.1.1:3128", env["HTTPS_PROXY"])
        assertEquals("http://192.168.1.1:3128", env["http_proxy"])
        // Exclusions verbatim (globs are matched by js patch 15), then loopback; LAN is proxied unless excluded.
        assertEquals("192.168.*,*.lan,corp.example,localhost,127.0.0.1,::1,[::1]", env["NO_PROXY"])
        assertEquals(env["NO_PROXY"], env["no_proxy"])
        assertEquals("1", env["NODE_USE_ENV_PROXY"])
        assertEquals("localhost,127.0.0.1,::1,[::1]", EngineProxy.env(EngineProxy.Proxy("p", 1, emptyList()))["NO_PROXY"])
        assertEquals("http://[fd00::1]:8080", EngineProxy.env(EngineProxy.Proxy("fd00::1", 8080, emptyList()))["HTTP_PROXY"])
        assertEquals(listOf("localhost", "*.lan"), EngineProxy.parseNonProxyHosts("localhost|*.lan|"))
        assertEquals("LOCALHOST,127.0.0.1,::1,[::1]", EngineProxy.env(EngineProxy.Proxy("p", 1, listOf("LOCALHOST")))["NO_PROXY"])
    }

    @Test fun modelNotAvailableIsNotTheKey() {
        val u = NetDiagnose.modelUnavailableOf("MODEL_NOT_AVAILABLE", "llm-service-lite: model \"qwen-max\" is not available to this account: Model access denied.")
        assertEquals(NetDiagnose.ModelUnavailable("qwen-max", "Model access denied"), u)
        assertEquals(NetDiagnose.ModelUnavailable(null, "您无权访问glm-4-plus"), NetDiagnose.modelUnavailableOf("MODEL_NOT_AVAILABLE", "llm-service-lite: the model is not available to this account: 您无权访问glm-4-plus"))
        assertNull(NetDiagnose.modelUnavailableOf("AUTH", "llm-service-lite: model \"x\" is not available to this account: y"))
    }
}
