package com.ceecept.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ceecept.music.audio.SpaceParams
import com.ceecept.music.audio.spatial.Keyframe
import com.ceecept.music.audio.spatial.ObjectTrajectory
import com.ceecept.music.audio.spatial.SpatialBands
import com.ceecept.music.audio.spatial.SpeakerLayout
import com.ceecept.music.ui.theme.CeeceptColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Max distance drawn on the radar, in metres. */
private const val MAX_DISTANCE = 8f

/** Non-linear radius mapping so near distances get most of the space. */
private fun distanceToRadius(distance: Float): Float =
    (ln(1f + distance.coerceIn(0f, MAX_DISTANCE)) / ln(1f + MAX_DISTANCE)).coerceIn(0f, 1f)

private fun radiusToDistance(fraction: Float): Float =
    (exp(fraction.coerceIn(0f, 1f) * ln(1f + MAX_DISTANCE)) - 1f).coerceIn(0.3f, MAX_DISTANCE)

/**
 * Top-down view of the rendered scene: the active speaker rig, the listener, and every
 * audio object the engine is currently placing. Drag anywhere to move the stage
 * (azimuth from the angle, distance from how far out you drag).
 */
@Composable
fun SpatialRadar(
    params: SpaceParams,
    layout: SpeakerLayout,
    binaural: Boolean,
    onChange: (azimuth: Float, distance: Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Live measured azimuth of each separated stream, or null for the band model. */
    streamAzimuths: FloatArray? = null,
    /** Live RMS of each separated stream. */
    streamLevels: FloatArray? = null
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val accent = CeeceptColors.Accent
    val violet = CeeceptColors.Violet
    val amber = CeeceptColors.Amber
    val teal = CeeceptColors.Teal
    val grid = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    // The same trajectory the renderer uses, so the animation is not a mock-up.
    val orbit = remember {
        ObjectTrajectory(
            listOf(
                Keyframe(0.000f, 0f, 0f, 1.00f),
                Keyframe(0.125f, 45f, 6f, 0.86f),
                Keyframe(0.250f, 90f, 10f, 0.78f),
                Keyframe(0.375f, 135f, 6f, 0.86f),
                Keyframe(0.500f, 180f, 0f, 1.00f),
                Keyframe(0.625f, 225f, -6f, 1.18f),
                Keyframe(0.750f, 270f, -10f, 1.26f),
                Keyframe(0.875f, 315f, -6f, 1.18f),
                Keyframe(1.000f, 360f, 0f, 1.00f)
            )
        )
    }
    val orbitOut = remember { FloatArray(3) }
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(params.orbitHz, params.enabled) {
        if (params.orbitHz <= 0.001f || !params.enabled) {
            phase = 0f
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            phase = (phase + params.orbitHz * dt) % 1f
        }
    }

    val orbiting = params.orbitHz > 0.001f && params.enabled
    if (orbiting) orbit.positionAt(phase, orbitOut)
    val orbitAz = if (orbiting) orbitOut[0] else 0f
    val orbitDistScale = if (orbiting) orbitOut[2] else 1f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { sizePx = it }
            .pointerInput(sizePx) {
                detectDragGestures { change, _ -> emit(change.position, sizePx, onChange) }
            }
            .pointerInput(sizePx) {
                detectTapGestures { position -> emit(position, sizePx, onChange) }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(cx, cy) * 0.86f

            // --- distance rings -------------------------------------------------
            for (d in listOf(1f, 2f, 4f, 8f)) {
                drawCircle(
                    color = grid.copy(alpha = if (d == 8f) 0.55f else 0.3f),
                    radius = radius * distanceToRadius(d),
                    center = Offset(cx, cy),
                    style = Stroke(1.dp.toPx())
                )
            }
            drawLine(grid.copy(alpha = 0.25f), Offset(cx, cy - radius), Offset(cx, cy + radius), 1.dp.toPx())
            drawLine(grid.copy(alpha = 0.25f), Offset(cx - radius, cy), Offset(cx + radius, cy), 1.dp.toPx())

            // --- speakers --------------------------------------------------------
            layout.speakers.forEach { speaker ->
                val isHeight = speaker.elevationDeg > 30f
                val r = radius * distanceToRadius(speaker.distanceM)
                val a = speaker.azimuthDeg * (Math.PI.toFloat() / 180f)
                val sx = cx + r * sin(a)
                val sy = cy - r * cos(a)
                when {
                    speaker.isLfe -> drawCircle(
                        color = faint.copy(alpha = 0.35f),
                        radius = 5.dp.toPx(),
                        center = Offset(cx, cy + radius * distanceToRadius(1.2f))
                    )

                    isHeight -> {
                        drawCircle(teal.copy(alpha = 0.22f), 11.dp.toPx(), Offset(sx, sy))
                        drawCircle(teal, 5.dp.toPx(), Offset(sx, sy), style = Stroke(1.5.dp.toPx()))
                    }

                    else -> {
                        drawCircle(faint.copy(alpha = 0.18f), 10.dp.toPx(), Offset(sx, sy))
                        drawCircle(faint, 4.dp.toPx(), Offset(sx, sy))
                    }
                }
            }

            // --- listener --------------------------------------------------------
            val headR = 15.dp.toPx()
            drawCircle(onSurface.copy(alpha = 0.85f), headR, Offset(cx, cy), style = Stroke(2.dp.toPx()))
            drawLine(
                onSurface.copy(alpha = 0.85f),
                Offset(cx, cy - headR), Offset(cx, cy - headR - 9.dp.toPx()),
                3.dp.toPx(), StrokeCap.Round
            )

            if (!params.enabled) return@Canvas

            // --- objects ----------------------------------------------------------
            val sceneAz = params.azimuth + orbitAz
            val sceneDistance = (params.distance * orbitDistScale).coerceIn(0.3f, MAX_DISTANCE)
            val objectRadius = radius * distanceToRadius(sceneDistance)
            val elevationLift = (params.elevation / 90f).coerceIn(-0.5f, 1f)

            // --- separated streams: draw what the analyser actually found ---------
            if (params.stems && streamAzimuths != null && streamLevels != null) {
                for (i in StreamGroups.ORDER.indices) {
                    val g = StreamGroups.ORDER[i]
                    for (s in g.indices) {
                        if (s >= streamAzimuths.size) continue
                        val level = if (s < streamLevels.size) streamLevels[s] else 0f
                        val loud = (kotlin.math.sqrt(level * 6f)).coerceIn(0f, 1f)
                        val dist = (sceneDistance * StreamGroups.DISTANCE[s]).coerceIn(0.3f, MAX_DISTANCE)
                        val rr = radius * distanceToRadius(dist)
                        val a = (params.azimuth + orbitAz + streamAzimuths[s]) * (Math.PI.toFloat() / 180f)
                        val ox = cx + rr * sin(a)
                        val oy = cy - rr * cos(a) -
                            (StreamGroups.ELEVATION[s] / 90f + elevationLift) * 16.dp.toPx()
                        val base = (5f + 7f * loud).dp.toPx()
                        drawCircle(g.color.copy(alpha = 0.18f + 0.3f * loud), base * 2.1f, Offset(ox, oy))
                        drawCircle(g.color.copy(alpha = 0.45f + 0.5f * loud), base, Offset(ox, oy))
                    }
                }
                return@Canvas
            }

            // Per-band pairs: the width of each band is the engine's own table.
            SpatialBands.TABLE.forEachIndexed { index, band ->
                val bandWidth = if (params.multiband) band.spatialWidth else 1f
                val offset = 30f * params.width * bandWidth
                val tint = when {
                    index <= 1 -> violet
                    index <= 3 -> accent
                    else -> amber
                }
                val alpha = if (band.diffuse) 0.30f else 0.62f
                val dotSize = (9f - index * 0.7f).coerceAtLeast(4f)
                listOf(sceneAz - offset, sceneAz + offset).forEach { az ->
                    val a = az * (Math.PI.toFloat() / 180f)
                    val ox = cx + objectRadius * sin(a)
                    val oy = cy - objectRadius * cos(a) - elevationLift * 14.dp.toPx()
                    drawCircle(tint.copy(alpha = alpha * 0.35f), (dotSize + 5f).dp.toPx(), Offset(ox, oy))
                    drawCircle(tint.copy(alpha = alpha), dotSize.dp.toPx(), Offset(ox, oy))
                }
            }

            // Centre (mid) object: the vocal/bass anchor.
            val ca = sceneAz * (Math.PI.toFloat() / 180f)
            val mx = cx + objectRadius * sin(ca)
            val my = cy - objectRadius * cos(ca) - elevationLift * 14.dp.toPx()
            drawLine(accent.copy(alpha = 0.35f), Offset(cx, cy), Offset(mx, my), 1.5.dp.toPx(), StrokeCap.Round)
            drawCircle(accent.copy(alpha = 0.25f), 20.dp.toPx(), Offset(mx, my))
            drawCircle(accent, 9.dp.toPx(), Offset(mx, my))
            drawCircle(Color.White, 3.5.dp.toPx(), Offset(mx, my))

            // Elevation indicator: a rising arc when the stage is lifted.
            if (params.elevation > 2f) {
                drawArc(
                    color = teal.copy(alpha = 0.5f),
                    startAngle = -90f - 40f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(cx - radius * 0.55f, cy - radius * 0.55f - elevationLift * 16.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(radius * 1.1f, radius * 1.1f),
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Text(
            text = "FRONT",
            style = MaterialTheme.typography.labelSmall,
            color = faint,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
        )
        Text(
            text = "BEHIND",
            style = MaterialTheme.typography.labelSmall,
            color = faint,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        )
        Text(
            text = "${params.azimuth.toInt()}°  ·  ${"%.1f".format(params.distance)} m",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp)
        )
        Text(
            text = if (binaural) "HRTF" else "SPEAKERS",
            style = MaterialTheme.typography.labelSmall,
            color = if (binaural) CeeceptColors.Teal else faint,
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
        )
    }
}

private fun emit(position: Offset, size: IntSize, onChange: (Float, Float) -> Unit) {
    val w = size.width.toFloat().coerceAtLeast(1f)
    val h = size.height.toFloat().coerceAtLeast(1f)
    val cx = w / 2f
    val cy = h / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    val azimuth = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat().coerceIn(-180f, 180f)
    val maxRadius = minOf(cx, cy) * 0.86f
    val fraction = (sqrt(dx * dx + dy * dy) / maxRadius).coerceIn(0f, 1f)
    onChange(azimuth, radiusToDistance(fraction))
}


/**
 * The fourteen renderer streams, grouped the way a listener thinks about a mix.
 * Indices match [com.ceecept.music.audio.spatial.StemSeparator].
 */
object StreamGroups {
    data class Group(val label: String, val indices: IntArray, val color: Color)

    val ORDER = listOf(
        Group("Bass", intArrayOf(0), CeeceptColors.Violet),
        Group("Vocal", intArrayOf(1, 2), CeeceptColors.Accent),
        Group("Drums", intArrayOf(3, 4, 5), CeeceptColors.Amber),
        Group("Instruments", intArrayOf(6, 7), CeeceptColors.Teal),
        Group("Pads", intArrayOf(8, 9), Color(0xFF64B5F6)),
        Group("Room", intArrayOf(10, 11, 12, 13), Color(0xFFB0BEC5))
    )

    /** Mirrors the engine's per-stream distance and elevation tables. */
    val DISTANCE = floatArrayOf(
        0.95f, 0.85f, 1.00f, 1.05f, 1.05f, 1.05f, 1.10f, 1.10f,
        1.25f, 1.25f, 1.70f, 1.70f, 1.45f, 1.45f
    )
    val ELEVATION = floatArrayOf(
        -3f, 2f, 0f, 0f, 0f, 0f, 4f, 4f, 18f, 18f, 22f, 22f, 55f, 55f
    )
}

/** Live level meters for the separated streams, one bar per group. */
@Composable
fun StreamMeters(levels: FloatArray, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StreamGroups.ORDER.forEach { group ->
            var sum = 0f
            for (i in group.indices) if (i < levels.size) sum += levels[i]
            val loud = kotlin.math.sqrt(sum * 5f).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(loud)
                            .align(Alignment.BottomCenter)
                            .background(group.color.copy(alpha = 0.55f + 0.4f * loud))
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
