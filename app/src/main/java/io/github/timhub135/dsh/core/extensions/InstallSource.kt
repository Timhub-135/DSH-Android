package io.github.timhub135.dsh.core.extensions

import java.net.URI
import java.net.URLDecoder

/**
 * What the user pasted, typed or shared, recognized on the phone before anything is fetched
 * (PLUGINS-DESIGN §6.5). The app uses it to (a) show "安装 <spec>" as the first search result,
 * (b) refuse what can never work (git/ssh URLs) without a round trip, and (c) hand the engine a
 * clean input. The engine's `android/ext/resolve` / `inspect` still does the network-dependent
 * part (GitHub → npm name, release .tgz or codeload tarball; zip → tgz).
 */
enum class SourceKind {
    /** `name`, `name@ver`, `@scope/name@range`, or an npmjs.com / npmmirror.com package page. */
    Npm,
    /** A github.com repository or tree URL (engine prefers the npm package it publishes). */
    Github,
    /** An https URL of a `.tgz` / `.tar.gz`. */
    Tarball,
    /** An https URL of a `.zip` (engine repacks it). */
    Zip,
    /** A local `.tgz` / `.tar.gz` / `.zip` already copied into the app (SAF / share → cache/ext-staging). */
    File,
    /** `{"mcpServers": …}` or a single server object: belongs in the MCP tab. */
    McpJson,
    /** Recognized, but cannot work on a phone (git/ssh transports). */
    Rejected,
    /** Not a package name or a link we understand. */
    Unknown,
}

data class InstallInput(
    val kind: SourceKind,
    /** What to send as `input` to `android/ext/inspect` / `install` (normalized). */
    val spec: String,
    /** Package name when it is known without fetching. */
    val name: String? = null,
    val version: String? = null,
    /** For [SourceKind.Rejected]: the §15 code (`needs-git`). */
    val code: String? = null,
) {
    val installable: Boolean get() = kind in setOf(SourceKind.Npm, SourceKind.Github, SourceKind.Tarball, SourceKind.Zip, SourceKind.File)
}

object InstallSource {
    /** npm's rule for new package names (validate-npm-package-name), without the 214-char check. */
    private val NAME = Regex("^(?:@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*$")
    /** A version or range after `@` (`1.2.3`, `^1`, `latest`, `>=1 <2`, `next`). */
    private val RANGE = Regex("^[A-Za-z0-9.^~<>=*|x+\\- ]+$")
    private val COMMAND = Regex("^(?:npx\\s+(?:-y\\s+)?|npm\\s+(?:i|install|add)\\s+|pnpm\\s+(?:add|i|install)\\s+|yarn\\s+add\\s+|dsh\\s+plugin\\s+(?:--profile\\s+\\S+\\s+)?(?:add|install)\\s+)(?:-[-\\w]+\\s+)*")
    private val NPM_HOSTS = setOf("www.npmjs.com", "npmjs.com", "npmmirror.com", "www.npmmirror.com", "npm.im")

    fun classify(raw: String): InstallInput {
        val text = raw.trim().removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'").trim()
        if (text.isEmpty()) return InstallInput(SourceKind.Unknown, "")
        if (text.startsWith("{") || text.startsWith("\"mcpServers\"")) return InstallInput(SourceKind.McpJson, text)
        // Pasted shell commands: `npm i foo`, `pnpm add foo@1`, `dsh plugin add foo`.
        val stripped = COMMAND.find(text)?.let { text.substring(it.range.last + 1).trim().split(Regex("\\s+")).firstOrNull().orEmpty() } ?: text
        if (stripped.isEmpty()) return InstallInput(SourceKind.Unknown, text)

        val lower = stripped.lowercase()
        if (lower.startsWith("git+") || lower.startsWith("git://") || lower.startsWith("ssh://") || Regex("^[\\w.-]+@[\\w.-]+:").containsMatchIn(stripped))
            return InstallInput(SourceKind.Rejected, stripped, code = "needs-git")
        if (lower.startsWith("github:")) return github("https://github.com/" + stripped.substringAfter(':'))
        if (lower.startsWith("file:")) return local(stripped.substringAfter("file:").removePrefix("//"))
        if (stripped.startsWith("/")) return local(stripped)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return url(stripped)
        // Bare host paths people copy without a scheme.
        if (lower.startsWith("github.com/") || lower.startsWith("www.npmjs.com/") || lower.startsWith("npmjs.com/") || lower.startsWith("npmmirror.com/"))
            return url("https://$stripped")
        return npmSpec(stripped) ?: InstallInput(SourceKind.Unknown, stripped)
    }

