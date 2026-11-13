package io.github.timhub135.dsh.core.account

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Cases from dsh ui-settings-account tests/formatBalance.client.spec.ts. */
class FormatBalanceTest {
    private fun f(v: String, s: String = "¥") = formatBalance(v, s, Locale.US)

    @Test fun zeroAndPlatformExponents() {
        assertEquals("¥0.00", f("0"))
        assertEquals("¥0.00", f("0E-16"))
        assertEquals("¥5.00", f("5.0000000000000000"))
    }

    @Test fun roundsDownAndGroups() {
        assertEquals("¥12,345.67", f("12345.678"))
        assertEquals("$1,000.00", f("1e+3", "$"))
        assertEquals("¥0.50", f(".5"))
    }

    @Test fun subCentAndNegative() {
        assertEquals("<¥0.01", f("0.004"))
        assertEquals("-¥0.01", f("-0.004"))
        assertEquals("-¥1,234.57", f("-1234.567"))
    }
}
