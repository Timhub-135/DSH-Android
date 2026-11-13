package io.github.timhub135.dsh.core.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.system.Os
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * The single place where the `:engine` process environment is assembled before `node::Start`.
 *
 * The app's Bundle (EngineManager.environment) is applied first; then the runtime-provisioned
 * variables below. Bundle keys are plain setenv calls and overwrite each other, so anything that
 * must *combine* (NODE_OPTIONS) is merged here, never set twice.
 *  - NODE_EXTRA_CA_CERTS: the Android trust store exported as PEM ([CaBundle]).
 *  - NODE_COMPILE_CACHE: V8 code cache for every loaded module (Node ≥ 22.1), in cacheDir so the
 *    system may reclaim it; Node keys entries by file content and Node version, so engine updates
 *    simply miss and repopulate.
 *  - NODE_OPTIONS: app options + [RUNTIME_NODE_OPTIONS], de-duplicated by flag name.
 *  - HTTP(S)_PROXY / NO_PROXY: Android's proxy for the default network ([EngineProxy]), read once
 *    per engine start; with one, argv also gets `--use-env-proxy`.
 * Flags Node refuses in NODE_OPTIONS (--expose-internals) go in argv: [NODE_ARGV].
 */
object EngineRuntimeEnv {
    private const val TAG = "EngineRuntimeEnv"

    /** argv flags before the entry script. --expose-internals replaces the require-builtin native addon (js patch 02). */
    val NODE_ARGV = listOf("--expose-internals")

    /** Preload that restores env-driven Node behaviour Android's AT_SECURE disables (see the file's header). */
    const val PRELOAD = "node_modules/@dsh-android/bundle-android/android-env.cjs"

    /**
     * Full Node argv before the entry script. Zygote-forked processes run with AT_SECURE=1, so
     * Node ignores NODE_OPTIONS (and TMPDIR, NODE_COMPILE_CACHE, NODE_EXTRA_CA_CERTS — restored by
     * [PRELOAD]); the composed NODE_OPTIONS are therefore passed as argv flags as well.
     */
    fun nodeArgv(engineDir: File, env: Bundle): List<String> {
        val options = mergeNodeOptions(env.getString("NODE_OPTIONS"), RUNTIME_NODE_OPTIONS).split(' ').filter { it.isNotBlank() }
        val preload = File(engineDir, PRELOAD).takeIf { it.exists() }?.let { listOf("--require", it.path) } ?: emptyList()
        return NODE_ARGV + options + (if (proxyApplied) listOf("--use-env-proxy") else emptyList()) + preload
    }

    /** [apply] exported a proxy; read by [nodeArgv] in the same (engine) process. */
    @Volatile private var proxyApplied = false

    /**
     * Options appended to the app's NODE_OPTIONS (engine argv and every child node, e.g. pnpm).
     *
     * Happy-eyeballs attempt timeout: with several addresses for a host, Node's `net.connect`
     * gives each but the last 250 ms (the default) and then *closes* it and moves on; it does not
     * keep earlier attempts racing like curl does. On a high-latency or lossy path (mainland China →
     * Fastly: cdn.jsdelivr.net has 4 IPv4 addresses) a 250 ms SYN/SYN-ACK round trip is common, so
     * the first three addresses are dropped just before they would connect and the whole 10 s
     * connect timeout rides on the last one ("Connect Timeout Error (attempted addresses: …4…,
     * timeout 10000ms)") while curl connects in a second. 1.5 s covers a slow round trip and one
     * lost SYN and still leaves the last address most of undici's 10 s.
     */
    internal val RUNTIME_NODE_OPTIONS = listOf("--network-family-autoselection-attempt-timeout=1500")

    fun apply(context: Context, env: Bundle) {
        val vars = LinkedHashMap<String, String>()
        env.keySet().forEach { vars[it] = env.getString(it) ?: "" }
        CaBundle.export(context)?.let { vars["NODE_EXTRA_CA_CERTS"] = it.path }
        vars["NODE_COMPILE_CACHE"] = File(context.cacheDir, "node-compile-cache").apply { mkdirs() }.path
        mergeNodeOptions(vars["NODE_OPTIONS"], RUNTIME_NODE_OPTIONS).takeIf { it.isNotEmpty() }?.let { vars["NODE_OPTIONS"] = it }
        val proxy = systemProxy(context)?.also { Log.i(TAG, "engine proxy ${it.host}:${it.port}, ${it.exclusions.size} exclusions") }
        val proxyEnv = EngineProxy.env(proxy)
        EngineProxy.ENV_NAMES.forEach { if (it !in proxyEnv) runCatching { Os.unsetenv(it) } }
        vars += proxyEnv
        proxyApplied = proxyEnv.isNotEmpty()
        vars.forEach { (k, v) -> Os.setenv(k, v, true) }
    }

