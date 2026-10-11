package com.nothingnesscore.bootpatcher.ui.theme

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass surface primitives.
 *
 * Stock Material 3 components (Card, NavigationBar, TopAppBar) are deliberately
 * avoided: MD3 surfaces are opaque and flat, while the intended look is
 * frosted, layered and translucent. These build that from a real backdrop blur,
 * a translucent tint and a specular rim light.
 */

val GlassShape: Shape = RoundedCornerShape(28.dp)
val GlassShapeSmall: Shape = RoundedCornerShape(18.dp)

/**
 * A frosted glass panel.
 *
 * @param blurRadius backdrop blur. Requires API 31; below that it degrades to
 *   translucency alone rather than pretending to blur.
 * @param tint extra colour mixed over the glass.
 * @param dim 0..1 multiplier used to fade panels without hiding them.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    blurRadius: Dp = 0.dp,
    tint: Color = Color.Transparent,
    dim: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val radius = if (supportsBlur) blurRadius else 0.dp

    val surface = MaterialTheme.colorScheme.surface
    val variant = MaterialTheme.colorScheme.surfaceVariant

Box(modifier.clip(shape)) {
        // Frosted backdrop: a blurred, translucent fill drawn *behind* the
        // content. The blur must not wrap the content itself, otherwise the
        // text is blurred too and becomes unreadable.
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (radius.value > 0f && supportsBlur) {
                        // Backed by android.graphics.RenderEffect, and a no-op
                        // below API 31; below that this degrades to plain
                        // translucency rather than pretending to blur.
                        Modifier.blur(radius)
                    } else Modifier
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            surface.copy(alpha = 0.60f * dim),
                            variant.copy(alpha = 0.34f * dim),
                        )
                    )
                )
                .background(tint.copy(alpha = tint.alpha * dim))
        )

        // Specular rim, then the sharp content on top.
        Box(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val rim = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.50f * dim),
                            Color.White.copy(alpha = 0.10f * dim),
                            Color.Transparent,
                        ),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(
                            size.width * 0.9f, size.height * 0.6f
                        ),
                    )
                    onDrawBehind {
                        drawRoundRect(brush = rim, style = Stroke(width = 1.2.dp.toPx()))
                    }
                }
        )

        Box(Modifier) { content() }
    }
}

/** Floating, inset glass navigation bar. */
@Composable
fun GlassNavigationBar(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(100.dp),
        blurRadius = blurRadius,
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { content() }
    }
}

/** Pill action button with a gradient fill and a soft rim. */
@Composable
fun GlassButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dim by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.40f,
        animationSpec = tween(180),
        label = "buttonDim",
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        scheme.primary.copy(alpha = dim),
                        scheme.primary.copy(alpha = dim * 0.70f),
                    )
                )
            )
            .drawWithCache {
                onDrawBehind {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.20f * dim),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = 22.dp, vertical = 13.dp)) { content() }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE6FF),
    onPrimaryContainer = Color(0xFF00164D),
    secondary = Color(0xFF00B8A6),
    surface = Color(0xFFF6F8FF),
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFE3E8F7),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E6F),
    primaryContainer = Color(0xFF0043A6),
    onPrimaryContainer = Color(0xFFDBE6FF),
    secondary = Color(0xFF5FE0CE),
    surface = Color(0xFF0E1118),
    onSurface = Color(0xFFE4E2EA),
    surfaceVariant = Color(0xFF2A3040),
    onSurfaceVariant = Color(0xFFC4C6D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun BootPatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val supportsMonet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val colors: ColorScheme = when {
        useDynamicColor && supportsMonet && darkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && supportsMonet -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}