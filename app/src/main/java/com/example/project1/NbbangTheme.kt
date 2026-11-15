package com.example.project1

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 차 화면(Pleos)과 어울리는 밝은 톤 + 포인트 파란색 하나
val Accent = Color(0xFF2563EB)
val AccentSoft = Color(0xFFE8EFFE)
val Bg = Color(0xFFF5F6F8)
val CardWhite = Color(0xFFFFFFFF)
val Ink = Color(0xFF111827)
val Muted = Color(0xFF6B7280)
val Line = Color(0xFFE5E7EB)
val Warn = Color(0xFFB45309)
val WarnSoft = Color(0xFFFEF3C7)

@Composable
fun NbbangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            surface = CardWhite,
            onSurface = Ink,
        ),
        content = content
    )
}
