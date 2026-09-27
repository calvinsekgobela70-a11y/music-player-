package com.ceecept.music.audio.spatial

import com.ceecept.music.audio.Biquad
import com.ceecept.music.audio.BiquadState
import com.ceecept.music.audio.DelayLine
import com.ceecept.music.audio.Dsp
import com.ceecept.music.audio.process
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scene the renderer is asked to produce. Angles in degrees, distance in metres —
 * the same units as the guidelines throughout.
 */
data class SpatialScene(
    val enabled: Boolean = true,
    /** Dry/wet immersion, 0..1. */
    val strength: Float = 0.8f,
    val azimuthDeg: Float = 0f,
    val elevationDeg: Float = 12f,
    val distanceM: Float = 1.6f,
    /** Scene width; 1 = the ±30° stereo pair of §2.2. */
    val width: Float = 1f,
    val roomSize: Float = 0.55f,
    val reverb: Float = 0.35f,
    val damping: Float = 0.5f,
    /** Weight of the §5.2 virtual-height cue, 0..1.5. */
    val height: Float = 1f,
    /** Orbit rate in Hz for the §8 trajectory demo mode; 0 = static scene. */
    val orbitHz: Float = 0f,
    /** §12 per-band spatial widths. */
    val multiband: Boolean = true,

    // ---- Immerse 3.0: object separation and per-stream enhancement ----
    /** Analyse the mix and render its parts as individual objects. */
    val stems: Boolean = true,
    /** Presence/clarity lift on the extracted lead vocal, 0..1. */
    val vocal: Float = 0.45f,
    /** Psychoacoustic bass extension on the extracted bass stream, 0..1. */
    val bass: Float = 0.45f,
    /** Transient emphasis on the extracted percussion, 0..1. */
    val punch: Float = 0.35f,
    /** Level of the rear and height ambience streams, 0..1. */
    val ambience: Float = 0.5f,
    /** 0 = follow the output route, 1 = stereo, 2 = 5.1, 3 = 7.1.4. */
    val rigMode: Int = 0
)

/**
 * Real-time object-based spatial renderer — guidelines §11.1.
 *
 * Pipeline per block:
 *   1. decompose the incoming stereo programme into audio objects (§2.3, §12);
 *   2. resolve each object's position, including trajectory motion (§8.1);
 *   3. distance processing — inverse square law, air absorption, reverb amount (§7);
 *   4. adaptive rendering of every object onto the active speaker layout (§4);
 *   5. height handling: direct to the height layer, or virtual height EQ (§5);
 *   6. fold the speaker feeds to the two channels the phone actually has — binaural
 *      HRTF for headphones (§6), amplitude fold-down for loudspeakers;
 *   7. anti-clipping soft limiter with oversampled saturation (§10.1, §11.1).
 *
 * Objects are derived from the stereo mix rather than authored stems: the centre
 * (mid) signal is one object, and the difference (side) signal is split into the six
 * §12.1 bands, each contributing a left and a right object whose angular offset is the
 * band's own `spatial_width`. Bass bands therefore collapse towards the centre exactly
 * as the guideline's `diffuse` flag requires, while treble spreads wider than the
 * original stereo pair.
 */
class SpatialAudioEngine {

    companion object {
        const val MAX_SPEAKERS = 12
        const val BANDS = 6

        /** Legacy band model: 1 centre object + one left/right pair per band. */
        const val BAND_OBJECTS = 1 + 2 * BANDS

        /** Object slots: the stem renderer needs the most, so it sets the size. */
        const val OBJECTS = StemSeparator.STREAMS

        private const val EARLY_TAPS = 12
        private const val FDN_LINES = 8
        private val FDN_MS = floatArrayOf(29.7f, 33.9f, 37.3f, 41.5f, 45.9f, 50.1f, 54.7f, 59.3f)

        /** Nominal listening distance: the scene is level-matched here. */
        private const val REFERENCE_DISTANCE_M = 1.6f
    }

    /** §3.2 — exposed so head tracking can move the whole scene with one write. */
    val listener = ListenerPose()

    private var sampleRate = 48000
    private var layout: SpeakerLayout = SpeakerLayouts.IMMERSIVE_7_1_4
    private var binauralize = true
    private var speakerCount = layout.totalSpeakers
    private var assignmentCone = Panning.coneFor(layout)
    private var panner = PairwisePanner(layout)
    private var scene = SpatialScene()
    private var sceneDirty = true

