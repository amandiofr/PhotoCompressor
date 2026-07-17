package com.amandiofr.photocompressor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green700 = Color(0xFF388E3C)
private val Green500 = Color(0xFF4CAF50)
private val Green100 = Color(0xFFC8E6C9)
private val Teal200  = Color(0xFF80CBC4)

private val LightColors = lightColorScheme(
    primary          = Green700,
    onPrimary        = Color.White,
    primaryContainer = Green100,
    secondary        = Teal200,
)

@Composable
fun PhotoCompressorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
