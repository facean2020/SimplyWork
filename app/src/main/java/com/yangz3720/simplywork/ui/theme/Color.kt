package com.yangz3720.simplywork.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * SimplyWork visual foundation (UI-stage design plan): paper texture + a single ink-teal accent.
 * light: paper-white surface / ink body text / ink-teal accent; dark: near-black + off-white + pale teal.
 */

// ----- Light -----
val PaperLight = Color(0xFFF7F6F2)      // background: warm paper white
val SurfaceLight = Color(0xFFFDFCFA)    // cards / input fields
val InkLight = Color(0xFF1F2226)        // primary text: ink
val MistLight = Color(0xFF6E737B)       // secondary text: mist gray
val AccentLight = Color(0xFF2E6B5F)     // accent: ink teal
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