    // ---- object model ----
    private val objAzimuth = FloatArray(OBJECTS)
    private val objElevation = FloatArray(OBJECTS)
    private val objDistance = FloatArray(OBJECTS)
    private val objDiffuse = FloatArray(OBJECTS)
    private val gains = SmoothedGainBank(OBJECTS * MAX_SPEAKERS)
    private val panScratch = FloatArray(MAX_SPEAKERS)
    private val relative = FloatArray(3)
    private val cartScratch = FloatArray(3)

    // ---- band split (§12) ----
    private val sideSplitter = LinkwitzRileySplitter(BANDS)
    private val bandBuf = FloatArray(BANDS)
    private val subLow = Biquad()
    private val subLow2 = Biquad()
    private val subLowState = BiquadState()
    private val subLowState2 = BiquadState()

    // ---- propagation delay -> Doppler (§8.2) ----
    private var propagationL = DelayLine(64)
    private var propagationR = DelayLine(64)
    private val propagationSmoother = SmoothedGain(0f)

    // ---- speaker busses ----
    private val busses = FloatArray(MAX_SPEAKERS)
    private var lfeIndex = -1

    // ---- early reflections (§7.3) ----
    private var earlyLines: Array<DelayLine> = emptyArray()
    private val tapAzimuth = FloatArray(EARLY_TAPS)
    private val tapElevation = FloatArray(EARLY_TAPS)
    private val tapSpacing = FloatArray(EARLY_TAPS)
    private val tapSpeakerA = IntArray(EARLY_TAPS)
    private val tapSpeakerB = IntArray(EARLY_TAPS)
    private val tapGainA = FloatArray(EARLY_TAPS)
    private val tapGainB = FloatArray(EARLY_TAPS)

    // ---- late reverb ----
    private var fdnLines: Array<DelayLine> = emptyArray()
    private val fdnBase = FloatArray(FDN_LINES)
    private val fdnFeedback = FloatArray(FDN_LINES)
    private val fdnDamp = FloatArray(FDN_LINES)
    private val fdnRead = FloatArray(FDN_LINES)
    private val lfoPhase = FloatArray(FDN_LINES)
    private val lfoInc = FloatArray(FDN_LINES)
    private var dampCoef = 0.3f
    private var reverbSend = 0.2f
    private var predelay = DelayLine(64)

    // ---- output stage ----
    private val binaural = BinauralVirtualizer(MAX_SPEAKERS)
    private val folddown = StereoFolddown(MAX_SPEAKERS)
    private val earOut = FloatArray(2)
    private val airL1 = Biquad(); private val airL2 = Biquad()
    private val airR1 = Biquad(); private val airR2 = Biquad()
    private val airLs1 = BiquadState(); private val airLs2 = BiquadState()
    private val airRs1 = BiquadState(); private val airRs2 = BiquadState()
    private val heightL = VirtualHeightFilter()
    private val heightR = VirtualHeightFilter()
    private val overL = Oversampler2x()
    private val overR = Oversampler2x()
    private val overBuf = FloatArray(2)

    private var dryGain = 0.7f
    private var wetGain = 0.7f

    // ---- Immerse 3.0: separation + per-stream enhancement ----
    private val separator = StemSeparator()
    private val streamBuf = FloatArray(StemSeparator.STREAMS)
    private val streamTrim = FloatArray(StemSeparator.STREAMS) { 1f }
    private val virtualBass = VirtualBass()
    private val vocalEnhancer = VocalEnhancer()
    private val shaperCentre = TransientShaper()
    private val shaperLeft = TransientShaper()
    private val shaperRight = TransientShaper()
    private val decorrelators = Array(6) { Decorrelator(it) }
    private val airLift = AirLift()
    private var dryDelayL = DelayLine(64)
    private var dryDelayR = DelayLine(64)
    private var stemMode = false
    private val lookahead = LookaheadLimiter()
    private val loudness = LoudnessMatch()

    /** Live per-stream levels for the UI (index = StemSeparator stream id). */
    val streamLevels = FloatArray(StemSeparator.STREAMS)

    /** Live per-stream azimuths for the UI, degrees. */
    val streamAzimuths = FloatArray(StemSeparator.STREAMS)

