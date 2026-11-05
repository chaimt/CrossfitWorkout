package com.wodcapture.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wodcapture.domain.model.WorkoutSection

/*
 * WOD Capture look: warm orange for the metcon (and the brand), steel blue for weights, neutral
 * grey surfaces. The two section colours are the same everywhere (library badges, builder and
 * review headers) so the athlete reads "blue = lifting, orange = conditioning" at a glance.
 */

private val Light = lightColorScheme(
    primary = Color(0xFFC2410C), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCCB), onPrimaryContainer = Color(0xFF3A0E00),
    secondary = Color(0xFF1D4ED8), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4FF), onSecondaryContainer = Color(0xFF00174D),
    tertiary = Color(0xFF0F766E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDF3EC), onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F7F8), onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFF7F7F8), onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFE6E7EB), onSurfaceVariant = Color(0xFF4A4D55),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F1F3), surfaceContainerHigh = Color(0xFFEAEBEE),
    surfaceContainerHighest = Color(0xFFE3E4E8),
    outline = Color(0xFF7A7D86), outlineVariant = Color(0xFFD3D5DB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF9666), onPrimary = Color(0xFF4F1600),
    primaryContainer = Color(0xFF7A2A06), onPrimaryContainer = Color(0xFFFFDCCB),
    secondary = Color(0xFFA9BEFF), onSecondary = Color(0xFF00297A),
    secondaryContainer = Color(0xFF1E3A8A), onSecondaryContainer = Color(0xFFDCE4FF),
    tertiary = Color(0xFF7FD8CB), onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF115049), onTertiaryContainer = Color(0xFFCDF3EC),
    background = Color(0xFF111215), onBackground = Color(0xFFE4E5E9),
    surface = Color(0xFF111215), onSurface = Color(0xFFE4E5E9),
    surfaceVariant = Color(0xFF2E3036), onSurfaceVariant = Color(0xFFC3C5CC),
    surfaceContainerLowest = Color(0xFF0C0D0F), surfaceContainerLow = Color(0xFF1A1B1F),
    surfaceContainer = Color(0xFF1E2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8D9099), outlineVariant = Color(0xFF3F4248),
)

private val WodTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val WodShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun WodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = WodTypography,
        shapes = WodShapes,
    ) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** Accent (strong) and container (soft) colours of a workout section. */
data class SectionColors(val accent: Color, val onAccent: Color, val container: Color, val onContainer: Color)

@Composable
@ReadOnlyComposable
fun sectionColors(section: WorkoutSection): SectionColors {
    val c: ColorScheme = MaterialTheme.colorScheme
    return when (section) {
        WorkoutSection.WEIGHTS -> SectionColors(c.secondary, c.onSecondary, c.secondaryContainer, c.onSecondaryContainer)
        WorkoutSection.METCON -> SectionColors(c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer)
        WorkoutSection.OTHER -> SectionColors(c.tertiary, c.onTertiary, c.tertiaryContainer, c.onTertiaryContainer)
    }
}
