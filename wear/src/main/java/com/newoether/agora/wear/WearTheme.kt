package com.newoether.agora.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Hermes brand, in Wear Material 3 colour roles.
 *
 * Same identity as the phone app (forest `#123E25`, terracotta `#C85C32`, cream `#FCFAF7`) mapped
 * onto the M3 slots the components actually read. Dark-first because this is an OLED watch: a
 * light-background app on a watch is a battery decision, and the wrong one.
 *
 * Only the roles that are used are overridden; everything else falls through to
 * `MaterialTheme.colorScheme`'s defaults by starting from a copy of the default scheme.
 */
@Composable
fun WearHermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = hermesColorScheme(),
        content = content,
    )
}

@Composable
private fun hermesColorScheme(): ColorScheme {
    val base = MaterialTheme.colorScheme
    return base.copy(
        primary = Color(0xFF7FC49B),
        onPrimary = Color(0xFF0B2A18),
        primaryContainer = Color(0xFF1E5133),
        onPrimaryContainer = Color(0xFFD8F3E2),
        secondary = Color(0xFFE8A184),
        onSecondary = Color(0xFF3A1608),
        secondaryContainer = Color(0xFF6B3520),
        onSecondaryContainer = Color(0xFFFFE2D6),
        background = Color(0xFF0D0F0E),
        onBackground = Color(0xFFE9EDEA),
        onSurface = Color(0xFFE9EDEA),
        surfaceContainerLow = Color(0xFF171B19),
        surfaceContainer = Color(0xFF1C211E),
        surfaceContainerHigh = Color(0xFF232925),
        onSurfaceVariant = Color(0xFFB6C0B9),
    )
}