    /** Elevation, distance scale and diffusion of each stream object. */
    private val streamElevation = floatArrayOf(
        -3f, 2f, 0f, 0f, 0f, 0f, 4f, 4f, 18f, 18f, 22f, 22f, 55f, 55f
    )
    private val streamDistance = floatArrayOf(
        0.95f, 0.85f, 1.00f, 1.05f, 1.05f, 1.05f, 1.10f, 1.10f,
        1.25f, 1.25f, 1.70f, 1.70f, 1.45f, 1.45f
    )
    private val streamDiffuse = floatArrayOf(
        0.35f, 0f, 0.05f, 0f, 0.02f, 0.02f, 0.05f, 0.05f,
        0.25f, 0.25f, 0.65f, 0.65f, 0.5f, 0.5f
    )

    // §8.1 orbit path, expressed in normalised time (0..1 = one revolution) so the
    // spline is built once in prepare() and never allocates on the audio thread.
    private var orbitPath: ObjectTrajectory? = null
    private var orbitPhase = 0f
    private val orbitPosition = FloatArray(3)

    // ---- performance counters (§15.1) ----
    @Volatile var lastBlockCpuPercent: Float = 0f
        private set
    @Volatile var renderedFrames: Long = 0L
        private set

    /**
     * True when the renderer had to switch the analyser off to keep up (§11.2 asks for
     * graceful degradation rather than dropouts). Cleared whenever the scene changes.
     */
    @Volatile var analyserDegraded: Boolean = false
        private set

    private var cpuAverage = 0f
    private var overloadedBlocks = 0

    // ------------------------------------------------------------------ lifecycle

    fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        val sr = sampleRate

        propagationL = DelayLine((0.06f * sr).toInt() + 8)
        propagationR = DelayLine((0.06f * sr).toInt() + 8)
        propagationSmoother.setTimeConstant(40f, sr)
        propagationSmoother.snap(Doppler.propagationDelaySamples(REFERENCE_DISTANCE_M, sr))

        earlyLines = Array(EARLY_TAPS) { DelayLine((0.15f * sr).toInt() + 8) }
        fdnLines = Array(FDN_LINES) { DelayLine((0.15f * sr).toInt() + 8) }
        predelay = DelayLine((0.05f * sr).toInt() + 8)
        predelay.delay = DistanceModel.PREDELAY_MS / 1000f * sr

        // Golden-angle reflection geometry: no two taps share a direction, and the
        // delays stay mutually prime so the pattern never sounds like a comb filter.
        for (i in 0 until EARLY_TAPS) {
            tapAzimuth[i] = Geometry.wrapDeg(i * 137.5f)
            tapElevation[i] = if (i % 3 == 0) 25f else if (i % 3 == 1) 0f else -12f
            tapSpacing[i] = 1f + i * 1.45f + (i * 37 % 11) * 0.21f
        }

        // --- Immerse 3.0 stages ---
        separator.prepare(sr)
        virtualBass.prepare(sr)
        vocalEnhancer.prepare(sr)
        shaperCentre.prepare(sr); shaperLeft.prepare(sr); shaperRight.prepare(sr)
        for (d in decorrelators) d.prepare(sr)
        airLift.prepare(sr)
        // The dry path is delayed to match the analysis window so dry and wet stay
        // phase-aligned; otherwise the mix comb-filters.
        val analysisLatency = separator.latencySamples()
        dryDelayL = DelayLine(analysisLatency + 8)
        dryDelayR = DelayLine(analysisLatency + 8)
        dryDelayL.delay = analysisLatency.toFloat()
        dryDelayR.delay = analysisLatency.toFloat()
        lookahead.configure(sr, lookaheadMs = 1.5f, releaseMs = 120f, ceilingDb = -0.2f)
        loudness.configure(sr)

        val lfoRates = floatArrayOf(0.09f, 0.13f, 0.07f, 0.17f, 0.11f, 0.19f, 0.15f, 0.23f)
        for (i in 0 until FDN_LINES) {
            lfoInc[i] = 2f * PI.toFloat() * lfoRates[i] / sr
            lfoPhase[i] = i * 0.9f
        }

