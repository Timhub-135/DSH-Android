package io.github.timhub135.dsh.feature.quick

import android.graphics.Bitmap
import io.github.timhub135.dsh.service.DshAccessibilityService

/** "What's on screen right now", captured at the moment the user summons the quick panel. */
object ScreenContext {
    @Volatile var packageName: String? = null; private set
    @Volatile var text: String? = null; private set
    /** Screenshot taken by a floating-ball double tap; handed to the panel's composer once. */
    @Volatile private var shot: Bitmap? = null

    fun attachShot(bmp: Bitmap?) { shot = bmp }
    fun takeShot(): Bitmap? = shot.also { shot = null }

    fun fromAssist(text: String?, packageName: String?) { this.text = text; this.packageName = packageName }

    /** Floating ball / tile path: read via accessibility, before our panel covers the screen. */
    fun captureFromAccessibility() {
        val svc = DshAccessibilityService.instance ?: return run { text = null; packageName = null }
        runCatching { svc.snapshot() }.getOrNull()?.let {
            if (it.packageName != null && !it.packageName.startsWith("io.github.timhub135.dsh")) { text = it.text.take(8000); packageName = it.packageName }
        }
    }

    fun clear() { text = null; packageName = null; shot = null }
}
