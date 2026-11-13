package io.github.timhub135.dsh.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Ported from DeepSeek Harness ui-theme design-platform.css (docs/design-tokens.md).

/** The DSH alias palette. Feature code reads these through [Dsh.colors]; M3 roles are derived from them. */
@Immutable
data class DshColors(
    val isDark: Boolean,
    // labels
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    val labelOnPrimary: Color,
    val labelShimmer: Color,
    val deepDiving: Color,
    val deepDivingShimmer: Color,
    val link: Color,
    val menuIcon: Color,
    // surfaces
    val bgBase: Color,
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgModule: Color,
    val bgSkeleton: Color,
    val sidebar: Color,
    val sidebarActive: Color,
    val bubble: Color,
    val inputMajor: Color,
    val selector: Color,
    val codeBlock: Color,
    val codeBanner: Color,
    val inlineCode: Color,
    val menuFill: Color,
    val toastBg: Color,
    // borders
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    // brand / buttons / interaction
    val brandPrimary: Color,
    val buttonPrimaryDimmed: Color,
    val info: Color,
    val floating: Color,
    val hover: Color,
    val pressed: Color,
    val hoverSolid: Color,
    val hoverDanger: Color,
    val switchThumbOff: Color,
    val business: Color,
    val businessTertiary: Color,
    // status
    val error: Color,
    val errorSecondary: Color,
    val success: Color,
    val successSecondary: Color,
    val successTertiary: Color,
    val warn: Color,
    val warnSecondary: Color,
    val warnLabel: Color,
    val warnTertiary: Color,
    val amber: Color,
    val idle: Color,
    val diffAdded: Color,
    val diffDeleted: Color,
    val scrim: Color,
    // trajectory kinds (dsh context-meter segment colors)
    val kindSystem: Color,
    val kindTools: Color,
    val kindMessages: Color,
)

val LightDsh = DshColors(
    isDark = false,
    labelPrimary = Color(0xFF0F1115), labelSecondary = Color(0xFF61666B), labelTertiary = Color(0xFF81858C), labelCaption = Color(0xFFADB2B8),
    labelDimmed = Color(0xFFE1E5EE), labelOnPrimary = Color(0xFFFFFFFF), labelShimmer = Color(0x4D000000),
    deepDiving = Color(0xFF345EBA), deepDivingShimmer = Color(0xFF243D80), link = Color(0xFF4176E6), menuIcon = Color(0xFF353638),
    bgBase = Color(0xFFFFFFFF), bgLayer1 = Color(0xFFFFFFFF), bgLayer2 = Color(0xFFFFFFFF), bgLayer3 = Color(0xFFFFFFFF),
    bgModule = Color(0xFFF5F6F7), bgSkeleton = Color(0x0A000000), sidebar = Color(0xFFF9FAFB), sidebarActive = Color(0xFFEBEEF2),
    bubble = Color(0xFFEDF3FE), inputMajor = Color(0xFFFFFFFF), selector = Color(0xFFF5F6F7),
    codeBlock = Color(0xFFF9FAFB), codeBanner = Color(0xFFF9FAFB), inlineCode = Color(0xFFFAFAFA), menuFill = Color(0xF0F8F9FA),
    toastBg = Color(0xFF353638),
    borderL1 = Color(0x0A000000), borderL2 = Color(0x1A000000), borderL3 = Color(0x1F000000), borderL4 = Color(0x29000000),
    brandPrimary = Color(0xFF0F1115), buttonPrimaryDimmed = Color(0xFFEBEEF2), info = Color(0xFF4176E6), floating = Color(0xFFFFFFFF),
    hover = Color(0x0F263148), pressed = Color(0x1A263148), hoverSolid = Color(0xFFF1F3F5), hoverDanger = Color(0x0DEC1313),
    switchThumbOff = Color(0xFFFFFFFF), business = Color(0xFF4176E6), businessTertiary = Color(0xFFE4EDFD),
    error = Color(0xFFEC1313), errorSecondary = Color(0xFFF25A5A), success = Color(0xFF22C55E), successSecondary = Color(0xFF4ED17E),
    successTertiary = Color(0xFFE6FAED), warn = Color(0xFFF59E0B), warnSecondary = Color(0xFFF7AD31), warnLabel = Color(0xFFDD8629),
    warnTertiary = Color(0xFFFEF5E7), amber = Color(0xFFDD8629), idle = Color(0xFFD4D4D4),
    diffAdded = Color(0x1422C55E), diffDeleted = Color(0x14EC1313), scrim = Color(0x3D000000),
    kindSystem = Color(0xFFADB2B8), kindTools = Color(0xFFA78BFA), kindMessages = Color(0xFF4D93F8),
)