        // One revolution of the demo trajectory: eight keyframes around the listener,
        // with the distance breathing in and out so the propagation delay produces a
        // real Doppler shift (§8.2) instead of a synthetic pitch bend.
        orbitPath = ObjectTrajectory(
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

        sideSplitter.configure(SpatialBands.CROSSOVERS, sr)
        subLow.setLowPass(100f, 0.7071f, sr)
        subLow2.setLowPass(100f, 0.7071f, sr)

        gains.setTimeConstant(5f, sr) // §10.2: 5 ms, the guideline's smoothing window.
        binaural.prepare(sr)
        sceneDirty = true
    }

    /** §11.2: switch layout/fold-down when the audio route changes. */
    fun setStrategy(strategy: RenderStrategy) {
        if (layout.id == strategy.virtualLayout.id && binauralize == strategy.binauralize) return
        layout = strategy.virtualLayout
        binauralize = strategy.binauralize
        speakerCount = minOf(layout.totalSpeakers, MAX_SPEAKERS)
        assignmentCone = Panning.coneFor(layout)
        panner = PairwisePanner(layout)
        lfeIndex = layout.speakers.indexOfFirst { it.isLfe }
        folddown.configure(layout)
        gains.clear()
        sceneDirty = true
    }

    fun setScene(scene: SpatialScene) {
        this.scene = scene
        sceneDirty = true
    }

    fun reset() {
        gains.clear()
        sideSplitter.clear()
        subLowState.clear(); subLowState2.clear()
        propagationL.clear(); propagationR.clear()
        earlyLines.forEach { it.clear() }
        fdnLines.forEach { it.clear() }
        fdnDamp.fill(0f)
        predelay.clear()
        binaural.clear()
        heightL.clear(); heightR.clear()
        airLs1.clear(); airLs2.clear(); airRs1.clear(); airRs2.clear()
        overL.clear(); overR.clear()
        lookahead.reset()
        loudness.reset()
        separator.reset()
        virtualBass.reset()
        vocalEnhancer.reset()
        shaperCentre.reset(); shaperLeft.reset(); shaperRight.reset()
        for (d in decorrelators) d.reset()
        airLift.reset()
        dryDelayL.clear(); dryDelayR.clear()
        streamLevels.fill(0f)
        busses.fill(0f)
        orbitPhase = 0f
    }

    // ------------------------------------------------------------------ configure

