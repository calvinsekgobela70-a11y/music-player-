package com.ceecept.music.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ceecept.music.R
import com.ceecept.music.ThemeMode

/**
 * Ceecept internal design system.
 * High contrast, intentional hierarchy (display / headline / body / caption),
 * functional layouts, one accent family, spring-based motion at 60fps.
 */
object CeeceptColors {
    val Accent = Color(0xFFFA2D48)
    val AccentDeep = Color(0xFFD81B36)
    val Violet = Color(0xFF7C5CFF)
    val Teal = Color(0xFF30D158)
    val Amber = Color(0xFFFFB340)

    val DarkBg = Color(0xFF0B0B12)
    val DarkSurface = Color(0xFF14141D)
    val DarkSurfaceHigh = Color(0xFF1E1E2A)
    val DarkOnSurface = Color(0xFFF5F5F7)
    val DarkOnVariant = Color(0xFFA7A7B8)
    val DarkOutline = Color(0xFF2C2C3A)

    val LightBg = Color(0xFFF5F5F7)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEDEDF2)
    val LightOnSurface = Color(0xFF1D1D1F)
    val LightOnVariant = Color(0xFF6E6E73)
    val LightOutline = Color(0xFFE2E2E8)

    fun accentGradient() = Brush.linearGradient(listOf(Accent, Violet))
    fun accentGradientHorizontal() = Brush.horizontalGradient(listOf(AccentDeep, Accent, Violet))
}

private val DarkScheme = darkColorScheme(
    primary = CeeceptColors.Accent,
    onPrimary = Color.White,
    secondary = CeeceptColors.Violet,
    tertiary = CeeceptColors.Teal,
    background = CeeceptColors.DarkBg,
    onBackground = CeeceptColors.DarkOnSurface,
    surface = CeeceptColors.DarkSurface,
    onSurface = CeeceptColors.DarkOnSurface,
    surfaceVariant = CeeceptColors.DarkSurfaceHigh,
    onSurfaceVariant = CeeceptColors.DarkOnVariant,
    surfaceContainer = CeeceptColors.DarkSurface,
    surfaceContainerHigh = CeeceptColors.DarkSurfaceHigh,
    outline = CeeceptColors.DarkOutline,
    outlineVariant = CeeceptColors.DarkOutline
)

private val LightScheme = lightColorScheme(
    primary = CeeceptColors.Accent,
    onPrimary = Color.White,
    secondary = CeeceptColors.Violet,
    tertiary = Color(0xFF1F9D4D),
    background = CeeceptColors.LightBg,
    onBackground = CeeceptColors.LightOnSurface,
    surface = CeeceptColors.LightSurface,
    onSurface = CeeceptColors.LightOnSurface,
    surfaceVariant = CeeceptColors.LightSurfaceHigh,
    onSurfaceVariant = CeeceptColors.LightOnVariant,
    surfaceContainer = CeeceptColors.LightSurface,
    surfaceContainerHigh = CeeceptColors.LightSurfaceHigh,
    outline = CeeceptColors.LightOutline,
    outlineVariant = CeeceptColors.LightOutline
)

/**
 * Inter — a Custom open grotesque in the spirit of San Francisco: highly
 * legible at any size with optical sizing feel via tight large-title tracking.
 * (Apple's SF font is proprietary and cannot be bundled; Inter is metrically
 * and visually close and ships offline inside the APK.)
 */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private val CeeceptTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp
    )
)

private val CeeceptShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp)
)

/** Motion specs — every animation in the app comes from here. */
object CeeceptMotion {
    fun <T> bouncy() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    fun <T> snappy() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> gentle() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> screen() = tween<T>(durationMillis = 320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
}

@Composable
fun CeeceptTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    useCustomFont: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = if (useCustomFont) CeeceptTypography else androidx.compose.material3.Typography(),
        shapes = CeeceptShapes,
        content = content
    )
}
