package com.vyro.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val VyroDarkColorScheme = darkColorScheme(
    primary = VyroColors.ElectricCyan,
    onPrimary = VyroColors.Obsidian,
    primaryContainer = VyroColors.SlateControl,
    onPrimaryContainer = VyroColors.ElectricCyan,
    secondary = VyroColors.StudioAmber,
    onSecondary = VyroColors.Obsidian,
    secondaryContainer = VyroColors.SlateElevated,
    onSecondaryContainer = VyroColors.StudioAmber,
    tertiary = VyroColors.TextViolet,
    onTertiary = VyroColors.TextPrimary,
    background = VyroColors.Obsidian,
    onBackground = VyroColors.TextPrimary,
    surface = VyroColors.SlatePanel,
    onSurface = VyroColors.TextPrimary,
    surfaceVariant = VyroColors.SlateControl,
    onSurfaceVariant = VyroColors.TextSecondary,
    outline = VyroColors.SteelDivider,
    outlineVariant = VyroColors.SteelMuted,
    error = VyroColors.CoralError,
    onError = VyroColors.Obsidian,
)

val VyroShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun VyroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VyroDarkColorScheme,
        typography = VyroTypography,
        shapes = VyroShapes,
        content = content,
    )
}

@Composable
fun VyroBadge(
    label: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun VyroStudioPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp,
    ) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            content()
        }
    }
}

object VyroDesignAssets {
    val studioReelDrawableRes: Int get() = R.drawable.img_studio_reel
}
