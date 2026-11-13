package io.github.timhub135.dsh.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

/**
 * Signing certificates of a package as SHA-256 hex: [current] signs the APK (several only for a
 * multi-signer APK); [history] is the v3 rotation lineage, oldest first (just [current] without rotation).
 */
data class Signers(val current: Set<String>, val history: List<String>) {
    companion object {
        fun of(info: PackageInfo): Signers? {
            val s = info.signingInfo ?: return null
            return if (s.hasMultipleSigners()) Signers(s.apkContentsSigners.map(::sha256).toSet(), emptyList())
            else s.signingCertificateHistory.map(::sha256).let { h -> Signers(setOfNotNull(h.lastOrNull()), h) }
        }

        fun sha256(sig: Signature): String = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Why a downloaded APK is refused ([ApkCheck.verdict]); null fields mean "could not be read". */
sealed interface ApkProblem {
    data object Unreadable : ApkProblem
    data class OtherPackage(val packageName: String) : ApkProblem
    data class NotNewer(val versionCode: Long, val installed: Long) : ApkProblem
    /** Not the manifest's version (a mirror served another release under the same name). */
    data class WrongVersion(val versionCode: Long, val expected: Long) : ApkProblem
    data object OtherSigner : ApkProblem
}

object ApkCheck {
    /**
     * The update may replace the installed app only when it is signed like it: the same signer
     * set, or (v3 key rotation) a single new signer whose lineage contains the installed one —
     * the same rule Android applies, checked here first so a mismatch gets a clear message
     * instead of INSTALL_FAILED_UPDATE_INCOMPATIBLE after the system dialog.
     */
    fun sameSigner(installed: Signers, apk: Signers): Boolean =
        installed.current.isNotEmpty() && (apk.current == installed.current ||
            (installed.current.size == 1 && apk.current.size == 1 && installed.current.single() in apk.history))

    /** Pure part of [check]: what is wrong with an APK read as ([pkg], [code], [signers]); null = fine. */
    fun verdict(pkg: String?, code: Long?, signers: Signers?, ownPackage: String, installedCode: Long, expectedCode: Long, installed: Signers): ApkProblem? = when {
        pkg == null || code == null || signers == null -> ApkProblem.Unreadable
        pkg != ownPackage -> ApkProblem.OtherPackage(pkg)
        code <= installedCode -> ApkProblem.NotNewer(code, installedCode)
        code != expectedCode -> ApkProblem.WrongVersion(code, expectedCode)
        !sameSigner(installed, signers) -> ApkProblem.OtherSigner
        else -> null
    }

    /** The installed app's signers (minSdk 29, so GET_SIGNING_CERTIFICATES is always there). */
    fun installed(context: Context): Signers? = runCatching {
        Signers.of(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
    }.getOrNull()

    fun installedVersionCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Reads [apk] with the package parser and checks it against the running app (and the manifest's [expectedCode]). */
    fun check(context: Context, apk: File, expectedCode: Long): ApkProblem? {
        val installed = installed(context) ?: return ApkProblem.Unreadable
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
        // Some builds leave signingInfo empty for archives; the legacy field still names the signer.
        val signers = info?.let(Signers::of) ?: runCatching {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.map(Signers::sha256)?.let { Signers(it.toSet(), if (it.size == 1) it else emptyList()) }
        return verdict(info?.packageName, info?.longVersionCode, signers, context.packageName, installedVersionCode(context), expectedCode, installed)
    }

    /** Before downloading: the manifest's signer is the installed one (a self-built or debug install is not). */
    fun manifestSignerMatches(installed: Signers?, signerSha256: String): Boolean = installed != null &&
        (installed.current == setOf(signerSha256) || signerSha256 in installed.history)
}
