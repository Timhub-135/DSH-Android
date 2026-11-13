package io.github.timhub135.dsh.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCheckTest {
    private val release = "31c7e843953e3718ae633a6c7396068ddce8ca822f628cd3db4969f6ca3fc6ab"
    private val debug = "1".repeat(64)
    private val rotated = "2".repeat(64)
    private fun single(s: String) = Signers(setOf(s), listOf(s))
    private val own = "io.github.timhub135.dsh"

    @Test fun sameSignerOnly() {
        assertTrue(ApkCheck.sameSigner(single(release), single(release)))
        assertFalse(ApkCheck.sameSigner(single(release), single(debug)))
        assertFalse(ApkCheck.sameSigner(single(debug), single(release)))
        assertFalse(ApkCheck.sameSigner(Signers(emptySet(), emptyList()), Signers(emptySet(), emptyList())))
        // v3 rotation: the new key's lineage contains the installed key.
        assertTrue(ApkCheck.sameSigner(single(release), Signers(setOf(rotated), listOf(release, rotated))))
        assertFalse(ApkCheck.sameSigner(single(release), Signers(setOf(rotated), listOf(debug, rotated))))
        // Multi-signer sets must be equal.
        assertTrue(ApkCheck.sameSigner(Signers(setOf(release, debug), emptyList()), Signers(setOf(debug, release), emptyList())))
        assertFalse(ApkCheck.sameSigner(Signers(setOf(release, debug), emptyList()), single(release)))
    }

    @Test fun verdictChecksPackageVersionAndSigner() {
        fun v(pkg: String? = own, code: Long? = 22, signers: Signers? = single(release), expected: Long = 22) =
            ApkCheck.verdict(pkg, code, signers, own, installedCode = 21, expectedCode = expected, installed = single(release))
        assertNull(v())
        assertEquals(ApkProblem.Unreadable, v(pkg = null))
        assertEquals(ApkProblem.Unreadable, v(signers = null))
        assertEquals(ApkProblem.OtherPackage("com.evil"), v(pkg = "com.evil"))
        assertEquals(ApkProblem.NotNewer(21, 21), v(code = 21))
        assertEquals(ApkProblem.NotNewer(5, 21), v(code = 5))
        assertEquals(ApkProblem.WrongVersion(23, 22), v(code = 23))
        assertEquals(ApkProblem.OtherSigner, v(signers = single(debug)))
    }

    @Test fun manifestSignerPrecheck() {
        assertTrue(ApkCheck.manifestSignerMatches(single(release), release))
        assertFalse(ApkCheck.manifestSignerMatches(single(debug), release))
        assertFalse(ApkCheck.manifestSignerMatches(null, release))
        assertTrue(ApkCheck.manifestSignerMatches(Signers(setOf(rotated), listOf(release, rotated)), release))
    }
}
