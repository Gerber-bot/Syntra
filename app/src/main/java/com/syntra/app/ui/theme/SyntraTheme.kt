package com.syntra.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Syntra Neural UI Design Tokens & Theme.
 * Inspired by official reference: AMOLED True Black + Deep Teal Surfaces + Neon Mint Accents.
 */
object SyntraColors {
    val BackgroundBlack = Color(0xFF000000)
    val BackgroundDark = Color(0xFF080D0E)
    val SurfaceDark = Color(0xFF121A1C)
    val SurfaceCard = Color(0xFF162224)
    val SurfaceCardElevated = Color(0xFF1C292B)
    val SurfaceHighlight = Color(0xFF223336)

    // Mint & Neon accents
    val NeonMint = Color(0xFF2FFFD0)
    val PrimaryMint = Color(0xFF00BFA6)
    val SoftMint = Color(0xFF75D9C1)
    val DarkMint = Color(0xFF0A3D36)

    // Neutral text & borders
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA0B0B2)
    val TextMuted = Color(0xFF607072)
    val BorderSubtle = Color(0xFF243335)
    val BorderGlow = Color(0x552FFFD0)

    val Error = Color(0xFFFF5252)
    val Success = Color(0xFF2FFFD0)
}

object SyntraBrushes {
    val primaryButton = Brush.horizontalGradient(
        listOf(SyntraColors.NeonMint, SyntraColors.PrimaryMint)
    )

    val cardBackground = Brush.verticalGradient(
        listOf(SyntraColors.SurfaceCardElevated, SyntraColors.SurfaceCard)
    )

    val cardBackgroundDeep = Brush.verticalGradient(
        listOf(SyntraColors.SurfaceCard, SyntraColors.SurfaceDark)
    )

    val glowingBorder = Brush.linearGradient(
        listOf(SyntraColors.NeonMint.copy(alpha = 0.5f), SyntraColors.BorderSubtle)
    )

    val orbRadial = Brush.radialGradient(
        listOf(SyntraColors.NeonMint, SyntraColors.PrimaryMint, SyntraColors.DarkMint)
    )
}

private val SyntraDarkColorScheme = darkColorScheme(
    primary = SyntraColors.NeonMint,
    onPrimary = Color(0xFF05100E),
    primaryContainer = SyntraColors.DarkMint,
    onPrimaryContainer = SyntraColors.SoftMint,
    secondary = SyntraColors.PrimaryMint,
    onSecondary = Color(0xFF05100E),
    background = SyntraColors.BackgroundBlack,
    onBackground = SyntraColors.TextPrimary,
    surface = SyntraColors.SurfaceDark,
    onSurface = SyntraColors.TextPrimary,
    surfaceVariant = SyntraColors.SurfaceCard,
    onSurfaceVariant = SyntraColors.TextSecondary,
    outline = SyntraColors.BorderSubtle,
    error = SyntraColors.Error,
    onError = Color.White
)

@Composable
fun SyntraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SyntraDarkColorScheme,
        content = content
    )
}

/**
 * Modern rounded elevated card with AMOLED depth and subtle or glowing borders.
 */
@Composable
fun SyntraCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    glowingBorder: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderStroke = BorderStroke(
        width = 1.dp,
        color = if (glowingBorder) SyntraColors.BorderGlow else SyntraColors.BorderSubtle
    )

    val baseModifier = modifier
        .clip(shape)
        .background(brush = SyntraBrushes.cardBackground)
        .border(borderStroke, shape)
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    Column(
        modifier = baseModifier.padding(16.dp),
        content = content
    )
}

/**
 * Primary Pill-shaped Button with glowing mint gradient.
 */
@Composable
fun SyntraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFF05100E),
            disabledContainerColor = SyntraColors.SurfaceHighlight,
            disabledContentColor = SyntraColors.TextMuted
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(if (enabled) SyntraBrushes.primaryButton else Brush.horizontalGradient(listOf(SyntraColors.SurfaceHighlight, SyntraColors.SurfaceHighlight)))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (enabled) Color(0xFF05100E) else SyntraColors.TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Secondary Pill-shaped Button for neutral/secondary actions.
 */
@Composable
fun SyntraSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, SyntraColors.BorderSubtle),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = SyntraColors.SurfaceCardElevated,
            contentColor = SyntraColors.TextPrimary,
            disabledContainerColor = SyntraColors.SurfaceDark,
            disabledContentColor = SyntraColors.TextMuted
        ),
        modifier = modifier.height(48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (enabled) SyntraColors.TextPrimary else SyntraColors.TextMuted,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Filter Chip pill (e.g. Todo, Push, Pull, Piernas).
 */
@Composable
fun SyntraFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val shape = CircleShape
    val containerColor = if (selected) SyntraColors.NeonMint else SyntraColors.SurfaceCardElevated
    val textColor = if (selected) Color(0xFF05100E) else SyntraColors.TextSecondary
    val borderStroke = if (selected) null else BorderStroke(1.dp, SyntraColors.BorderSubtle)

    Box(
        modifier = modifier
            .clip(shape)
            .then(if (borderStroke != null) Modifier.border(borderStroke, shape) else Modifier)
            .background(containerColor)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * Small pill badge for status, timestamps or chips (e.g. "Ayer", "Hoy", "● Modelo activo").
 */
@Composable
fun SyntraBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = SyntraColors.SurfaceHighlight,
    contentColor: Color = SyntraColors.TextSecondary
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Glowing IrisX bioluminescent sphere/orb avatar.
 */
@Composable
fun IrisXOrb(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    glowing: Boolean = true
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            val center = this.center

            // Outer soft glow ring
            if (glowing) {
                drawCircle(
                    color = SyntraColors.NeonMint.copy(alpha = 0.25f),
                    radius = radius,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Core sphere with radial gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        SyntraColors.NeonMint,
                        SyntraColors.PrimaryMint,
                        SyntraColors.SurfaceDark
                    ),
                    center = center,
                    radius = radius * 0.9f
                ),
                radius = radius * 0.85f
            )

            // Inner highlight arc
            drawCircle(
                color = Color.White.copy(alpha = 0.45f),
                radius = radius * 0.5f,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}
