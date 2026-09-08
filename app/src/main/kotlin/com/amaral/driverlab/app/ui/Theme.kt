package com.amaral.driverlab.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A cool, low-chroma palette. The screen is on during a run and its brightness is
// one of the things the protocol asks the user to hold fixed, so the theme avoids
// large bright areas that would change the thermal picture between runs.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1B9),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141),
    onPrimaryContainer = Color(0xFF9BEDD4),
    secondary = Color(0xFFB1CCC3),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDDE4E0),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDDE4E0),
    surfaceVariant = Color(0xFF3B4A45),
    onSurfaceVariant = Color(0xFFBBCBC4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B57),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9BEDD4),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A635B),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
)

@Composable
fun AmaralTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
