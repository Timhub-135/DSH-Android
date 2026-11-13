package io.github.timhub135.dsh.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import io.github.timhub135.dsh.MainActivity
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.capability.AgentScreen
import io.github.timhub135.dsh.core.capability.CapabilityHub
import io.github.timhub135.dsh.core.capability.LiveCall
import io.github.timhub135.dsh.core.data.SettingsRepository
import io.github.timhub135.dsh.core.platform.ActivityStarts
import io.github.timhub135.dsh.core.platform.Notifications
import io.github.timhub135.dsh.core.session.Block
import io.github.timhub135.dsh.core.session.RowStatus
import io.github.timhub135.dsh.core.session.SessionStore
import io.github.timhub135.dsh.feature.conversation.ToolPresentation
import io.github.timhub135.dsh.feature.quick.QuickActivity
import io.github.timhub135.dsh.feature.quick.ScreenContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

/**
 * Edge-docked floating button that opens the quick panel from any app. Hidden while our own UI is in front.
 * Pulses while a turn runs and turns orange when a conversation waits for the user; a tap then opens that
 * conversation (TapTargets), otherwise the quick panel;
 * a double tap sends the current screen to the quick panel.
 *
 * With 操作时显示状态 on, a one-line capsule beside it says what the running turn does (or that it
 * needs the user, or how it ended), and a glowing border frames the screen while the agent
 * operates the user's own screen. None of them shows in DSH's own screenshots ([withoutOverlays]).
 */
