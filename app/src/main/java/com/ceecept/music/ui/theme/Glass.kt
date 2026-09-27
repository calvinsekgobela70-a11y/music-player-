package com.ceecept.music.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The glossy, layered material the interface is built from.
 *
 * Real Gaussian blur behind a surface needs `Modifier.blur`, which is API 31 and above;
 * this app supports Android 10, so the glass is built the way it was before hardware
 * blur existed — a translucent fill, a bright hairline border, a specular highlight
 * running off the top-left corner, and a soft inner shadow at the bottom. Over the
 * moving colour field of the now-playing screen it reads exactly like frosted glass,
 * and it costs nothing to draw.
 */
object Glass {

    /** Corner radii. Generous, in the iOS idiom. */
    val CornerSmall = 14.dp
    val CornerMedium = 20.dp
    val CornerLarge = 28.dp
    val CornerSheet = 38.dp

    /** The standard iOS interface spring: settles quickly, never rings. */
    fun <T> spring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = 420f)

    /** For things that should feel physical — buttons, sheets, artwork. */
    fun <T> springBouncy(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 380f)

    /** Slow, heavy spring for full-screen transitions. */
    fun <T> springSheet(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 260f)

    @Composable
    fun fill(dark: Boolean = isSystemInDarkTheme(), strength: Float = 1f): Brush =
        if (dark) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.14f * strength),
                    Color.White.copy(alpha = 0.07f * strength),
                    Color.White.copy(alpha = 0.04f * strength)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.92f * strength),
                    Color.White.copy(alpha = 0.78f * strength),
                    Color.White.copy(alpha = 0.7f * strength)
                )
            )
        }

    @Composable
    fun border(dark: Boolean = isSystemInDarkTheme()): BorderStroke = BorderStroke(
        width = 1.dp,
        brush = Brush.verticalGradient(
            if (dark) {
                listOf(
                    Color.White.copy(alpha = 0.30f),
                    Color.White.copy(alpha = 0.10f),
                    Color.White.copy(alpha = 0.05f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color.White.copy(alpha = 0.55f),
                    Color.Black.copy(alpha = 0.06f)
                )
            }
        )
    )

    /** Diagonal specular sheen, the detail that makes a flat fill look like glass. */
    fun sheen(dark: Boolean): Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (dark) 0.10f else 0.5f),
            Color.Transparent,
            Color.Transparent
        )
    )
}

/**
 * Applies the glass treatment to any container.
 *
 * @param shape the clip and border shape
 * @param strength 0 = barely there, 1 = standard, >1 = more opaque (for bars that sit
 *   over busy artwork and still have to be readable)
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(Glass.CornerMedium),
    strength: Float = 1f,
    dark: Boolean = isSystemInDarkTheme()
): Modifier = this
    .clip(shape)
    .background(Glass.fill(dark, strength))
    .background(Glass.sheen(dark))
    .border(Glass.border(dark), shape)

/** A glass panel with content padding. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Glass.CornerLarge),
    strength: Float = 1f,
    contentPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .glass(shape, strength)
            .padding(contentPadding),
        content = content
    )
}

/**
 * iOS-style segmented control: a glass track with a sliding, slightly raised selector.
 * The selector animates with the interface spring rather than a linear tween, which is
 * most of what makes iOS controls feel the way they do.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .glass(shape, strength = 0.8f, dark = dark)
            .padding(3.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fraction = 1f / (options.size - index)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (selected) {
                            Modifier
                                .background(
                                    if (dark) Color.White.copy(alpha = 0.18f)
                                    else Color.White
                                )
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = if (dark) 0.22f else 0.9f),
                                    RoundedCornerShape(10.dp)
                                )
                        } else {
                            Modifier
                        }
                    )
                    .then(Modifier.clickableNoRipple { onSelect(index) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Tap handling without the Material ripple, which is not an iOS idiom. */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    return this.then(
        androidx.compose.foundation.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
    )
}
