package com.arisu.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Palette: dusk violet + warm gold accent, tuned for a cheerful maid companion.
private val Violet = Color(0xFFB388FF)
private val VioletDeep = Color(0xFF7C4DFF)
private val Gold = Color(0xFFFFD166)
private val InkDark = Color(0xFF170F24)
private val SurfaceDark = Color(0xFF241A36)
private val SurfaceDarkHigh = Color(0xFF31244A)
private val CreamLight = Color(0xFFFDF8F3)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceLightAlt = Color(0xFFF3EAF7)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = InkDark,
    secondary = Gold,
    onSecondary = InkDark,
    tertiary = Color(0xFF9CE6D2),
    background = InkDark,
    onBackground = Color(0xFFEFE8FA),
    surface = SurfaceDark,
    onSurface = Color(0xFFEFE8FA),
    surfaceVariant = SurfaceDarkHigh,
    onSurfaceVariant = Color(0xFFCBBFE0),
    outline = Color(0xFF5B4A7A),
    error = Color(0xFFFF8A9E),
)

private val LightColors = lightColorScheme(
    primary = VioletDeep,
    onPrimary = Color.White,
    secondary = Color(0xFFE8A020),
    onSecondary = InkDark,
    tertiary = Color(0xFF2E9E82),
    background = CreamLight,
    onBackground = Color(0xFF2A2137),
    surface = SurfaceLight,
    onSurface = Color(0xFF2A2137),
    surfaceVariant = SurfaceLightAlt,
    onSurfaceVariant = Color(0xFF5D5470),
    outline = Color(0xFFB9A9D6),
    error = Color(0xFFC62850),
)

val PanelShape = RoundedCornerShape(24.dp)
val BubbleShape = RoundedCornerShape(18.dp)

@Composable
fun ArisuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
