package io.github.timhub135.dsh.core.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputTest {
    @Test fun readBackMismatchIsReported() {
        assertFalse(TextInput.differs("hello world", "hello world"))
        // Formatting the field adds is not a mismatch.
        assertFalse(TextInput.differs("13800138000", "138 0013 8000"))
        assertFalse(TextInput.differs("13800138000", "138-0013-8000"))
        // Old text kept, truncated, or rejected: a mismatch.
        assertTrue(TextInput.differs("北京", "上海北京"))
        assertTrue(TextInput.differs("a very long text", "a very lo"))
        assertTrue(TextInput.differs("hello", ""))
    }
}
