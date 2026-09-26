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
    val multiband: Boolean = true
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

        /** 1 centre object + one left/right pair per band. */
        const val OBJECTS = 1 + 2 * BANDS

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
    private val limiter = SoftLimiter()
    private val overL = Oversampler2x()
    private val overR = Oversampler2x()
    private val overBuf = FloatArray(2)

    private var dryGain = 0.7f
    private var wetGain = 0.7f

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
        limiter.configure(sr)
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
        limiter.reset()
        busses.fill(0f)
        orbitPhase = 0f
    }

    // ------------------------------------------------------------------ configure

    /** Recompute everything that depends on the scene. Runs once per block, not per sample. */
    private fun rebuild() {
        val sr = sampleRate
        val s = scene
        if (lfeIndex == -1) lfeIndex = layout.speakers.indexOfFirst { it.isLfe }

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

            val mid = (inL + inR) * 0.5f
            val side = (inL - inR) * 0.5f

            // --- §12 band decomposition of the stereo difference signal ---
            sideSplitter.process(side, bandBuf)

            // --- §4 object routing ---
            gains.tick()
            for (k in 0 until speakerCount) busses[k] = 0f

            var base = 0
            for (k in 0 until speakerCount) busses[k] += mid * current[k]
            for (b in 0 until BANDS) {
                val bandSample = bandBuf[b]
                if (bandSample == 0f) continue
                base = (1 + b * 2) * MAX_SPEAKERS
                for (k in 0 until speakerCount) busses[k] += bandSample * current[base + k]
                base += MAX_SPEAKERS
                for (k in 0 until speakerCount) busses[k] -= bandSample * current[base + k]
            }

            // --- bass management: the LFE feed is the mono low band (§13 hints) ---
            if (lfeIndex >= 0) {
                busses[lfeIndex] += subLow2.process(subLow.process(mid, subLowState), subLowState2)
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

            // --- mix, then §11.1 anti-clipping, then §10.1 oversampled safety clip ---
            io[i] = dryL * dg + wetL * wg
            io[i + 1] = dryR * dg + wetR * wg
            // The limiter is a smoothed gain (linear, so it cannot alias) and holds the
            // signal at -0.1 dBFS; the saturator behind it only ever catches
            // inter-sample peaks, which keeps its distortion products tiny and lets 2x
            // oversampling suppress them by >50 dB.
            limiter.processFrame(io, i)
            io[i] = saturate(overL, io[i])
            io[i + 1] = saturate(overR, io[i + 1])
        }

        renderedFrames += frames
        val elapsedNs = System.nanoTime() - startNs
        val blockNs = frames.toDouble() / sampleRate * 1e9
        lastBlockCpuPercent = if (blockNs > 0) (elapsedNs / blockNs * 100.0).toFloat() else 0f
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
