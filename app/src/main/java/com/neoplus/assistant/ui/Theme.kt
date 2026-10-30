package com.neoplus.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

object Neo {
    val Navy = Color(0xFF050B1A)
    val Panel = Color(0xFF0B1630)
    val Cyan = Color(0xFF22D3EE)
    val Blue = Color(0xFF3B82F6)
    val Purple = Color(0xFFA855F7)
}

@Composable
fun NeoTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(
        primary = Neo.Cyan, secondary = Neo.Purple, tertiary = Neo.Blue,
        background = Neo.Navy, surface = Neo.Panel, onBackground = Color(0xFFE6F1FF), onSurface = Color(0xFFE6F1FF),
    ) else lightColorScheme(
        primary = Color(0xFF0E7490), secondary = Color(0xFF7E22CE), tertiary = Color(0xFF1D4ED8),
        background = Color(0xFFF3F7FC), surface = Color.White,
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
