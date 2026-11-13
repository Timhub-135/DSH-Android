package io.github.timhub135.dsh.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMachineTest {
    private fun listening(m: VoiceMachine = VoiceMachine()) = m.also { it.on(VoiceEvent.Start); it.on(VoiceEvent.Ready) }

    @Test fun `listening shows partials live, then the final text counts down and is sent`() {
        val m = listening()
        assertEquals(VoiceState.Listening(ready = true), m.state)
        m.on(VoiceEvent.Partial("打开"))
        m.on(VoiceEvent.Partial("打开微信"))
        assertEquals("打开微信", (m.state as VoiceState.Listening).text)
        assertEquals(VoiceEffect.Release, m.on(VoiceEvent.Final("打开微信给妈妈发消息")))
        assertEquals(VoiceState.Countdown("打开微信给妈妈发消息", 1500, 1500), m.state)
        assertNull(m.on(VoiceEvent.Tick(1000)))
        assertEquals(500L, (m.state as VoiceState.Countdown).leftMs)
        assertEquals(VoiceEffect.Send("打开微信给妈妈发消息"), m.on(VoiceEvent.Tick(600)))
        assertEquals(VoiceState.Idle, m.state)
        assertNull(m.on(VoiceEvent.Tick(50)))
    }

    @Test fun `cancel during the countdown keeps the text unsent`() {
        val m = listening()
        m.on(VoiceEvent.Final("查天气"))
        assertNull(m.on(VoiceEvent.Cancel))
        assertEquals(VoiceState.Idle, m.state)
        assertNull(m.on(VoiceEvent.Tick(5000)))
    }

    @Test fun `cancel while listening releases the recognizer and late results are ignored`() {
        val m = listening()
        m.on(VoiceEvent.Partial("打开"))
        assertEquals(VoiceEffect.Release, m.on(VoiceEvent.Cancel))
        assertNull(m.on(VoiceEvent.Final("打开相机")))
        assertNull(m.on(VoiceEvent.Error(VoiceError.Other)))
        assertEquals(VoiceState.Idle, m.state)
    }

    @Test fun `an error after partials still counts what was heard`() {
        val m = listening()
        m.on(VoiceEvent.Partial("设个闹钟"))
        m.on(VoiceEvent.Error(VoiceError.NoMatch))
        assertEquals("设个闹钟", (m.state as VoiceState.Countdown).text)
        // An empty final falls back to the last partial too.
        val n = listening()
        n.on(VoiceEvent.Partial("截图"))
        n.on(VoiceEvent.Final(" "))
        assertEquals("截图", (n.state as VoiceState.Countdown).text)
    }

    @Test fun `errors with nothing heard fail, and a retry listens again`() {
        val m = listening()
        assertEquals(VoiceEffect.Release, m.on(VoiceEvent.Error(VoiceError.Network)))
        assertEquals(VoiceState.Failed(VoiceError.Network), m.state)
        m.on(VoiceEvent.Start)
        assertTrue(m.state is VoiceState.Listening)
        m.on(VoiceEvent.Final(""))
        assertEquals(VoiceState.Failed(VoiceError.NoMatch), m.state)
        val p = listening()
        p.on(VoiceEvent.Partial("嗯"))
        p.on(VoiceEvent.Error(VoiceError.Permission))
        assertEquals(VoiceState.Failed(VoiceError.Permission), p.state)
    }

    @Test fun `without auto-send the final text just stays`() {
        val m = listening(VoiceMachine(autoSend = false))
        m.on(VoiceEvent.Final("hello"))
        assertEquals(VoiceState.Idle, m.state)
    }

    @Test fun `level, join and error codes`() {
        val m = listening()
        m.on(VoiceEvent.Level(10f))
        assertEquals(1f, (m.state as VoiceState.Listening).level)
        assertEquals(0f, VoiceMachine.level(-5f))
        assertEquals(0.5f, VoiceMachine.level(4f))
        assertEquals("打开微信", VoiceMachine.join("", "打开微信"))
        assertEquals("帮我打开微信", VoiceMachine.join("帮我", "打开微信"))
        assertEquals("open WeChat", VoiceMachine.join("open ", "WeChat"))
        assertEquals("note:打开", VoiceMachine.join("note:", "打开"))
        assertEquals(VoiceError.NoMatch, VoiceMachine.errorOf(7))
        assertEquals(VoiceError.Permission, VoiceMachine.errorOf(9))
        assertEquals(VoiceError.Language, VoiceMachine.errorOf(12))
        assertEquals(VoiceError.Other, VoiceMachine.errorOf(99))
    }
}
