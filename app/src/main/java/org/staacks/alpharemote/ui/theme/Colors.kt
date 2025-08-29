package org.staacks.alpharemote.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color


val Cyan = Color(0xFF0BA6D0)
val MidnightLight = Color(0xF066079)
val Midnight = Color(0xFF054D61)
val Fulvous = Color(0xFE98A15)
val Ochre = Color(0xFFC47412)
val Black = Color(0xF000000)
val Gray10 = Color(0xFF1a1a1a)
val Gray20 = Color(0xF333333)
val Gray30 = Color(0xFF4d4d4d)
val Gray40 = Color(0xF666666)
val Gray50 = Color(0xFF808080)
val Gray60 = Color(0xF999999)
val Gray70 = Color(0xFFb3b3b3)
val Gray80 = Color(0xFcccccc)
val Gray90 = Color(0xFFe6e6e6)
val Gray95 = Color(0xFF2F2F2)
val White = Color(0xFFFFFFFF)

val DarkRed = Color(0x800000)
val LightRed = Color(0xFF8080)
val DarkGreen = Color(0x08000)
val LightGreen = Color(0x80ff80)

val LightColorScheme = lightColorScheme(
    primary = Cyan,
    onPrimary = Black,
    primaryContainer = Midnight, // M3 uses containers; primaryVariant is mapped here
    onPrimaryContainer = White,  // Adjust as needed
    secondary = Fulvous,
    onSecondary = Black,
    secondaryContainer = Fulvous,  // M3 uses containers; secondaryVariant is mapped here
    onSecondaryContainer = White, // Adjust as needed
    tertiary = Fulvous, // Example, adjust if you have a tertiary color
    onTertiary = White,
    error = LightRed,
    onError = White,
)

val DarkColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Black,
    primaryContainer = White, // M3 uses containers; primaryVariant is mapped here
    onPrimaryContainer = White,  // Adjust as needed
    secondary = Fulvous,
    onSecondary = Black,
    secondaryContainer = Fulvous,  // M3 uses containers; secondaryVariant is mapped here
    onSecondaryContainer = White, // Adjust as needed
    tertiary = Fulvous, // Example, adjust if you have a tertiary color
    onTertiary = White,
    error = LightRed,
    onError = White,
)

// Extension properties for custom colors on MaterialTheme.colorScheme
val androidx.compose.material3.ColorScheme.textError: Color
    get() = if (this == LightColorScheme) DarkRed else DarkRed // Or define a different dark error if needed

val androidx.compose.material3.ColorScheme.textConnected: Color
    get() = if (this == LightColorScheme) DarkGreen else DarkGreen // Or define a different dark connected if needed

val androidx.compose.material3.ColorScheme.customButton: Color
    get() = if (this == LightColorScheme) Black else White

val androidx.compose.material3.ColorScheme.customButtonBG: Color
    get() = if (this == LightColorScheme) Gray95 else Color(0xFF2A2A2A)

val androidx.compose.material3.ColorScheme.zoomFocusBG: Color
    get() = if (this == LightColorScheme) Gray95 else Color(0xFF2A2A2A)

