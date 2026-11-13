package io.github.timhub135.dsh.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogSeqsTest {
    @Test fun `normal generation keeps engine seqs`() {
        val m = LogSeqs()
        assertNull(m.mapped("s", 1, 0))
        assertEquals(0L, m.start("s", 1, 0, lastLogged = -1))
        assertEquals(5L, m.mapped("s", 1, 5))
        // Engine restarted after an idle shutdown: the journal had everything, seqs continue.
        assertEquals(17L, m.start("s", 2, 17, lastLogged = 16))
        assertEquals(18L, m.mapped("s", 2, 18))
    }

    @Test fun `crashed generation's reused seqs are moved past the log`() {
        // Host repro: gen 1 notified 0..11, the journal kept 0..7; gen 2 continues at 8.
        val m = LogSeqs()
        m.start("s", 1, 0, lastLogged = -1)
        assertEquals(12L, m.start("s", 2, 8, lastLogged = 11)) // agent/inbox/spliced
        assertEquals(13L, m.mapped("s", 2, 9))                  // the continuation's turn/start
        assertEquals(161L, m.mapped("s", 2, 157))               // its turn/end
        assertNull(m.mapped("other", 2, 8))
    }
}
