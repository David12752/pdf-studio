package com.pdfsigner.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.pdfsigner.app.domain.prefs.AppTheme

// ?? High-contrast overrides (WCAG AA) ????????????????????????????????????????
private val HcLightColors = lightColorScheme(
    primary            = Color(0xFF000080),
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFD0D8FF),
    onPrimaryContainer = Color(0xFF00006E),
    secondary          = Color(0xFF8B0000),
    onSecondary        = Color.White,
    background         = Color.White,
    onBackground       = Color.Black,
    surface            = Color.White,
    onSurface          = Color.Black,
    surfaceVariant     = Color(0xFFF0F0F0),
    onSurfaceVariant   = Color(0xFF1A1A1A),
    outline            = Color(0xFF404040),
    error              = Color(0xFF8B0000),
    onError            = Color.White,
)

private val HcDarkColors = darkColorScheme(
    primary            = Color(0xFF99B4FF),
    onPrimary          = Color(0xFF00006E),
    primaryContainer   = Color(0xFF000080),
    onPrimaryContainer = Color(0xFFD0D8FF),
    secondary          = Color(0xFFFF8A80),
    onSecondary        = Color.Black,
    background         = Color.Black,
    onBackground       = Color.White,
    surface            = Color(0xFF0A0A0A),
    onSurface          = Color.White,
    surfaceVariant     = Color(0xFF1A1A1A),
    onSurfaceVariant   = Color(0xFFE0E0E0),
    outline            = Color(0xFFA0A0A0),
    error              = Color(0xFFFF6B6B),
    onError            = Color.Black,
)

private val BrandedLightColors = lightColorScheme(
    primary              = Brand700,
    onPrimary            = Color.White,
    primaryContainer     = Brand100,
    onPrimaryContainer   = Brand900,
    secondary            = Accent500,
    onSecondary          = Color.White,
    secondaryContainer   = Accent50,
    onSecondaryContainer = Accent600,
    tertiary             = Brand500,
    onTertiary           = Color.White,
    background           = Surface,
    onBackground         = OnSurface,
    surface              = Surface,
    onSurface            = OnSurface,
    surfaceVariant       = NeutralGray100,
    onSurfaceVariant     = NeutralGray800,
    outline              = Brand300,
    outlineVariant       = NeutralGray200,
    error                = ErrorRed,
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD6),
    onErrorContainer     = Color(0xFF410002),
)

private val BrandedDarkColors = darkColorScheme(
    primary              = Brand300,
    onPrimary            = Brand900,
    primaryContainer     = Brand700,
    onPrimaryContainer   = Brand100,
    secondary            = Accent300,
    onSecondary          = Brand900,
    secondaryContainer   = Accent600.copy(alpha = 0.3f),
    onSecondaryContainer = Accent300,
    tertiary             = Brand100,
    onTertiary           = Brand900,
    background           = SurfaceDark,
    onBackground         = NeutralGray50,
    surface              = SurfaceDark,
    onSurface            = NeutralGray50,
    surfaceVariant       = NeutralGray900,
    onSurfaceVariant     = Brand300,
    outline              = Brand600,
    outlineVariant       = NeutralGray800,
    error                = Color(0xFFFFB4AB),
    onError              = Color(0xFF690005),
)

/** Scale multipliers for the three font-size tiers. */
private val FONT_SCALES = listOf(1f, 1.15f, 1.30f)

@Composable
fun PdfSignerTheme(
    appTheme: AppTheme       = AppTheme.SYSTEM,
    fontScale: Int           = 0,
    highContrast: Boolean    = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme  = when (appTheme) {
        AppTheme.DARK   -> true
        AppTheme.LIGHT  -> false
        AppTheme.SYSTEM -> systemDark
    }

    val colorScheme = when {
        highContrast && !darkTheme -> HcLightColors
        highContrast && darkTheme  -> HcDarkColors
        darkTheme                  -> BrandedDarkColors
        else                       -> BrandedLightColors
    }

    // Apply font scale multiplier to ALL typography levels
    val scale      = FONT_SCALES.getOrElse(fontScale) { 1f }
    val typography = if (scale == 1f) AppTypography else AppTypography.copy(
        displayLarge  = AppTypography.displayLarge.copy(fontSize  = (AppTypography.displayLarge.fontSize.value  * scale).sp),
        displayMedium = AppTypography.displayMedium.copy(fontSize = (AppTypography.displayMedium.fontSize.value * scale).sp),
        headlineLarge = AppTypography.headlineLarge.copy(fontSize = (AppTypography.headlineLarge.fontSize.value * scale).sp),
        headlineMedium= AppTypography.headlineMedium.copy(fontSize= (AppTypography.headlineMedium.fontSize.value* scale).sp),
        headlineSmall = AppTypography.headlineSmall.copy(fontSize = (AppTypography.headlineSmall.fontSize.value * scale).sp),
        titleLarge    = AppTypography.titleLarge.copy(fontSize    = (AppTypography.titleLarge.fontSize.value    * scale).sp),
        titleMedium   = AppTypography.titleMedium.copy(fontSize   = (AppTypography.titleMedium.fontSize.value   * scale).sp),
        titleSmall    = AppTypography.titleSmall.copy(fontSize    = (AppTypography.titleSmall.fontSize.value    * scale).sp),
        bodyLarge     = AppTypography.bodyLarge.copy(fontSize     = (AppTypography.bodyLarge.fontSize.value     * scale).sp),
        bodyMedium    = AppTypography.bodyMedium.copy(fontSize    = (AppTypography.bodyMedium.fontSize.value    * scale).sp),
        bodySmall     = AppTypography.bodySmall.copy(fontSize     = (AppTypography.bodySmall.fontSize.value     * scale).sp),
        labelLarge    = AppTypography.labelLarge.copy(fontSize    = (AppTypography.labelLarge.fontSize.value    * scale).sp),
        labelMedium   = AppTypography.labelMedium.copy(fontSize   = (AppTypography.labelMedium.fontSize.value   * scale).sp),
        labelSmall    = AppTypography.labelSmall.copy(fontSize    = (AppTypography.labelSmall.fontSize.value    * scale).sp),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = typography,
        shapes      = AppShapes,
        content     = content
    )
}
