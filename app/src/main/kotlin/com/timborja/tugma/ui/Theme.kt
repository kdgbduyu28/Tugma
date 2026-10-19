package com.timborja.tugma.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F5E54),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EBDF),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFDDA8),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF4A6270),
    tertiaryContainer = Color(0xFFCDE6F6),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
    surfaceContainer = Color(0xFFF1EDE6),
    surfaceContainerHigh = Color(0xFFEBE7E0),
    errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8BD4C5),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFB7EBDF),
    secondary = Color(0xFFF2B84B),
    secondaryContainer = Color(0xFF684300),
    onSecondaryContainer = Color(0xFFFFDDA8),
    tertiary = Color(0xFFB1CAD9),
    tertiaryContainer = Color(0xFF334A57),
    background = Color(0xFF121414),
    surface = Color(0xFF121414),
    surfaceContainer = Color(0xFF1C1F1E),
    surfaceContainerHigh = Color(0xFF262A28),
)

@Composable
fun TugmaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