    /**
     * The proxy HttpURLConnection would use: [ConnectivityManager.getDefaultProxy] (Wi-Fi, global
     * or VPN proxy of the default network), else the `http(s).proxyHost` properties Android sets in
     * every app process. A PAC proxy is skipped: Node has no PAC evaluator.
     */
    private fun systemProxy(context: Context): EngineProxy.Proxy? = runCatching {
        val info = context.getSystemService(ConnectivityManager::class.java)?.defaultProxy
        if (info != null && info.pacFileUrl != null && info.pacFileUrl != Uri.EMPTY) {
            Log.i(TAG, "PAC proxy configured; not applied to the engine (requests go direct)")
            return null
        }
        if (info != null && !info.host.isNullOrBlank() && info.port > 0)
            return EngineProxy.Proxy(info.host, info.port, info.exclusionList.orEmpty().toList())
        val host = System.getProperty("https.proxyHost")?.takeIf { it.isNotBlank() } ?: System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() } ?: return null
        val port = (System.getProperty("https.proxyPort") ?: System.getProperty("http.proxyPort"))?.toIntOrNull() ?: 80
        EngineProxy.Proxy(host, port, EngineProxy.parseNonProxyHosts(System.getProperty("http.nonProxyHosts")))
    }.onFailure { Log.w(TAG, "reading the system proxy failed; engine connects directly", it) }.getOrNull()

    /** Joins option lists; a later `--flag[=value]` replaces an earlier one with the same name. */
    fun mergeNodeOptions(base: String?, extra: List<String>): String {
        val out = LinkedHashMap<String, String>()
        (base.orEmpty().split(' ').filter { it.isNotBlank() } + extra).forEach { out[it.substringBefore('=')] = it }
        return out.values.joinToString(" ")
    }

    /**
     * Exports `AndroidCAStore` — the system CAs minus those the user disabled, plus user-installed
     * CAs — to `files/certs/android-ca.pem` for NODE_EXTRA_CA_CERTS (Node's bundled Mozilla roots
     * stay in place; this adds the device's view, incl. Conscrypt APEX updates and corporate/proxy
     * CAs). The file is rewritten only when the digest of the store's aliases and certificates
     * changes; reading the store costs a few tens of ms per engine start.
     */
    object CaBundle {
        fun export(context: Context): File? = runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val certs = ks.aliases().toList().sorted().mapNotNull { a -> (ks.getCertificate(a) as? X509Certificate)?.let { a to it } }
            if (certs.isEmpty()) return null
            val md = MessageDigest.getInstance("SHA-256")
            certs.forEach { (alias, c) -> md.update(alias.toByteArray()); md.update(c.encoded) }
            val digest = md.digest().joinToString("") { "%02x".format(it) }
            val dir = File(context.filesDir, "certs").apply { mkdirs() }
            val pem = File(dir, "android-ca.pem")
            val stamp = File(dir, "android-ca.sha256")
            if (pem.exists() && stamp.takeIf { it.exists() }?.readText()?.trim() == digest) return pem
            val tmp = File(dir, "android-ca.pem.tmp")
            tmp.bufferedWriter().use { w ->
                certs.forEach { (alias, c) ->
                    w.write("# $alias\n-----BEGIN CERTIFICATE-----\n")
                    w.write(Base64.encodeToString(c.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
                    w.write("\n-----END CERTIFICATE-----\n")
                }
            }
            check(tmp.renameTo(pem)) { "rename failed" }
            stamp.writeText(digest)
            Log.i(TAG, "exported ${certs.size} CA certificates (${certs.count { it.first.startsWith("user:") }} user-installed)")
            pem
        }.onFailure { Log.w(TAG, "CA export failed; Node uses its bundled roots only", it) }.getOrNull()
    }
}