    /** Recompute everything that depends on the scene. Runs once per block, not per sample. */
    private fun rebuild() {
        val sr = sampleRate
        val s = scene
        if (lfeIndex == -1) lfeIndex = layout.speakers.indexOfFirst { it.isLfe }

        // --- Immerse 3.0 stem model -------------------------------------------
        stemMode = s.stems
        analyserDegraded = false
        overloadedBlocks = 0
        cpuAverage = 0f
        virtualBass.amount = s.bass.coerceIn(0f, 1f)
        vocalEnhancer.amount = s.vocal.coerceIn(0f, 1f)
        val punch = s.punch.coerceIn(0f, 1f)
        shaperCentre.amount = punch
        shaperLeft.amount = punch
        shaperRight.amount = punch
        airLift.amount = (s.ambience * 0.8f).coerceIn(0f, 1f)
        val ambTrim = 0.45f + 1.1f * s.ambience.coerceIn(0f, 1f)
        for (i in streamTrim.indices) streamTrim[i] = 1f
        streamTrim[StemSeparator.WIDE_L] = 0.85f + 0.5f * s.width
        streamTrim[StemSeparator.WIDE_R] = streamTrim[StemSeparator.WIDE_L]
        streamTrim[StemSeparator.AMB_L] = ambTrim
        streamTrim[StemSeparator.AMB_R] = ambTrim
        streamTrim[StemSeparator.AIR_L] = ambTrim * 0.9f
        streamTrim[StemSeparator.AIR_R] = ambTrim * 0.9f
        if (stemMode) {
            updateStreamPlacement()
        }

        // --- object geometry (§2.3, §12.1) ---
        val baseSpread = 30f * s.width
        objAzimuth[0] = s.azimuthDeg
        objElevation[0] = s.elevationDeg
        objDistance[0] = s.distanceM
        objDiffuse[0] = 0f
        for (b in 0 until BANDS) {
            val band = SpatialBands.TABLE[b]
            val bandWidth = if (s.multiband) band.spatialWidth else 1f
            val offset = baseSpread * bandWidth
            val li = 1 + b * 2
            val ri = li + 1
            objAzimuth[li] = Geometry.wrapDeg(s.azimuthDeg - offset)
            objAzimuth[ri] = Geometry.wrapDeg(s.azimuthDeg + offset)
            objElevation[li] = s.elevationDeg
            objElevation[ri] = s.elevationDeg
            objDistance[li] = s.distanceM
            objDistance[ri] = s.distanceM
            // §2.3 diffuse_amount: bass bands are rendered non-directionally.
            objDiffuse[li] = if (band.diffuse) 0.85f else 0.06f
            objDiffuse[ri] = objDiffuse[li]
        }
        updateObjectGains()

        // --- distance processing (§7) ---
        val cutoff = DistanceModel.airAbsorptionCutoffHz(s.distanceM)
        airL1.setLowPass(cutoff, 0.7071f, sr); airL2.setLowPass(cutoff, 0.7071f, sr)
        airR1.setLowPass(cutoff, 0.7071f, sr); airR2.setLowPass(cutoff, 0.7071f, sr)

        val decay = DistanceModel.decayTimeSec(s.distanceM, s.roomSize)
        val lengthScale = 0.55f + 0.9f * s.roomSize
        for (i in 0 until FDN_LINES) {
            val seconds = FDN_MS[i] / 1000f * lengthScale
            fdnBase[i] = seconds * sr
            fdnFeedback[i] = Math.pow(10.0, (-3.0 * seconds / decay)).toFloat().coerceAtMost(0.985f)
        }
        dampCoef = Dsp.onePoleLowPassCoef(18000f - s.damping * 15500f, sr)
        predelay.delay = DistanceModel.PREDELAY_MS / 1000f * sr
        reverbSend = DistanceModel.reverbWet(s.distanceM) * (0.4f + 1.2f * s.reverb)

        // --- early reflections (§7.3) ---
        val firstArrivalMs = DistanceModel.earlyReflectionDelayMs(s.distanceM)
        val roomScale = 0.5f + s.roomSize
        for (i in 0 until EARLY_TAPS) {
            val ms = firstArrivalMs + tapSpacing[i] * roomScale * 4.6f
            earlyLines[i].delay = ms / 1000f * sr
            val decayGain = Math.exp((-ms / 1000f) * 26.0 / roomScale).toFloat()
            val az = Geometry.wrapDeg(tapAzimuth[i] + s.azimuthDeg)
            val el = tapElevation[i] + s.elevationDeg * 0.5f
            Panning.adaptiveRender(az, el, s.distanceM, layout, panScratch, assignmentCone)
            // Keep the two strongest speakers per tap: full vectors would cost
            // taps x speakers multiply-accumulates per sample for no audible gain.
            var i1 = 0; var g1 = -1f; var i2 = 0; var g2 = -1f
            for (k in 0 until speakerCount) {
                val g = panScratch[k]
                if (g > g1) { i2 = i1; g2 = g1; i1 = k; g1 = g } else if (g > g2) { i2 = k; g2 = g }
            }
            val norm = (g1 + g2).coerceAtLeast(1e-6f)
            tapSpeakerA[i] = i1
            tapSpeakerB[i] = i2
            tapGainA[i] = decayGain * 0.34f * (g1 / norm)
            tapGainB[i] = decayGain * 0.34f * (g2 / norm)
        }

        // --- height handling (§5) ---
        // Direct routing when the layout owns a height layer, spectral cue when not.
        val virtualHeightElevation = if (layout.hasHeightSpeakers) 0f else s.elevationDeg
        heightL.configure(virtualHeightElevation, s.height, sr)
        heightR.configure(virtualHeightElevation, s.height, sr)

        // --- output stage ---
        binaural.configure(layout, s.strength.coerceIn(0f, 1f), s.height)
        folddown.configure(layout)

        // Level-match the wet path to the reference distance, then apply the §7.1 law.
        val distanceGain = (DistanceModel.attenuationLinear(s.distanceM) /
            DistanceModel.attenuationLinear(REFERENCE_DISTANCE_M)).coerceIn(0.35f, 2.0f)
        val strength = s.strength.coerceIn(0f, 1f)
        dryGain = cos(strength * (PI.toFloat() / 2f) * 0.85f)
        wetGain = sin(strength * (PI.toFloat() / 2f)) * 1.25f * distanceGain

        propagationSmoother.target = Doppler.propagationDelaySamples(s.distanceM, sr)
        sceneDirty = false
    }

