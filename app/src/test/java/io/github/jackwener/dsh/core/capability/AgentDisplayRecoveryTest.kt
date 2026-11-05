package io.github.jackwener.dsh.core.capability

import android.view.Display
import io.github.jackwener.dsh.core.capability.AgentScreen.Companion.carryOver
import io.github.jackwener.dsh.core.capability.AgentScreen.Companion.displayOff
import io.github.jackwener.dsh.core.capability.AgentScreen.Companion.offMessage
import io.github.jackwener.dsh.core.capability.AgentScreen.Companion.recoveredNote
import io.github.jackwener.dsh.core.capability.AgentScreen.Companion.shouldRecreate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDisplayRecoveryTest {
    @Test fun offStates() {
        assertTrue(displayOff(Display.STATE_OFF))
        assertTrue(displayOff(Display.STATE_DOZE))
        assertTrue(displayOff(Display.STATE_DOZE_SUSPEND))
        assertFalse(displayOff(Display.STATE_ON))
        assertFalse(displayOff(Display.STATE_ON_SUSPEND))
        // Unknown (or an old shell service that cannot tell) is not a reason to tear the display down.
        assertFalse(displayOff(Display.STATE_UNKNOWN))
    }

    @Test fun recreateOnlyWhenANewDisplayWouldBeOn() {
        // Android 13+: own power group, a new display starts awake whatever the main screen does.
        assertTrue(shouldRecreate(33, Display.STATE_OFF))
        assertTrue(shouldRecreate(36, Display.STATE_ON))
        // Older: the display follows the main screen.
        assertTrue(shouldRecreate(31, Display.STATE_ON))
        assertFalse(shouldRecreate(31, Display.STATE_OFF))
        assertFalse(shouldRecreate(32, Display.STATE_DOZE))
    }

    @Test fun carriesOverOrdinaryAppsOldestFirst() {
        val rows = listOf(
            TaskRow(40, 81, "com.ss.android.ugc.aweme", "A", true, true),
            TaskRow(41, 0, "com.tencent.mm", "B", true, true),
            TaskRow(39, 81, "com.taobao.taobao", "C", true, false),
            TaskRow(38, 81, "com.android.launcher", "D", false, false),
            TaskRow(37, 81, "", "", true, null),
        )
        assertEquals(listOf(39, 40), carryOver(rows, 81).map { it.taskId })
        assertTrue(carryOver(rows, 82).isEmpty())
    }

    @Test fun messages() {
        assertTrue(recoveredNote(emptyList()).contains("app_launch"))
        assertTrue(recoveredNote(listOf("抖音")).contains("抖音"))
        assertTrue(offMessage("SecurityException: denied").contains("SecurityException: denied"))
        assertTrue(offMessage(null, mainOff = true).contains("phone's screen is off"))
        assertTrue(offMessage(null).contains("screen: \"main\""))
    }
}