@AndroidEntryPoint
class FloatingBallService : Service() {
    @Inject lateinit var store: SessionStore
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var hub: CapabilityHub
    @Inject lateinit var agentScreen: AgentScreen
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var view: ImageView? = null
    private var pulse: ObjectAnimator? = null
    private var capsule: TextView? = null
    /** The conversation the capsule speaks for (tap opens it). */
    private var capsuleSession: String? = null
    private var border: ControlBorder? = null
    private var borderPulse: ObjectAnimator? = null
    private var inFront = false
    private var dragging = false
    private var statusOn = true
    private var controlling = false
    /** Captures in progress that asked for the overlays to be hidden, and when the first hid them (0: nothing was shown). */
    private var shotHides = 0
    private var hiddenAt = 0L
    private var destroyed = false
    private val appInFront = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { inFront = true; render() }
        override fun onStop(owner: LifecycleOwner) { inFront = false; render() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_BALL, NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(getString(R.string.ball_running)).setOngoing(true).setSilent(true)
            .setContentIntent(Notifications.openRunning(this)).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WindowManager::class.java)
        val size = (48 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        val screenH = screenSize(wm).second
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenSize(wm).first - size - margin; y = screenH / 3
        }
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_ball)
            alpha = 0.85f
            contentDescription = getString(R.string.app_name)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var lastTap = 0L
        val singleTap = Runnable { open() }
        val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
        // A long press opens the quick panel already listening: speak a task in one step.
        var longPressed = false
        val longPress = Runnable { longPressed = true; v.removeCallbacks(singleTap); lastTap = 0; v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); openVoice() }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    longPressed = false; v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(v, lp)
                    if (!dragging && (abs(e.rawX - downX) >= 12 || abs(e.rawY - downY) >= 12)) { dragging = true; v.removeCallbacks(longPress); render() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); dragging = false; placeCapsule(); render(); true }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    dragging = false
                    if (longPressed) Unit
                    else if (abs(e.rawX - downX) < 12 && abs(e.rawY - downY) < 12) {
                        // A single tap waits out the double-tap window; a second tap inside it sends the screen instead.
                        if (e.eventTime - lastTap < doubleTapMs) { v.removeCallbacks(singleTap); lastTap = 0; sendScreen() }
                        else { lastTap = e.eventTime; v.postDelayed(singleTap, doubleTapMs) }
                    }
                    else {
                        // Re-read the size on every drop: rotation and fold changes move the far edge.
                        val screenW = screenSize(wm).first
                        lp.x = if (lp.x + size / 2 < screenW / 2) margin else screenW - size - margin; wm.updateViewLayout(v, lp)
                    }
                    placeCapsule(); render()
                    true
                }
                else -> false
            }
        }
        wm.addView(v, lp)
        view = v
        addCapsule()
        active = this
        ProcessLifecycleOwner.get().lifecycle.addObserver(appInFront)
        scope.launch {
            store.status.map { st -> st.entries.firstOrNull { it.value == RowStatus.Approval || it.value == RowStatus.Question }?.key to st.values.any { it == RowStatus.Running } }
                .distinctUntilChanged().collect { (waiting, running) -> show(waiting, running) }
        }
        scope.launch { settings.settings.map { it.ballStatus }.distinctUntilChanged().collect { statusOn = it; render() } }
        scope.launch { followStatus() }
        scope.launch { followControl() }
    }

    /** Ball, capsule and border visibility from the current state; the only place that changes it. */
    private fun render() {
        if (destroyed) return
        val shooting = shotHides > 0
        view?.visibility = when { inFront -> View.GONE; shooting -> View.INVISIBLE; else -> View.VISIBLE }
        capsule?.visibility = if (statusOn && !inFront && !shooting && !dragging && capsuleSession != null) View.VISIBLE else View.GONE
        val showBorder = statusOn && controlling && !shooting && (border != null || addBorder())
        border?.visibility = if (showBorder) View.VISIBLE else View.GONE
        if (showBorder && borderPulse == null) borderPulse = ObjectAnimator.ofFloat(border, View.ALPHA, 1f, 0.45f).apply {
            duration = 1200; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        } else if (!showBorder) { borderPulse?.cancel(); borderPulse = null }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addCapsule() {
        val c = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(CAPSULE_FILL) }
            visibility = View.GONE
            setOnClickListener { capsuleSession?.let { id -> go(TapTargets.capsule(id, QuickActivity.panelSession(this@FloatingBallService))) } }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(32), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(c, lp)
        capsule = c
        placeCapsule()
    }

    /** Beside the ball, on the side of the screen with room (the ball docks to an edge). */
    private fun placeCapsule() {
        val c = capsule ?: return
        val ball = view?.layoutParams as? WindowManager.LayoutParams ?: return
        val lp = c.layoutParams as? WindowManager.LayoutParams ?: return
        val w = screenSize(wm).first
        val gap = dp(6)
        val onRight = ball.x + ball.width / 2 >= w / 2
        lp.gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
        lp.x = if (onRight) w - ball.x + gap else ball.x + ball.width + gap
        lp.y = ball.y + (ball.height - lp.height) / 2
        c.maxWidth = minOf(dp(240), w - ball.width - dp(32))
        runCatching { wm.updateViewLayout(c, lp) }
    }

    /** False when the overlay permission is gone: the service stops (the ball cannot be shown either). */
    private fun addBorder(): Boolean {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return false }
        val (w, h) = screenSize(wm)
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            // Android 12+ passes touches through another app's overlay only when it is at most this opaque.
            if (Build.VERSION.SDK_INT >= 31) alpha = getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }
        val b = ControlBorder(this).apply { visibility = View.GONE }
        if (runCatching { wm.addView(b, lp) }.isFailure) { stopSelf(); return false }
        border = b
        return true
    }

    /** The capsule follows one conversation ([AgentStatus.focus]); when it ends, its outcome shows for a moment. */
    private suspend fun followStatus() {
        var focused: String? = null
        store.status.map { AgentStatus.focus(it, focused) }.distinctUntilChanged().collectLatest { id ->
            val prev = focused
            focused = id
            if (id == null) {
                val end = prev?.let { AgentStatus.of(store.state(it).value, false) as? CapsuleStatus.Ended }
                if (prev != null && end != null) { showCapsule(prev, end, null); delay(AgentStatus.ENDED_MS) }
                showCapsule(null, null, null)
                return@collectLatest
            }
            combine(store.state(id), agentScreen.displays.map { id in it }) { live, agent -> AgentStatus.of(live, agent) }
                .distinctUntilChanged()
                .map { st -> st to (st as? CapsuleStatus.Working)?.takeIf { it.background }?.let { backgroundApp(id) } }
                .flowOn(Dispatchers.Default)
                .collect { (st, app) -> showCapsule(id, st, app) }
        }
    }

    private fun backgroundApp(sessionId: String): String? =
        agentScreen.current(sessionId)?.let { d -> runCatching { DshAccessibilityService.instance?.packageOn(d) }.getOrNull() }?.let(agentScreen::label)

    private fun showCapsule(sessionId: String?, status: CapsuleStatus?, app: String?) {
        val c = capsule ?: return
        capsuleSession = sessionId.takeIf { status != null }
        if (status != null) {
            c.text = capsuleText(status, app)
            c.contentDescription = c.text
            (c.background as? GradientDrawable)?.setStroke(dp(1), when (status) {
                is CapsuleStatus.NeedsYou -> COLOR_NEEDS_YOU
                is CapsuleStatus.Working -> COLOR_WORKING
                is CapsuleStatus.Ended -> when (status.end) {
                    CapsuleStatus.End.Done -> COLOR_DONE; CapsuleStatus.End.Stopped -> COLOR_STOPPED; CapsuleStatus.End.Failed -> COLOR_FAILED
                }
            })
            placeCapsule()
        }
        render()
    }

    private fun capsuleText(s: CapsuleStatus, app: String?): String = when (s) {
        is CapsuleStatus.NeedsYou -> when (s.need) {
            CapsuleStatus.Need.Permission -> getString(R.string.status_needs_you, getString(R.string.status_permission))
            CapsuleStatus.Need.Approval -> s.detail?.let { getString(R.string.status_needs_you, getString(R.string.status_approve, getString(ToolPresentation.info(it).title))) }
            else -> s.detail?.takeIf { it.isNotBlank() }?.let { getString(R.string.status_needs_you, it.lineSequence().first()) }
        } ?: getString(R.string.ball_needs_you)
        is CapsuleStatus.Working -> {
            val step = s.step?.let(::stepText)
            when {
                s.background && app != null -> if (step != null) getString(R.string.status_background_step, app, step) else getString(R.string.status_background, app)
                else -> step ?: getString(R.string.status_thinking)
            }
        }
        is CapsuleStatus.Ended -> getString(when (s.end) {
            CapsuleStatus.End.Done -> R.string.turn_done; CapsuleStatus.End.Stopped -> R.string.turn_stopped; CapsuleStatus.End.Failed -> R.string.status_failed
        })
    }

    /** The conversation row's wording: tool title plus its one-line summary (点击 “提交订单”). */
    private fun stepText(t: Block.Tool): String {
        val title = getString(ToolPresentation.info(t.name).title)
        return ToolPresentation.summary(this, t)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "$title $it" } ?: title
    }

    /** The border shows while a turn operates the user's own screen ([AgentStatus.borderUntil]). */
    private suspend fun followControl() {
        var onMain = emptyMap<Long, LiveCall>()
        val lastActed = HashMap<String, Long>()
        combine(hub.liveCalls, store.status, ::Pair).collectLatest { (calls, status) ->
            val now = SystemClock.elapsedRealtime()
            onMain.filterKeys { it !in calls }.values.filter { it.acts && it.sessionId.isNotEmpty() }
                .forEach { lastActed[it.sessionId.substringBefore('/')] = now }
            onMain = calls.filterValues { it.mainScreen }
            val roots = AgentStatus.roots(status)
            lastActed.keys.retainAll { roots[it] == RowStatus.Running }
            while (true) {
                val until = AgentStatus.borderUntil(calls.values, lastActed, status, SystemClock.elapsedRealtime())
                if (controlling != (until != null)) { controlling = until != null; render() }
                if (until == null || until == Long.MAX_VALUE) break
                delay(until - SystemClock.elapsedRealtime())
            }
        }
    }

    /**
     * Runs [capture] with every overlay hidden; waits until they have been hidden a frame's worth
     * (only when something was on screen; a capture joining one in progress waits out the same moment).
     */
    private suspend fun <T> hidingOverlays(capture: suspend () -> T): T {
        // withContext can be cancelled on its way back after the block ran: the count is undone whenever it was taken.
        var counted = false
        try {
            val wait = withContext(Dispatchers.Main.immediate) {
                if (shotHides++ == 0) hiddenAt = if (listOfNotNull(view, capsule, border).any { it.visibility == View.VISIBLE }) SystemClock.elapsedRealtime() else 0L
                counted = true
                render()
                if (hiddenAt == 0L) 0L else hiddenAt + SHOT_HIDE_MS - SystemClock.elapsedRealtime()
            }
            if (wait > 0) delay(wait)
            return capture()
        } finally {
            if (counted) withContext(NonCancellable + Dispatchers.Main.immediate) { shotHides--; render() }
        }
    }

    private fun show(waiting: String?, running: Boolean) {
        val v = view ?: return
        v.setImageResource(when { waiting != null -> R.drawable.ic_ball_attention; running -> R.drawable.ic_ball_working; else -> R.drawable.ic_ball })
        v.contentDescription = getString(when { waiting != null -> R.string.ball_needs_you; running -> R.string.ball_working; else -> R.string.app_name })
        if (running && waiting == null) {
            if (pulse == null) pulse = ObjectAnimator.ofFloat(v, View.ALPHA, 0.85f, 0.45f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        } else {
            pulse?.cancel(); pulse = null
            v.alpha = 0.85f
        }
    }

    /** Captures the main screen (screenshot without the ball, plus its text) and opens the quick panel with it attached. */
    private fun sendScreen() {
        if (view == null) return
        val a11y = DshAccessibilityService.instance
        if (a11y == null) {
            Toast.makeText(this, R.string.ball_screen_needs_a11y, Toast.LENGTH_SHORT).show()
            return open()
        }
        scope.launch {
            hidingOverlays {
                ScreenContext.captureFromAccessibility()
                // Android 10 has no accessibility screenshot, and secure pages refuse one: the screen text still goes along.
                ScreenContext.attachShot(runCatching { a11y.capture().bitmap }.getOrNull())
            }
            ActivityStarts.start(this@FloatingBallService, Intent(this@FloatingBallService, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_SCREEN, true))
        }
    }

    /** Full window size (displayMetrics excludes insets on some devices, which pushed the ball half off-screen). */
    private fun screenSize(wm: WindowManager): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val wm = getSystemService(WindowManager::class.java)
        val (w, h) = screenSize(wm)
        val margin = (8 * resources.displayMetrics.density).toInt()
        lp.x = if (lp.x + lp.width / 2 < w / 2) margin else w - lp.width - margin
        lp.y = lp.y.coerceIn(0, (h - lp.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, lp) }
        placeCapsule()
        border?.let { b -> (b.layoutParams as? WindowManager.LayoutParams)?.let { blp -> blp.width = w; blp.height = h; runCatching { wm.updateViewLayout(b, blp) } } }
    }

    /** A tap: the conversation waiting for the user or running (never a new one while a task runs, #39), else the quick panel. */
    private fun open() = go(TapTargets.ball(store.status.value, capsuleSession, QuickActivity.panelSession(this)))

    private fun go(target: TapTarget) = ActivityStarts.start(this, when (target) {
        is TapTarget.Conversation -> Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, target.sessionId)
        is TapTarget.Panel -> Intent(this, QuickActivity::class.java).apply { target.sessionId?.let { putExtra(QuickActivity.EXTRA_SESSION, it) } }
    })

    private fun openVoice() = ActivityStarts.start(this, Intent(this, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_VOICE, true))

    override fun onDestroy() {
        destroyed = true
        if (active === this) active = null
        scope.cancel()
        pulse?.cancel(); pulse = null
        borderPulse?.cancel(); borderPulse = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appInFront)
        val wm = getSystemService(WindowManager::class.java)
        listOfNotNull(view, capsule, border).forEach { runCatching { wm.removeView(it) } }
        view = null; capsule = null; border = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Thin glowing frame along the screen edges; draws only, never takes input or accessibility focus. */
    private class ControlBorder(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10 * density; color = COLOR_WORKING; alpha = 120
            maskFilter = BlurMaskFilter(6 * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; color = COLOR_WORKING }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }

        override fun onDraw(canvas: Canvas) {
            // Follow the display's rounded corners where the system reports them (Android 12+).
            val r = (if (Build.VERSION.SDK_INT >= 31) rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() else null)
                ?: (16 * density)
            val i = line.strokeWidth / 2
            canvas.drawRoundRect(i, i, width - i, height - i, r, r, glow)
            canvas.drawRoundRect(i, i, width - i, height - i, r, r, line)
        }
    }

    companion object {
        private const val SHOT_HIDE_MS = 120L
        private const val CAPSULE_FILL = 0xE60F1115.toInt()
        private const val COLOR_WORKING = 0xFF4176E6.toInt()
        private const val COLOR_NEEDS_YOU = 0xFFF59E0B.toInt()
        private const val COLOR_DONE = 0xFF22C55E.toInt()
        private const val COLOR_STOPPED = 0xFF9CA3AF.toInt()
        private const val COLOR_FAILED = 0xFFEF4444.toInt()

        /** The running service (cleared in onDestroy). */
        @SuppressLint("StaticFieldLeak")
        @Volatile private var active: FloatingBallService? = null

        /** Runs [capture] (a picture of the user's screen) with the ball, capsule and border hidden. */
        suspend fun <T> withoutOverlays(capture: suspend () -> T): T {
            val s = active ?: return capture()
            return s.hidingOverlays(capture)
        }

        fun setEnabled(context: Context, on: Boolean) {
            val i = Intent(context, FloatingBallService::class.java)
            if (on && Settings.canDrawOverlays(context)) context.startForegroundService(i) else context.stopService(i)
        }
    }
}
