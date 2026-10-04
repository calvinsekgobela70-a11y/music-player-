package com.ceecept.music.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Colours pulled out of a piece of album artwork.
 *
 * Deliberately hand-rolled rather than pulled from a palette library: the app ships no
 * network code and as few dependencies as possible, and the job is small. The bitmap is
 * scaled to 32×32, every pixel is dropped into a coarse RGB bucket weighted by how
 * colourful and how well-exposed it is, and the busiest buckets that are far enough
 * apart in hue become the palette.
 */
data class ArtworkPalette(
    val colors: List<Color>,
    val dominant: Color,
    val isDark: Boolean
) {
    companion object {
        val Default = ArtworkPalette(
            colors = listOf(
                Color(0xFF7B3CFF), Color(0xFFFF3D91), Color(0xFF1EA7FF), Color(0xFFFF8A22)
            ),
            dominant = Color(0xFF5130B8),
            isDark = true
        )

        fun from(bitmap: Bitmap?): ArtworkPalette {
            if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return Default
            return try {
                val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                val buckets = HashMap<Int, FloatArray>() // key -> [weight, r, g, b]
                var luminanceSum = 0f
                var count = 0
                for (y in 0 until small.height) {
                    for (x in 0 until small.width) {
                        val p = small.getPixel(x, y)
                        val r = (p shr 16 and 0xFF) / 255f
                        val g = (p shr 8 and 0xFF) / 255f
                        val b = (p and 0xFF) / 255f
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        val sat = if (mx <= 0.001f) 0f else (mx - mn) / mx
                        val lum = 0.299f * r + 0.587f * g + 0.114f * b
                        luminanceSum += lum
                        count++
                        // Prefer colourful, well-exposed pixels; ignore near-black and
                        // near-white, which carry no usable hue.
                        val weight = sat * (1f - abs(lum - 0.55f) * 1.4f).coerceAtLeast(0.05f)
                        if (weight <= 0.02f) continue
                        val key = ((r * 5).toInt() shl 8) or ((g * 5).toInt() shl 4) or (b * 5).toInt()
                        val slot = buckets.getOrPut(key) { FloatArray(4) }
                        slot[0] += weight
                        slot[1] += r * weight
                        slot[2] += g * weight
                        slot[3] += b * weight
                    }
                }
                if (small !== bitmap) small.recycle()
                val ranked = buckets.values
                    .filter { it[0] > 0.1f }
                    .sortedByDescending { it[0] }
                    .map { Color(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }

                val picked = mutableListOf<Color>()
                for (c in ranked) {
                    if (picked.size >= 4) break
                    if (picked.none { distance(it, c) < 0.22f }) picked.add(c)
                }
                while (picked.size < 4 && ranked.isNotEmpty()) picked.add(ranked[picked.size % ranked.size])
                if (picked.isEmpty()) return Default

                val avgLum = if (count > 0) luminanceSum / count else 0.3f
                ArtworkPalette(
                    colors = picked.map { it.vibrant() },
                    dominant = picked.first().vibrant(0.92f),
                    isDark = avgLum < 0.55f
                )
            } catch (e: Exception) {
                Default
            }
        }

        private fun distance(a: Color, b: Color): Float {
            val dr = a.red - b.red
            val dg = a.green - b.green
            val db = a.blue - b.blue
            return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
        }

        /** Push artwork colours towards an Apple Music-style luminous, saturated wash. */
        private fun Color.vibrant(gain: Float = 1.20f): Color {
            val avg = (red + green + blue) / 3f
            val sat = 1.72f
            return Color(
                red = ((avg + (red - avg) * sat) * gain + 0.035f).coerceIn(0f, 1f),
                green = ((avg + (green - avg) * sat) * gain + 0.035f).coerceIn(0f, 1f),
                blue = ((avg + (blue - avg) * sat) * gain + 0.035f).coerceIn(0f, 1f),
                alpha = 1f
            )
        }
    }
}

/**
 * The now-playing backdrop: four large, soft colour fields drifting slowly over each
 * other, taken from the album artwork — the effect Apple Music uses behind its player.
 *
 * Each field is a radial gradient with a long, soft falloff, so no blur pass is needed
 * (and none is available below Android 12 anyway). The motion is one shared clock
 * driving four Lissajous paths at mutually prime rates, which never visibly repeats.
 */
@Composable
fun ArtworkBackdrop(
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    intensity: Float = 1f
) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val colors = remember(palette) {
        if (palette.colors.size >= 4) palette.colors
        else List(4) { palette.colors.getOrElse(it) { palette.dominant } }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val t = phase * 2f * Math.PI.toFloat()

        // Base wash so the corners are never empty.
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    colors[0].copy(alpha = 1.00f),
                    palette.dominant.copy(alpha = 1.00f),
                    colors.getOrElse(2) { palette.dominant }.copy(alpha = 1.00f)
                )
            ),
            size = Size(w, h)
        )

        val rates = floatArrayOf(1.18f, 0.94f, 1.57f, 0.68f)
        val phases = floatArrayOf(0f, 1.7f, 3.1f, 4.6f)
        for (i in 0 until 4) {
            val cx = w * (0.5f + 0.42f * sin(t * rates[i] + phases[i]))
            val cy = h * (0.5f + 0.40f * cos(t * rates[i] * 0.83f + phases[i] * 1.3f))
            val radius = (max(w, h) * (0.55f + 0.12f * sin(t * rates[i] * 1.7f)))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors[i].copy(alpha = 1.00f * intensity),
                        colors[i].copy(alpha = 0.54f * intensity),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        // MilkDrop-inspired sparkle pulses from the uploaded app reference: cheap radial
        // glows only, so Huawei P40 Lite stays smooth while the screen feels livelier.
        for (i in 0 until 5) {
            val local = t * (1.65f + i * 0.21f) + phases[i % phases.size]
            val cx = w * (0.5f + 0.48f * sin(local * 0.73f + i))
            val cy = h * (0.5f + 0.46f * cos(local * 0.91f + i * 1.7f))
            val pulse = 0.5f + 0.5f * sin(local * 1.9f)
            val radius = max(w, h) * (0.16f + 0.08f * pulse)
            val color = colors[(i + 1) % colors.size]
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f * pulse * intensity),
                        color.copy(alpha = 0.24f * pulse * intensity),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        // Legibility scrim: darker at the bottom where the controls live.
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = 0.04f),
                    Color.Black.copy(alpha = 0.10f),
                    Color.Black.copy(alpha = 0.40f)
                )
            ),
            size = Size(w, h)
        )
    }
}
