package com.yangz3720.simplywork.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * SimplyWork 视觉基调（UI 阶段设计计划）：纸面质感 + 墨青单强调色。
 * light: 纸白纸面 / 墨色正文 / 墨青 accent；dark: 玄黑 + 米白 + 浅青。
 */

// ----- Light -----
val PaperLight = Color(0xFFF7F6F2)      // 背景：暖纸白
val SurfaceLight = Color(0xFFFDFCFA)    // 卡片 / 输入条
val InkLight = Color(0xFF1F2226)        // 主文本：墨
val MistLight = Color(0xFF6E737B)       // 次要文本：雾灰
val AccentLight = Color(0xFF2E6B5F)     // 强调：墨青
val AccentContainerLight = Color(0xFFDFE9E5)
val OnAccentContainerLight = Color(0xFF17352E)
val ErrorLight = Color(0xFFB3432B)
val OutlineLight = Color(0xFFDDDBD4)

// ----- Dark -----
val PaperDark = Color(0xFF15171A)
val SurfaceDark = Color(0xFF1D2024)
val InkDark = Color(0xFFE8E6E0)
val MistDark = Color(0xFF9CA1A9)
val AccentDark = Color(0xFF8CC0AF)
val AccentContainerDark = Color(0xFF24382F)
val OnAccentContainerDark = Color(0xFFC4E0D4)
val ErrorDark = Color(0xFFE28B72)
val OutlineDark = Color(0xFF2C3036)