    /**
     * Immerse 3.0 — place the separated streams.
     *
     * Each stream object takes the azimuth the analyser *measured* for it, so a guitar
     * that was mixed 40 degrees left is rendered by the speaker 40 degrees left instead
     * of being smeared across the front pair. Elevation, distance and diffusion come
     * from the per-stream table: bass and lead stay near and dry at ear level, pads lift
     * and widen, ambience goes to the rear, air goes overhead.
     */
    private fun updateStreamPlacement() {
        val s = scene
        val widthScale = s.width.coerceIn(0.2f, 1.5f)
        for (o in 0 until StemSeparator.STREAMS) {
            val measured = separator.azimuth[o]
            val az = Geometry.wrapDeg(s.azimuthDeg + measured * widthScale)
            streamAzimuths[o] = az
            objAzimuth[o] = az
            objElevation[o] = (streamElevation[o] * (0.4f + 0.6f * s.height.coerceIn(0f, 1.5f)) +
                s.elevationDeg * (if (o >= StemSeparator.AMB_L) 0.3f else 1f)).coerceIn(-40f, 90f)
            objDistance[o] = (s.distanceM * streamDistance[o]).coerceIn(0.3f, 20f)
            objDiffuse[o] = streamDiffuse[o]
        }
        updateObjectGains()
    }

