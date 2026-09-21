package com.ceecept.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ceecept.music.CeeceptApp
import com.ceecept.music.audio.BandParams
import com.ceecept.music.audio.DynamicsParams
import com.ceecept.music.audio.EqualizerProcessor
import com.ceecept.music.audio.SpaceParams
import com.ceecept.music.ui.components.CeeceptTabRow
import com.ceecept.music.ui.components.GainMeter
import com.ceecept.music.ui.components.LogStudioSlider
import com.ceecept.music.ui.components.PresetChips
import com.ceecept.music.ui.components.SectionHeader
import com.ceecept.music.ui.components.StudioSlider
import com.ceecept.music.ui.components.formatFreq
import com.ceecept.music.ui.theme.CeeceptColors
import com.ceecept.music.ui.theme.CeeceptMotion
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.delay

@Composable
fun StudioScreen(app: CeeceptApp) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Studio",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        CeeceptTabRow(
            tabs = listOf("Equalizer", "Dynamics", "Space 3D"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                fadeIn(animationSpec = CeeceptMotion.screen<Float>()) togetherWith
                    fadeOut(animationSpec = CeeceptMotion.screen())
            },
            label = "studioTab"
        ) { t ->
            when (t) {
                0 -> EqualizerTab(app)
                1 -> DynamicsTab(app)
                else -> SpaceTab(app)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Equalizer
// ---------------------------------------------------------------------------

@Composable
private fun EqualizerTab(app: CeeceptApp) {
    val engine = app.engine
    val gains by engine.eqGains.collectAsStateWithLifecycle()
    val preamp by engine.eqPreamp.collectAsStateWithLifecycle()
    val enabled by engine.eqEnabled.collectAsStateWithLifecycle()
    val preset by engine.eqPreset.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SwitchRow(
            title = "16-band equalizer",
            subtitle = "Parametric filters, 31 Hz – 16 kHz",
            checked = enabled,
            onChange = { engine.setEqEnabled(it) }
        )
        Spacer(Modifier.height(4.dp))
        EqGraph(gains = gains)
        Text(
            text = "20 Hz — 20 kHz · ±12 dB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
        SectionHeader("Presets")
        PresetChips(
            options = EqualizerProcessor.PRESETS.keys.toList(),
            selected = preset,
            onSelect = { engine.applyEqPreset(it) },
            modifier = Modifier.fillMaxWidth()
        )
        SectionHeader("Bands")
        gains.forEachIndexed { i, g ->
            StudioSlider(
                label = formatFreq(EqualizerProcessor.FREQUENCIES[i]),
                value = g,
                valueRange = -EqualizerProcessor.MAX_GAIN_DB..EqualizerProcessor.MAX_GAIN_DB,
                display = String.format(Locale.US, "%+.1f dB", g),
                onChange = { engine.setEqBand(i, it) },
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        SectionHeader("Preamp")
        StudioSlider(
            label = "Preamp",
            value = preamp,
            valueRange = -12f..12f,
            display = String.format(Locale.US, "%+.1f dB", preamp),
            onChange = { engine.setEqPreamp(it) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EqGraph(gains: FloatArray) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .padding(4.dp)
    ) {
        val w = size.width
        val h = size.height
        val pad = 14.dp.toPx()
        fun xFor(f: Float): Float {
            val t = ln(f / 20f) / ln(20000f / 20f)
            return pad + t * (w - 2 * pad)
        }
        fun yFor(db: Float): Float {
            return h / 2 - (db / 15f) * (h / 2 - pad / 2)
        }
        // Grid at band centres.
        EqualizerProcessor.FREQUENCIES.forEach { f ->
            drawLine(
                color = grid.copy(alpha = 0.6f),
                start = Offset(xFor(f), 8.dp.toPx()),
                end = Offset(xFor(f), h - 8.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
        // 0 dB line.
        drawLine(
            color = grid,
            start = Offset(pad, yFor(0f)),
            end = Offset(w - pad, yFor(0f)),
            strokeWidth = 1.5.dp.toPx()
        )
        // Response curve.
        val path = Path()
        val steps = 90
        for (i in 0..steps) {
            val f = 20f * (20000f / 20f).pow(i.toFloat() / steps)
            val db = EqualizerProcessor.responseDb(f, gains, 48000).coerceIn(-15f, 15f)
            val x = xFor(f)
            val y = yFor(db)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(w - pad, yFor(0f))
            lineTo(pad, yFor(0f))
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))
            )
        )
        drawPath(path = path, color = accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------
// Dynamics
// ---------------------------------------------------------------------------

@Composable
private fun DynamicsTab(app: CeeceptApp) {
    val engine = app.engine
    val params by engine.dynamicsParams.collectAsStateWithLifecycle()
    val preset by engine.dynamicsPreset.collectAsStateWithLifecycle()

    var meters by remember { mutableStateOf(FloatArray(5)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            meters = engine.dynamics.meters.copyOf()
        }
    }

    fun update(next: DynamicsParams) = engine.updateDynamics(next)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SwitchRow(
            title = "Multiband dynamics",
            subtitle = "3-band compressor · gates · limiter",
            checked = params.enabled,
            onChange = { update(params.copy(enabled = it)) }
        )
        SectionHeader("Presets")
        PresetChips(
            options = DynamicsParams.PRESETS.keys.toList(),
            selected = preset,
            onSelect = { engine.applyDynamicsPreset(it) },
            modifier = Modifier.fillMaxWidth()
        )
        SectionHeader("Live meters")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GainMeter(grDb = meters[0], label = "LOW gain reduction")
                GainMeter(grDb = meters[1], label = "MID gain reduction")
                GainMeter(grDb = meters[2], label = "HIGH gain reduction")
                GainMeter(grDb = meters[3], label = "LIMITER gain reduction")
                Text(
                    text = "Output peak  " + String.format(Locale.US, "%.1f dB", meters[4]),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SectionHeader("Crossover")
        LogStudioSlider(
            label = "Low / Mid",
            value = params.xoverLowHz,
            min = 80f, max = 800f,
            display = "${params.xoverLowHz.toInt()} Hz",
            onChange = { update(params.copy(xoverLowHz = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        LogStudioSlider(
            label = "Mid / High",
            value = params.xoverHighHz,
            min = 1200f, max = 12000f,
            display = if (params.xoverHighHz >= 1000) {
                String.format(Locale.US, "%.1f kHz", params.xoverHighHz / 1000)
            } else {
                "${params.xoverHighHz.toInt()} Hz"
            },
            onChange = { update(params.copy(xoverHighHz = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        BandCard(
            name = "LOW band",
            band = params.low,
            onChange = { update(params.copy(low = it)) }
        )
        BandCard(
            name = "MID band",
            band = params.mid,
            onChange = { update(params.copy(mid = it)) }
        )
        BandCard(
            name = "HIGH band",
            band = params.high,
            onChange = { update(params.copy(high = it)) }
        )

        SectionHeader("Brick-wall limiter")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SwitchRow(
                    title = "Limiter",
                    subtitle = "5 ms lookahead, zero overshoot",
                    checked = params.limiterOn,
                    onChange = { update(params.copy(limiterOn = it)) },
                    packed = true
                )
                StudioSlider(
                    label = "Ceiling",
                    value = params.limiterCeilingDb,
                    valueRange = -12f..0f,
                    display = String.format(Locale.US, "%.1f dB", params.limiterCeilingDb),
                    onChange = { update(params.copy(limiterCeilingDb = it)) }
                )
                LogStudioSlider(
                    label = "Release",
                    value = params.limiterReleaseMs,
                    min = 10f, max = 500f,
                    display = "${params.limiterReleaseMs.toInt()} ms",
                    onChange = { update(params.copy(limiterReleaseMs = it)) }
                )
            }
        }
        SectionHeader("Output")
        StudioSlider(
            label = "Makeup / output",
            value = params.outputDb,
            valueRange = -24f..12f,
            display = String.format(Locale.US, "%+.1f dB", params.outputDb),
            onChange = { update(params.copy(outputDb = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BandCard(name: String, band: BandParams, onChange: (BandParams) -> Unit) {
    SectionHeader(name)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SwitchRow(
                title = "Gate",
                subtitle = "Downward expander silences the noise floor",
                checked = band.gateOn,
                onChange = { onChange(band.copy(gateOn = it)) },
                packed = true
            )
            StudioSlider(
                label = "Gate threshold",
                value = band.gateDb,
                valueRange = -80f..-20f,
                display = String.format(Locale.US, "%.0f dB", band.gateDb),
                onChange = { onChange(band.copy(gateDb = it)) }
            )
            StudioSlider(
                label = "Threshold",
                value = band.thresholdDb,
                valueRange = -60f..0f,
                display = String.format(Locale.US, "%.1f dB", band.thresholdDb),
                onChange = { onChange(band.copy(thresholdDb = it)) }
            )
            StudioSlider(
                label = "Ratio",
                value = band.ratio,
                valueRange = 1f..20f,
                display = String.format(Locale.US, "%.1f : 1", band.ratio),
                onChange = { onChange(band.copy(ratio = it)) }
            )
            LogStudioSlider(
                label = "Attack",
                value = band.attackMs,
                min = 0.1f, max = 100f,
                display = String.format(Locale.US, "%.1f ms", band.attackMs),
                onChange = { onChange(band.copy(attackMs = it)) }
            )
            LogStudioSlider(
                label = "Release",
                value = band.releaseMs,
                min = 10f, max = 1000f,
                display = "${band.releaseMs.toInt()} ms",
                onChange = { onChange(band.copy(releaseMs = it)) }
            )
            StudioSlider(
                label = "Makeup",
                value = band.makeupDb,
                valueRange = 0f..24f,
                display = String.format(Locale.US, "+%.1f dB", band.makeupDb),
                onChange = { onChange(band.copy(makeupDb = it)) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Space 3D
// ---------------------------------------------------------------------------

@Composable
private fun SpaceTab(app: CeeceptApp) {
    val engine = app.engine
    val params by engine.spaceParams.collectAsStateWithLifecycle()
    val preset by engine.spacePreset.collectAsStateWithLifecycle()

    fun update(next: SpaceParams) = engine.updateSpace(next)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SwitchRow(
            title = "Ceecept Immerse",
            subtitle = "HRTF 3D audio for headphones",
            checked = params.enabled,
            onChange = { update(params.copy(enabled = it)) }
        )
        SectionHeader("Presets")
        PresetChips(
            options = SpaceParams.PRESETS.keys.toList(),
            selected = preset,
            onSelect = { engine.applySpacePreset(it) },
            modifier = Modifier.fillMaxWidth()
        )
        SectionHeader("3D coordinates — drag the sound")
        SpacePad(
            azimuth = params.azimuth,
            elevation = params.elevation,
            onChange = { az, el -> update(params.copy(azimuth = az, elevation = el)) }
        )
        Text(
            text = "Azimuth ${params.azimuth.toInt()}° · Elevation ${params.elevation.toInt()}° — " +
                "micro-delays (ITD), level shadowing (ILD) and pinna filtering place the stage around your head.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
        )
        SectionHeader("Scene")
        StudioSlider(
            label = "Immersion",
            value = params.strength,
            valueRange = 0f..1f,
            display = "${(params.strength * 100).toInt()}%",
            onChange = { update(params.copy(strength = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Distance",
            value = params.distance,
            valueRange = 0.5f..4f,
            display = String.format(Locale.US, "%.1f m", params.distance),
            onChange = { update(params.copy(distance = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Width",
            value = params.width,
            valueRange = 0f..1.5f,
            display = "${(params.width * 100).toInt()}%",
            onChange = { update(params.copy(width = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        SectionHeader("Space")
        StudioSlider(
            label = "Room size",
            value = params.roomSize,
            valueRange = 0f..1f,
            display = "${(params.roomSize * 100).toInt()}%",
            onChange = { update(params.copy(roomSize = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Reverb",
            value = params.reverb,
            valueRange = 0f..1f,
            display = "${(params.reverb * 100).toInt()}%",
            onChange = { update(params.copy(reverb = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Damping",
            value = params.damping,
            valueRange = 0f..1f,
            display = "${(params.damping * 100).toInt()}%",
            onChange = { update(params.copy(damping = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SpacePad(
    azimuth: Float,
    elevation: Float,
    onChange: (Float, Float) -> Unit
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val accent = CeeceptColors.Accent
    val onSurface = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { sizePx = it }
            .pointerInput(sizePx) {
                detectDragGestures { change, _ ->
                    val w = sizePx.width.toFloat().coerceAtLeast(1f)
                    val h = sizePx.height.toFloat().coerceAtLeast(1f)
                    val az = ((change.position.x / w) * 360f - 180f).coerceIn(-180f, 180f)
                    val el = (90f - (change.position.y / h) * 130f).coerceIn(-40f, 90f)
                    onChange(az, el)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Crosshair.
            drawLine(grid, Offset(w / 2, 0f), Offset(w / 2, h), 1.dp.toPx())
            val elZeroY = h * (90f / 130f)
            drawLine(grid, Offset(0f, elZeroY), Offset(w, elZeroY), 1.dp.toPx())
            // Listener head.
            drawCircle(onSurface.copy(alpha = 0.9f), radius = 26.dp.toPx(), center = Offset(w / 2, elZeroY), style = Stroke(2.dp.toPx()))
            drawLine(
                onSurface.copy(alpha = 0.9f),
                Offset(w / 2, elZeroY - 26.dp.toPx()),
                Offset(w / 2, elZeroY - 38.dp.toPx()),
                3.dp.toPx(), StrokeCap.Round
            )
            // Sound source.
            val sx = (azimuth + 180f) / 360f * w
            val sy = (90f - elevation) / 130f * h
            drawLine(
                accent.copy(alpha = 0.5f),
                Offset(w / 2, elZeroY), Offset(sx, sy),
                1.5.dp.toPx(), StrokeCap.Round
            )
            drawCircle(accent.copy(alpha = 0.25f), radius = 22.dp.toPx(), center = Offset(sx, sy))
            drawCircle(accent, radius = 10.dp.toPx(), center = Offset(sx, sy))
            drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(sx, sy))
        }
        Text(
            text = "FRONT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        )
        Text(
            text = "${azimuth.toInt()}° / ${elevation.toInt()}°",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    packed: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (packed) 0.dp else 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