val DarkDsh = DshColors(
    isDark = true,
    labelPrimary = Color(0xFFF9FAFB), labelSecondary = Color(0xFFCFD3D6), labelTertiary = Color(0xFFADB2B8), labelCaption = Color(0xFF81858C),
    labelDimmed = Color(0xFF43454A), labelOnPrimary = Color(0xFF0F1115), labelShimmer = Color(0x73FFFFFF),
    deepDiving = Color(0xFF7D9ADF), deepDivingShimmer = Color(0xFF8ABCFE), link = Color(0xFF7AAAFF), menuIcon = Color(0xFFEBEEF2),
    bgBase = Color(0xFF151517), bgLayer1 = Color(0xFF232324), bgLayer2 = Color(0xFF2C2C2E), bgLayer3 = Color(0xFF353638),
    bgModule = Color(0xFF353638), bgSkeleton = Color(0x14FFFFFF), sidebar = Color(0xFF1B1B1C), sidebarActive = Color(0xFF43454A),
    bubble = Color(0xFF2C2C2E), inputMajor = Color(0xFF2C2C2E), selector = Color(0xFF353638),
    codeBlock = Color(0xFF1B1B1C), codeBanner = Color(0xFF2C2C2E), inlineCode = Color(0xFF292929), menuFill = Color(0xF0303136),
    toastBg = Color(0xFF43454A),
    borderL1 = Color(0x0FFFFFFF), borderL2 = Color(0x1FFFFFFF), borderL3 = Color(0x29FFFFFF), borderL4 = Color(0x33FFFFFF),
    brandPrimary = Color(0xFFF9FAFB), buttonPrimaryDimmed = Color(0xFF43454A), info = Color(0xFF7AAAFF), floating = Color(0xFF2C2C2E),
    hover = Color(0x14FFFFFF), pressed = Color(0x24FFFFFF), hoverSolid = Color(0xFF353638), hoverDanger = Color(0x26F25A5A),
    switchThumbOff = Color(0xFFADB2B8), business = Color(0xFF7AAAFF), businessTertiary = Color(0xFF34415B),
    error = Color(0xFFF25A5A), errorSecondary = Color(0xFFF25A5A), success = Color(0xFF22C55E), successSecondary = Color(0xFF4ED17E),
    successTertiary = Color(0xFF233C2C), warn = Color(0xFFF59E0B), warnSecondary = Color(0xFFF7AD31), warnLabel = Color(0xFFDD8629),
    warnTertiary = Color(0xFF27241F), amber = Color(0xFFDD8629), idle = Color(0xFF545557),
    diffAdded = Color(0x1F22C55E), diffDeleted = Color(0x1FF25A5A), scrim = Color(0x80000000),
    kindSystem = Color(0xFFADB2B8), kindTools = Color(0xFFA78BFA), kindMessages = Color(0xFF4D93F8),
)

internal fun schemeOf(c: DshColors) = if (c.isDark) darkColorScheme(
    primary = c.brandPrimary, onPrimary = c.labelOnPrimary, primaryContainer = c.buttonPrimaryDimmed, onPrimaryContainer = c.labelPrimary,
    secondary = c.business, onSecondary = c.labelOnPrimary, secondaryContainer = c.businessTertiary, onSecondaryContainer = c.labelPrimary,
    tertiary = Color(0xFF5686FE), background = c.bgBase, onBackground = c.labelPrimary, surface = c.bgBase, onSurface = c.labelPrimary,
    onSurfaceVariant = c.labelSecondary, surfaceDim = c.sidebar, surfaceContainerLowest = c.bgBase, surfaceContainerLow = c.bgLayer1,
    surfaceContainer = c.bgLayer2, surfaceContainerHigh = c.bgLayer3, surfaceContainerHighest = c.bgModule, surfaceVariant = c.hoverSolid,
    inverseSurface = c.toastBg, inverseOnSurface = Color.White, inversePrimary = Color(0xFF4176E6), outline = c.borderL4, outlineVariant = c.borderL2,
    error = c.error, onError = c.labelOnPrimary, errorContainer = Color(0xFF570C0C), onErrorContainer = Color(0xFFFEE2E2), scrim = c.scrim,
    surfaceTint = Color.Transparent,
) else lightColorScheme(
    primary = c.brandPrimary, onPrimary = c.labelOnPrimary, primaryContainer = c.buttonPrimaryDimmed, onPrimaryContainer = c.labelPrimary,
    secondary = c.business, onSecondary = Color.White, secondaryContainer = c.businessTertiary, onSecondaryContainer = Color(0xFF0E3074),
    tertiary = Color(0xFF4176E6), background = c.bgBase, onBackground = c.labelPrimary, surface = c.bgBase, onSurface = c.labelPrimary,
    onSurfaceVariant = c.labelSecondary, surfaceDim = c.sidebar, surfaceContainerLowest = c.bgBase, surfaceContainerLow = c.bgLayer1,
    surfaceContainer = c.bgLayer2, surfaceContainerHigh = c.bgLayer3, surfaceContainerHighest = c.bgModule, surfaceVariant = c.hoverSolid,
    inverseSurface = c.toastBg, inverseOnSurface = Color.White, inversePrimary = Color(0xFF7AAAFF), outline = c.borderL4, outlineVariant = c.borderL2,
    error = c.error, onError = Color.White, errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D), scrim = c.scrim,
    surfaceTint = Color.Transparent,
)

val LocalDshColors = staticCompositionLocalOf { LightDsh }
