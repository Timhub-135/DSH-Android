package io.github.timhub135.dsh.core.designsystem

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.timhub135.dsh.core.data.ThemeMode

private fun style(size: Int, line: Int, weight: Int = 400, family: FontFamily = FontFamily.Default) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), fontFamily = family)

/** dsh `--dsw-font-*` scale plus the markdown/code tokens (docs/design-tokens.md). */
object DshType {
    val xl24 = style(24, 32, 600)
    val l20 = style(20, 28, 500)
    val base16 = style(16, 24)
    val base16m = style(16, 24, 500)
    val s14 = style(14, 22)
    val s14m = style(14, 22, 500)
    val xs13 = style(13, 20)
    val xs13m = style(13, 20, 500)
    val xxs12 = style(12, 18)
    val xxs12m = style(12, 18, 500)
    val xxxs11 = style(11, 14)
    val xxxs11m = style(11, 14, 500)
    val tiny10 = style(10, 16)
    /** Process rows (DisclosureRow): 13/24. */
    val row13 = style(13, 24)
    /** Markdown body 14/24. */
    val body = style(14, 24)
    val mono11 = style(11, 16, family = FontFamily.Monospace)
    val mono12 = style(12, 19, family = FontFamily.Monospace)
    val mono13 = style(13, 20, family = FontFamily.Monospace)
}

private val M3Typography = Typography(
    headlineMedium = style(28, 36, 600),
    headlineSmall = DshType.xl24,
    titleLarge = DshType.l20,
    titleMedium = DshType.base16m,
    titleSmall = DshType.s14m,
    bodyLarge = DshType.base16,
    bodyMedium = DshType.s14,
    bodySmall = DshType.xs13,
    labelLarge = DshType.s14m,
    labelMedium = DshType.xxs12m,
    labelSmall = DshType.xxxs11m,
)

/** dsh radius tiers (docs/ui-radius.md). */
object Radius {
    val xs = RoundedCornerShape(4.dp)
    val sm = RoundedCornerShape(8.dp)
    val tile = RoundedCornerShape(10.dp)
    val md = RoundedCornerShape(12.dp)
    val lg = RoundedCornerShape(16.dp)
    val xl = RoundedCornerShape(20.dp)
    val panel = RoundedCornerShape(28.dp)
}

private val M3Shapes = Shapes(extraSmall = Radius.xs, small = Radius.sm, medium = Radius.md, large = Radius.lg, extraLarge = Radius.panel)

/** The DSH hairline: 0.5 px borders become 0.5 dp. */
val Hairline = 0.5.dp

/** True when the user turned animations off (dsh disables shimmer/whale/spinners under reduced motion). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun DshTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    val colors = if (dark) DarkDsh else LightDsh
    val context = LocalContext.current
    val reduced = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    // System-bar icons follow the in-app theme, not the system night mode (enableEdgeToEdge's default).
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        context.findActivity()?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(
        LocalDshColors provides colors,
        LocalReducedMotion provides reduced,
        LocalTextSelectionColors provides TextSelectionColors(colors.business, Color(0x663B82F6)),
    ) {
        MaterialTheme(colorScheme = schemeOf(colors), typography = M3Typography, shapes = M3Shapes, content = content)
    }
}

object Dsh {
    val colors: DshColors
        @Composable @ReadOnlyComposable get() = LocalDshColors.current
}

fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}
