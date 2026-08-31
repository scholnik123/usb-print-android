package ru.usbprint.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary = Color(0xFF2457A5), secondary = Color(0xFF4D5F80), tertiary = Color(0xFF006B5D))
private val Dark = darkColorScheme(primary = Color(0xFFB4C5FF), secondary = Color(0xFFC0C7DC), tertiary = Color(0xFF7BDAC7))

@Composable fun UsbPrintTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