    /**
     * §4 — resolve every object to a normalised gain vector over the active layout.
     * Targets only; [SmoothedGainBank] interpolates them per sample (§10.2).
     */
    private fun updateObjectGains() {
        val s = scene
        val target = gains.target
        for (o in 0 until OBJECTS) {
            listener.relativeSpherical(
                objAzimuth[o], objElevation[o], objDistance[o], relative, cartScratch
            )
            val az = relative[0]
            val el = relative[1]
            // Pairwise panning: exact velocity-vector placement (§14.1 wants <5°),
            // with the guideline's layer geometry and elevation crossfade (§5.1).
            panner.pan(az, el, panScratch)
            panner.applyDiffusion(panScratch, objDiffuse[o])

            val base = o * MAX_SPEAKERS
            for (k in 0 until MAX_SPEAKERS) {
                target[base + k] = if (k < speakerCount) panScratch[k] else 0f
            }
        }
        if (!s.enabled) gains.clear()
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Render [frames] interleaved stereo frames in place.
     * Input is the dry programme; output is the spatialised mix.
     */
    fun process(io: FloatArray, frames: Int) {
        if (!scene.enabled) {
            renderedFrames += frames
            return
        }
        val startNs = System.nanoTime()
        if (sceneDirty) rebuild()

        // §8.1: advance the keyframed trajectory once per block and re-pan.
        val path = orbitPath
        if (scene.orbitHz > 0f && path != null) {
            orbitPhase += scene.orbitHz * frames / sampleRate
            while (orbitPhase > 1f) orbitPhase -= 1f
            path.positionAt(orbitPhase, orbitPosition)
            val orbitAz = orbitPosition[0]
            val orbitEl = orbitPosition[1]
            val orbitDist = (scene.distanceM * orbitPosition[2]).coerceAtLeast(0.2f)
            // Rotate the whole scene rather than each object independently.
            val baseSpread = 30f * scene.width
            objAzimuth[0] = Geometry.wrapDeg(scene.azimuthDeg + orbitAz)
            objElevation[0] = scene.elevationDeg + orbitEl
            for (b in 0 until BANDS) {
                val bandWidth = if (scene.multiband) SpatialBands.TABLE[b].spatialWidth else 1f
                val offset = baseSpread * bandWidth
                objAzimuth[1 + b * 2] = Geometry.wrapDeg(scene.azimuthDeg + orbitAz - offset)
                objAzimuth[2 + b * 2] = Geometry.wrapDeg(scene.azimuthDeg + orbitAz + offset)
                objElevation[1 + b * 2] = scene.elevationDeg + orbitEl
                objElevation[2 + b * 2] = scene.elevationDeg + orbitEl
            }
            for (o in 0 until OBJECTS) objDistance[o] = orbitDist
            updateObjectGains()
            // Distance drives the propagation delay, and the changing delay *is* the
            // Doppler shift — no pitch shifter in the signal path (§8.2).
            propagationSmoother.target = Doppler.propagationDelaySamples(orbitDist, sampleRate)
        }

        // The analyser re-measures where each part of the mix sits; refresh the object
        // placement once per block (never per sample) so the objects follow the music.
        if (stemMode) {
            updateStreamPlacement()
            System.arraycopy(separator.level, 0, streamLevels, 0, StemSeparator.STREAMS)
        }

        val current = gains.current
        val reverbAmount = reverbSend
        val dg = dryGain
        val wg = wetGain

        for (f in 0 until frames) {
            val i = f * 2
            val dryL = io[i]
            val dryR = io[i + 1]

            // --- propagation delay: distance changes become Doppler (§8.2) ---
            val delaySamples = propagationSmoother.next()
            propagationL.delay = delaySamples
            propagationR.delay = delaySamples
            val inL = propagationL.push(dryL)
            val inR = propagationR.push(dryR)

            gains.tick()
            for (k in 0 until speakerCount) busses[k] = 0f
            var base: Int
            val mid: Float
            var bassFeed = 0f

            if (stemMode) {
                // --- Immerse 3.0: separate, enhance, then place each part ---------
                separator.processSample(inL, inR, streamBuf)

                streamBuf[StemSeparator.BASS] = virtualBass.process(streamBuf[StemSeparator.BASS])
                streamBuf[StemSeparator.LEAD] = vocalEnhancer.process(streamBuf[StemSeparator.LEAD])
                streamBuf[StemSeparator.PERC_C] = shaperCentre.process(streamBuf[StemSeparator.PERC_C])
                streamBuf[StemSeparator.PERC_L] = shaperLeft.process(streamBuf[StemSeparator.PERC_L])
                streamBuf[StemSeparator.PERC_R] = shaperRight.process(streamBuf[StemSeparator.PERC_R])
                streamBuf[StemSeparator.WIDE_L] =
                    decorrelators[0].process(streamBuf[StemSeparator.WIDE_L], 0.5f)
                streamBuf[StemSeparator.WIDE_R] =
                    decorrelators[1].process(streamBuf[StemSeparator.WIDE_R], 0.5f)
                streamBuf[StemSeparator.AMB_L] =
                    decorrelators[2].process(streamBuf[StemSeparator.AMB_L], 0.8f)
                streamBuf[StemSeparator.AMB_R] =
                    decorrelators[3].process(streamBuf[StemSeparator.AMB_R], 0.8f)
                streamBuf[StemSeparator.AIR_L] =
                    airLift.process(decorrelators[4].process(streamBuf[StemSeparator.AIR_L], 0.7f))
                streamBuf[StemSeparator.AIR_R] =
                    decorrelators[5].process(streamBuf[StemSeparator.AIR_R], 0.7f)

                var sum = 0f
                for (o in 0 until StemSeparator.STREAMS) {
                    val sample = streamBuf[o] * streamTrim[o]
                    sum += sample
                    if (sample == 0f) continue
                    base = o * MAX_SPEAKERS
                    for (k in 0 until speakerCount) busses[k] += sample * current[base + k]
                }
                mid = sum * 0.5f
                bassFeed = streamBuf[StemSeparator.BASS]
            } else {
                // --- legacy band model (§12): mid object + one pair per band ------
                val m = (inL + inR) * 0.5f
                val side = (inL - inR) * 0.5f
                sideSplitter.process(side, bandBuf)
                for (k in 0 until speakerCount) busses[k] += m * current[k]
                for (b in 0 until BANDS) {
                    val bandSample = bandBuf[b]
                    if (bandSample == 0f) continue
                    base = (1 + b * 2) * MAX_SPEAKERS
                    for (k in 0 until speakerCount) busses[k] += bandSample * current[base + k]
                    base += MAX_SPEAKERS
                    for (k in 0 until speakerCount) busses[k] -= bandSample * current[base + k]
                }
                mid = m
                bassFeed = m
            }

            // --- bass management: the LFE feed is the mono low band (§13 hints) ---
            if (lfeIndex >= 0) {
                busses[lfeIndex] += subLow2.process(subLow.process(bassFeed, subLowState), subLowState2)
            }

            // --- §7.3 early reflections, panned onto the same layout ---
            for (t in 0 until EARLY_TAPS) {
                val tap = earlyLines[t].push(mid)
                busses[tapSpeakerA[t]] += tap * tapGainA[t]
                busses[tapSpeakerB[t]] += tap * tapGainB[t]
            }

            // --- fold to two channels: HRTF for headphones, amplitude otherwise ---
            if (binauralize) binaural.processFrame(busses, earOut) else folddown.processFrame(busses, earOut)
            var wetL = earOut[0]
            var wetR = earOut[1]

            // --- §5.2 virtual height (only when the layout has no height layer) ---
            wetL = heightL.process(wetL)
            wetR = heightR.process(wetR)

            // --- late reverb: 8-line modulated FDN, fed through the pre-delay ---
            val send = predelay.push(mid) * reverbAmount
            var mean = 0f
            for (n in 0 until FDN_LINES) {
                lfoPhase[n] += lfoInc[n]
                if (lfoPhase[n] > 2f * PI.toFloat()) lfoPhase[n] -= 2f * PI.toFloat()
                fdnLines[n].delay = fdnBase[n] * (1f + 0.004f * sin(lfoPhase[n]))
                val read = fdnLines[n].peek()
                fdnDamp[n] += dampCoef * (read - fdnDamp[n])
                fdnRead[n] = fdnDamp[n] * fdnFeedback[n]
                mean += fdnRead[n]
            }
            mean = mean * 2f / FDN_LINES
            for (n in 0 until FDN_LINES) {
                fdnLines[n].store(send * 0.4f + (fdnRead[n] - mean))
            }
            val revL = (fdnRead[0] - fdnRead[2] + fdnRead[4] - fdnRead[6]) * 0.32f
            val revR = (-fdnRead[1] + fdnRead[3] - fdnRead[5] + fdnRead[7]) * 0.32f

            wetL += revL
            wetR += revR

            // --- §7.2 air absorption on the spatialised path only ---
            wetL = airL2.process(airL1.process(wetL, airLs1), airLs2)
            wetR = airR2.process(airR1.process(wetR, airRs1), airRs2)

            // --- mix ---------------------------------------------------------
            // The dry path is delayed to the analysis latency so that dry and wet are
            // time-aligned; mixing a 21 ms-early dry signal would comb-filter the top
            // end, which is heard as harshness rather than as an echo.
            val alignedL = if (stemMode) dryDelayL.push(dryL) else dryL
            val alignedR = if (stemMode) dryDelayR.push(dryR) else dryR
            var outL = alignedL * dg + wetL * wg
            var outR = alignedR * dg + wetR * wg

            // --- loudness match: the render must not arrive louder than the source --
            val makeup = loudness.correction(alignedL, alignedR, outL, outR)
            outL *= makeup
            outR *= makeup

            io[i] = outL
            io[i + 1] = outR
            // Look-ahead limiting (gain is in place before the peak arrives, so no
            // distortion of its own) and then the oversampled clipper as a backstop for
            // inter-sample peaks only.
            lookahead.processFrame(io, i)
            io[i] = saturate(overL, io[i])
            io[i + 1] = saturate(overR, io[i + 1])
        }

        renderedFrames += frames
        val elapsedNs = System.nanoTime() - startNs
        val blockNs = frames.toDouble() / sampleRate * 1e9
        val cpu = if (blockNs > 0) (elapsedNs / blockNs * 100.0).toFloat() else 0f
        lastBlockCpuPercent = cpu

        // Graceful degradation: if the analysis path cannot keep up on this device,
        // fall back to the band renderer instead of dropping buffers.
        if (stemMode) {
            cpuAverage += 0.08f * (cpu - cpuAverage)
            if (cpuAverage > 70f) {
                overloadedBlocks++
                if (overloadedBlocks > 40) {
                    stemMode = false
                    analyserDegraded = true
                }
            } else if (overloadedBlocks > 0) {
                overloadedBlocks--
            }
        }
    }

    /**
     * §10.1: the only non-linearity in the chain runs at 2x so its harmonics stay in
     * band. [Dsp.softClip] is exactly linear below 0.7, so quiet material passes
     * through the oversampler untouched apart from its (linear-phase) group delay.
     */
    private fun saturate(os: Oversampler2x, x: Float): Float {
        os.up(x, overBuf)
        return os.down(Dsp.softClip(overBuf[0]), Dsp.softClip(overBuf[1]))
    }

    /** §14.1 support: the layout currently being rendered. */
    fun activeLayout(): SpeakerLayout = layout

    fun isBinauralising(): Boolean = binauralize
}
