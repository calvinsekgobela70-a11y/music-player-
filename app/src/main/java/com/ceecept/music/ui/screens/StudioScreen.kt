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
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.runtime.key
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
import com.ceecept.music.audio.spatial.SpatialAudioEngine
import com.ceecept.music.ui.components.SpatialRadar
import com.ceecept.music.ui.components.StreamMeters
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
    val musicalQ by engine.eqMusicalQ.collectAsStateWithLifecycle()
    val autoGain by engine.eqAutoGain.collectAsStateWithLifecycle()
    val subsonic by engine.eqSubsonic.collectAsStateWithLifecycle()

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
        SwitchRow(
            title = if (musicalQ) "Musical EQ curves" else "Precision EQ curves",
            subtitle = if (musicalQ) {
                "Small moves are broad and natural; big moves become more focused"
            } else {
                "Constant-Q mode for surgical corrections"
            },
            checked = musicalQ,
            onChange = { engine.setEqMusicalQ(it) }
        )
        SwitchRow(
            title = "Auto headroom",
            subtitle = "Pulls the preamp down by the EQ boost amount so sliders cannot clip",
            checked = autoGain,
            onChange = { engine.setEqAutoGain(it) }
        )
        SwitchRow(
            title = "20 Hz subsonic clean-up",
            subtitle = "Removes inaudible rumble before boosts waste headroom",
            checked = subsonic,
            onChange = { engine.setEqSubsonic(it) }
        )
        Spacer(Modifier.height(4.dp))
        EqGraph(gains = gains, musicalQ = musicalQ)
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
private fun EqGraph(gains: FloatArray, musicalQ: Boolean = true) {
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
            val db = EqualizerProcessor.responseDb(f, gains, 48000, musicalQ).coerceIn(-15f, 15f)
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
                label = "Soft knee",
                value = band.kneeDb,
                valueRange = 0.5f..18f,
                display = String.format(Locale.US, "%.1f dB", band.kneeDb),
                onChange = { onChange(band.copy(kneeDb = it)) }
            )
            StudioSlider(
                label = "Detector",
                value = band.rmsBlend,
                valueRange = 0f..1f,
                display = if (band.rmsBlend < 0.05f) "Peak"
                else if (band.rmsBlend > 0.95f) "RMS"
                else String.format(Locale.US, "%.0f%% RMS", band.rmsBlend * 100f),
                onChange = { onChange(band.copy(rmsBlend = it)) }
            )
            SwitchRow(
                title = "Auto make-up",
                subtitle = "Restores loudness without manual gain chasing",
                checked = band.autoMakeup,
                onChange = { onChange(band.copy(autoMakeup = it)) },
                packed = true
            )
            SwitchRow(
                title = "Program release",
                subtitle = "Slows release on dense music to avoid pumping",
                checked = band.programRelease,
                onChange = { onChange(band.copy(programRelease = it)) },
                packed = true
            )
            StudioSlider(
                label = "Manual makeup",
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
    val strategy by engine.renderStrategy.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { engine.refreshOutputRoute() }

    // Telemetry from the audio thread: plain float arrays that the renderer overwrites
    // every block. Poll them at 20 Hz rather than pushing state from the audio thread.
    val dsp = engine.spatial.engine
    var telemetryTick by remember { mutableStateOf(0) }
    LaunchedEffect(params.enabled, params.stems) {
        while (params.enabled && params.stems) {
            kotlinx.coroutines.delay(50)
            telemetryTick++
        }
    }
    val streamLevels = remember { FloatArray(SpatialAudioEngine.OBJECTS) }
    val streamAzimuths = remember { FloatArray(SpatialAudioEngine.OBJECTS) }
    LaunchedEffect(telemetryTick) {
        System.arraycopy(dsp.streamLevels, 0, streamLevels, 0, streamLevels.size)
        System.arraycopy(dsp.streamAzimuths, 0, streamAzimuths, 0, streamAzimuths.size)
    }

    fun update(next: SpaceParams) = engine.updateSpace(next)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SwitchRow(
            title = "Ceecept Immerse",
            subtitle = "Object-based 3D audio",
            checked = params.enabled,
            onChange = { update(params.copy(enabled = it)) }
        )

        key(telemetryTick) {
            RendererCard(
                layoutLabel = strategy.virtualLayout.label,
                speakers = strategy.virtualLayout.totalSpeakers,
                binaural = strategy.binauralize,
                objects = if (params.stems) SpatialAudioEngine.OBJECTS else SpatialAudioEngine.BAND_OBJECTS,
                heightLayer = strategy.virtualLayout.hasHeightSpeakers,
                cpuPercent = dsp.lastBlockCpuPercent,
                degraded = dsp.analyserDegraded
            )
        }

        SectionHeader("Presets")
        PresetChips(
            options = SpaceParams.PRESETS.keys.toList(),
            selected = preset,
            onSelect = { engine.applySpacePreset(it) },
            modifier = Modifier.fillMaxWidth()
        )

        SectionHeader("The room — drag to move the stage")
        key(telemetryTick) {
            SpatialRadar(
                params = params,
                layout = strategy.virtualLayout,
                binaural = strategy.binauralize,
                onChange = { az, distance ->
                    update(params.copy(azimuth = az, distance = distance))
                },
                streamAzimuths = streamAzimuths,
                streamLevels = streamLevels
            )
        }
        Text(
            text = if (params.stems) {
                "Every dot is a part of the song the analyser found and placed: violet " +
                    "bass, red vocal, amber drums, teal instruments, blue pads, grey room. " +
                    "They move with the mix."
            } else {
                "Band mode: violet = bass (centred and mono), red = mids, amber = treble " +
                    "(spread widest). Hollow rings are the height layer."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp)
        )
        if (params.stems) {
            key(telemetryTick) { StreamMeters(levels = streamLevels) }
            Spacer(Modifier.height(10.dp))
        }

        SectionHeader("Scene")
        StudioSlider(
            label = "Elevation",
            value = params.elevation,
            valueRange = -40f..90f,
            display = "${params.elevation.toInt()}°",
            onChange = { update(params.copy(elevation = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
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
            valueRange = 0.5f..8f,
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
        StudioSlider(
            label = "Height cue",
            value = params.height,
            valueRange = 0f..1.5f,
            display = "${(params.height * 100).toInt()}%",
            onChange = { update(params.copy(height = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        SectionHeader("Instrument separation")
        SwitchRow(
            title = "Analyse and place instruments",
            subtitle = "Splits the mix into bass, vocal, drums, instruments, pads and room, " +
                "then gives each one its own position",
            checked = params.stems,
            onChange = { update(params.copy(stems = it)) }
        )
        StudioSlider(
            label = "Vocal clarity",
            value = params.vocal,
            valueRange = 0f..1f,
            display = "${(params.vocal * 100).toInt()}%",
            onChange = { update(params.copy(vocal = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Bass depth",
            value = params.bass,
            valueRange = 0f..1f,
            display = "${(params.bass * 100).toInt()}%",
            onChange = { update(params.copy(bass = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Punch",
            value = params.punch,
            valueRange = 0f..1f,
            display = "${(params.punch * 100).toInt()}%",
            onChange = { update(params.copy(punch = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Room and air",
            value = params.ambience,
            valueRange = 0f..1f,
            display = "${(params.ambience * 100).toInt()}%",
            onChange = { update(params.copy(ambience = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Pads / immersion",
            value = params.padLevel,
            valueRange = 0f..1f,
            display = "${(params.padLevel * 100).toInt()}%",
            onChange = { update(params.copy(padLevel = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Imaging focus",
            value = params.imaging,
            valueRange = 0f..1f,
            display = "${(params.imaging * 100).toInt()}%",
            onChange = { update(params.copy(imaging = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        StudioSlider(
            label = "Analogue warmth",
            value = params.warmth,
            valueRange = 0f..1f,
            display = "${(params.warmth * 100).toInt()}%",
            onChange = { update(params.copy(warmth = it)) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        SectionHeader("Speaker rig")
        PresetChips(
            options = listOf("Auto", "Stereo", "5.1", "7.1.4"),
            selected = when (params.rigMode) {
                1 -> "Stereo"
                2 -> "5.1"
                3 -> "7.1.4"
                else -> "Auto"
            },
            onSelect = { label ->
                update(
                    params.copy(
                        rigMode = when (label) {
                            "Stereo" -> 1
                            "5.1" -> 2
                            "7.1.4" -> 3
                            else -> 0
                        }
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        SectionHeader("Object rendering")
        SwitchRow(
            title = "Per-band placement",
            subtitle = "Bass stays centred and mono, treble spreads widest",
            checked = params.multiband,
            onChange = { update(params.copy(multiband = it)) }
        )
        StudioSlider(
            label = "Orbit",
            value = params.orbitHz,
            valueRange = 0f..0.25f,
            display = if (params.orbitHz < 0.005f) "Off"
            else String.format(Locale.US, "%.0f s / turn", 1f / params.orbitHz),
            onChange = { update(params.copy(orbitHz = it)) },
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

/** Shows what the renderer resolved the current output route to (guidelines 11.2). */
@Composable
private fun RendererCard(
    layoutLabel: String,
    speakers: Int,
    binaural: Boolean,
    objects: Int,
    heightLayer: Boolean,
    cpuPercent: Float = 0f,
    degraded: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = layoutLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$objects objects → $speakers virtual speakers → " +
                    (if (binaural) "binaural HRTF" else "speaker fold-down") +
                    (if (heightLayer) " · height layer active" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (cpuPercent > 0.05f) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = String.format(Locale.US, "DSP load %.0f%% of real time", cpuPercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (degraded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Analyser paused — this device could not keep up, so the " +
                        "renderer fell back to band placement.",
                    style = MaterialTheme.typography.labelSmall,
                    color = CeeceptColors.Amber
                )
            }
        }
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
