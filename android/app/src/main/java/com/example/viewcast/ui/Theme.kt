package com.example.viewcast.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette issue des maquettes (thème sombre « obsidienne » + corail). */
object Vc {
    val Surface = Color(0xFF0E1320)
    val SurfaceLow = Color(0xFF161B28)
    val SurfaceContainer = Color(0xFF1A1F2D)
    val SurfaceHigh = Color(0xFF252A37)
    val SurfaceHighest = Color(0xFF303443)
    val SurfaceBright = Color(0xFF343947)
    val OnSurface = Color(0xFFDEE2F4)
    val OnSurfaceVariant = Color(0xFFE1BFB8)
    val Primary = Color(0xFFFFB4A3)
    val PrimaryContainer = Color(0xFFFF6B4A)
    val OnPrimary = Color(0xFF630F00)
    val Secondary = Color(0xFFFFB865)
    val Tertiary = Color(0xFF45DFA4)
    val TertiaryContainer = Color(0xFF00AF7B)
    val Error = Color(0xFFFFB4AB)
    val ErrorContainer = Color(0xFF93000A)
    val Outline = Color(0xFFA88A83)
    val OutlineVariant = Color(0xFF59413C)
}

@Composable
fun ViewCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Vc.Primary,
            onPrimary = Vc.OnPrimary,
            primaryContainer = Vc.PrimaryContainer,
            onPrimaryContainer = Color(0xFF661000),
            secondary = Vc.Secondary,
            tertiary = Vc.Tertiary,
            background = Vc.Surface,
            onBackground = Vc.OnSurface,
            surface = Vc.Surface,
            onSurface = Vc.OnSurface,
            surfaceVariant = Vc.SurfaceHighest,
            onSurfaceVariant = Vc.OnSurfaceVariant,
            error = Vc.Error,
            errorContainer = Vc.ErrorContainer,
            onErrorContainer = Color(0xFFFFDAD6),
            outline = Vc.Outline,
            outlineVariant = Vc.OutlineVariant
        ),
        content = content
    )
}
