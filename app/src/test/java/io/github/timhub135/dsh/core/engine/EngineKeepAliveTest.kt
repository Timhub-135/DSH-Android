package io.github.timhub135.dsh.core.engine

import android.app.Service
import io.github.timhub135.dsh.core.extensions.ExtPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #52: 引擎常驻 keeps the engine (and the plugins' background work in it) running. */
class EngineKeepAliveTest {
    @Test fun idleStopOnlyWithoutKeepAliveAndWithoutWork() {
        assertTrue(EngineKeepAlive.mayStopWhenIdle(keepAlive = false, running = false, accountBusy = false))
        assertFalse(EngineKeepAlive.mayStopWhenIdle(keepAlive = true, running = false, accountBusy = false))
        assertFalse(EngineKeepAlive.mayStopWhenIdle(keepAlive = false, running = true, accountBusy = false))
        assertFalse(EngineKeepAlive.mayStopWhenIdle(keepAlive = false, running = false, accountBusy = true))
    }

    @Test fun onlyKeepAliveStartsAreSticky() {
        assertEquals(Service.START_STICKY, EngineKeepAlive.startMode(true))
        assertEquals(Service.START_NOT_STICKY, EngineKeepAlive.startMode(false))
    }

    @Test fun recognisesBackgroundPluginsFromTheIssue() {
        assertTrue(EngineKeepAlive.looksBackground("@kanadego/dsh-heartbeat"))
        assertTrue(EngineKeepAlive.looksBackground("@tencent-connect/dsh-qqbot"))
        assertTrue(EngineKeepAlive.looksBackground("dsh-telegram-bot"))
        assertTrue(EngineKeepAlive.looksBackground("dsh_cron"))
    }

    @Test fun ordinaryPluginsAreNotBackground() {
        assertFalse(EngineKeepAlive.looksBackground("@fffangchenghao/dsh-web-search"))
        assertFalse(EngineKeepAlive.looksBackground("dsh-plugin-guide"))
        // Whole words only: "qq" inside another word, "bot" at the start, do not count.
        assertFalse(EngineKeepAlive.looksBackground("dsh-squqqa"))
        assertFalse(EngineKeepAlive.looksBackground("dsh-bottle"))
    }

    @Test fun suggestsOnlyWhileOffAndSomethingWorksInTheBackground() {
        val bot = ExtPlugin(name = "@tencent-connect/dsh-qqbot", enabled = true)
        val search = ExtPlugin(name = "@fffangchenghao/dsh-web-search", enabled = true)
        assertTrue(EngineKeepAlive.suggest(keepAlive = false, backgroundSeen = false, plugins = listOf(search, bot)))
        assertFalse(EngineKeepAlive.suggest(keepAlive = true, backgroundSeen = false, plugins = listOf(bot)))
        assertFalse(EngineKeepAlive.suggest(keepAlive = false, backgroundSeen = false, plugins = listOf(search)))
        // A disabled bot does nothing in the background.
        assertFalse(EngineKeepAlive.suggest(keepAlive = false, backgroundSeen = false, plugins = listOf(bot.copy(enabled = false))))
        // A plugin already ran a background session: whatever its name.
        assertTrue(EngineKeepAlive.suggest(keepAlive = false, backgroundSeen = true, plugins = emptyList()))
        assertFalse(EngineKeepAlive.suggest(keepAlive = true, backgroundSeen = true, plugins = emptyList()))
    }
}