    /** `name`, `name@ver`, `@scope/name`, `@scope/name@range`; null when it is not one. */
    fun npmSpec(s: String): InstallInput? {
        val at = if (s.startsWith("@")) s.indexOf('@', 1) else s.indexOf('@')
        val name = if (at > 0) s.substring(0, at) else s
        val ver = if (at > 0) s.substring(at + 1).trim() else null
        if (!NAME.matches(name) || name.length > 214) return null
        if (ver != null && (ver.isEmpty() || !RANGE.matches(ver))) return null
        return InstallInput(SourceKind.Npm, if (ver != null) "$name@$ver" else name, name, ver)
    }

    private fun local(path: String): InstallInput {
        val p = path.lowercase()
        return if (p.endsWith(".tgz") || p.endsWith(".tar.gz") || p.endsWith(".zip")) InstallInput(SourceKind.File, path)
        else InstallInput(SourceKind.Unknown, path)
    }

    private fun url(s: String): InstallInput {
        val uri = runCatching { URI(s.replace(" ", "%20")) }.getOrNull() ?: return InstallInput(SourceKind.Unknown, s)
        val host = uri.host?.lowercase() ?: return InstallInput(SourceKind.Unknown, s)
        val path = uri.rawPath.orEmpty()
        val lowerPath = path.lowercase()
        if (host in NPM_HOSTS) npmPage(host, path)?.let { return it }
        if (host == "github.com" || host == "www.github.com") {
            // A release asset or raw tarball on GitHub is still just a tarball.
            if (lowerPath.endsWith(".tgz") || lowerPath.endsWith(".tar.gz")) return InstallInput(SourceKind.Tarball, s)
            if (lowerPath.endsWith(".zip") && "/archive/" !in lowerPath) return InstallInput(SourceKind.Zip, s)
            return github(s)
        }
        if (lowerPath.endsWith(".tgz") || lowerPath.endsWith(".tar.gz")) return InstallInput(SourceKind.Tarball, s)
        if (lowerPath.endsWith(".zip")) return InstallInput(SourceKind.Zip, s)
        return InstallInput(SourceKind.Unknown, s)
    }

    /** `npmjs.com/package/<name>[/v/<ver>]`, `npmmirror.com/package/<name>[/v/<ver>]`, `npm.im/<name>`. */
    private fun npmPage(host: String, rawPath: String): InstallInput? {
        val segs = rawPath.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
        val rest = when {
            host == "npm.im" -> segs
            segs.firstOrNull() == "package" -> segs.drop(1)
            else -> return null
        }
        if (rest.isEmpty()) return null
        // `@scope/name` arrives as two segments, or as one when the slash was percent-encoded.
        val (name, after) = if (rest[0].startsWith("@") && '/' !in rest[0] && rest.size >= 2) "${rest[0]}/${rest[1]}" to rest.drop(2) else rest[0] to rest.drop(1)
        val ver = if (after.size >= 2 && after[0] == "v") after[1] else null
        if (!NAME.matches(name)) return null
        return InstallInput(SourceKind.Npm, if (ver != null) "$name@$ver" else name, name, ver)
    }

    /** github.com/o/r[.git][/tree/<ref>[/path]] → canonical https URL; other repo pages (issues, blob) → the repo. */
    private fun github(s: String): InstallInput {
        val uri = runCatching { URI(s) }.getOrNull() ?: return InstallInput(SourceKind.Unknown, s)
        val segs = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segs.size < 2) return InstallInput(SourceKind.Unknown, s)
        val owner = segs[0]
        val repo = segs[1].removeSuffix(".git")
        val base = "https://github.com/$owner/$repo"
        val spec = if (segs.size >= 4 && segs[2] == "tree") base + "/tree/" + segs.drop(3).joinToString("/") else base
        return InstallInput(SourceKind.Github, spec)
    }

    /** Looks like something the search box should offer to install directly (not a plain search word). */
    fun looksInstallable(raw: String): InstallInput? {
        val c = classify(raw)
        return when (c.kind) {
            SourceKind.Npm -> c.takeIf { raw.trim().contains('@') || raw.trim().contains('/') || raw.trim().contains('-') || raw.trim().startsWith("http") || COMMAND.containsMatchIn(raw.trim()) }
            SourceKind.Github, SourceKind.Tarball, SourceKind.Zip, SourceKind.File, SourceKind.Rejected, SourceKind.McpJson -> c
            SourceKind.Unknown -> null
        }
    }
}
