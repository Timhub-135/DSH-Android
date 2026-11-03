package io.github.jackwener.dsh.core.engine

import android.app.Service
import io.github.jackwener.dsh.core.extensions.ExtPlugin

/**
 * 引擎常驻 (#52). By default the engine runs on demand: [EngineManager] stops it 5 minutes after the
 * last turn, and Android then reaps the empty `:engine` process. Plugins that work on their own
 * (a heartbeat timer, a QQ / Telegram bot connection) live inside that process, so they stop too.
 * With the setting on, the engine is started at app start, boot and app update, is never stopped
 * for idling, is restarted after an unexpected exit (RestartPolicy still applies), and its
 * foreground service is START_STICKY so Android re-creates it after the process is killed.
 */
object EngineKeepAlive {
    /** Broadcast from a re-created (sticky) EngineService to `:main`: start Node in it again. */
    const val ACTION_RESTORE = "io.github.jackwener.dsh.ENGINE_KEEP_ALIVE"
    /** EngineService start intent extra: whether the start was a 引擎常驻 one. */
    const val EXTRA_KEEP_ALIVE = "keepAlive"

    /** onStartCommand's result: only a 引擎常驻 engine asks Android to re-create it (see EngineManager.bind). */
    fun startMode(keepAlive: Boolean): Int = if (keepAlive) Service.START_STICKY else Service.START_NOT_STICKY

    /** Whether the idle timer may stop the engine. */
    fun mayStopWhenIdle(keepAlive: Boolean, running: Boolean, accountBusy: Boolean): Boolean = !keepAlive && !running && !accountBusy

    /**
     * Whether 设置 → 扩展 suggests turning 引擎常驻 on: it is off, and either a plugin has run a
     * background session before, or an enabled plugin looks like one that works on its own ([BACKGROUND_HINTS]).
     */
    fun suggest(keepAlive: Boolean, backgroundSeen: Boolean, plugins: List<ExtPlugin>): Boolean =
        !keepAlive && (backgroundSeen || plugins.any { it.enabled && looksBackground(it.name) })

    /** A package name hinting at background work (a heartbeat, a scheduler, a chat bot). */
    fun looksBackground(name: String): Boolean {
        val words = name.lowercase().split('@', '/', '-', '_', '.').filter { it.isNotEmpty() }
        return words.any { w -> w in BACKGROUND_HINTS || w.endsWith("bot") }
    }

    private val BACKGROUND_HINTS = setOf("heartbeat", "cron", "scheduler", "schedule", "timer", "qq", "wechat", "weixin",
        "telegram", "feishu", "lark", "dingtalk", "discord", "slack", "onebot", "napcat", "keepalive", "daemon")
}
