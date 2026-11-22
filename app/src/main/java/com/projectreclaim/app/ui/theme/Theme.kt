package com.projectreclaim.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF185C50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEDE5),
    onPrimaryContainer = Color(0xFF123E35),
    secondary = Color(0xFF5966A5),
    background = Color(0xFFF5F6F2),
    onBackground = Color(0xFF1D302B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D302B),
    surfaceVariant = Color(0xFFE9EDE7),
    onSurfaceVariant = Color(0xFF52615B),
    outlineVariant = Color(0xFFDCE3DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5D7C4),
    onPrimary = Color(0xFF103C31),
    primaryContainer = Color(0xFF244D40),
    onPrimaryContainer = Color(0xFFDDEDE5),
    secondary = Color(0xFFB7C2FF),
    background = Color(0xFF111A17),
    onBackground = Color(0xFFE2EBE4),
    surface = Color(0xFF1A2621),
    onSurface = Color(0xFFE2EBE4),
    surfaceVariant = Color(0xFF293830),
    onSurfaceVariant = Color(0xFFB3C3B9),
    outlineVariant = Color(0xFF394B40),
)

@Composable
fun ProjectReclaimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
