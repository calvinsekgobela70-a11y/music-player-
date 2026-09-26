package com.ceecept.music.audio.spatial

import com.ceecept.music.audio.Biquad
import com.ceecept.music.audio.BiquadState
import com.ceecept.music.audio.process
import kotlin.math.abs

/**
 * Height channel and elevation perception — guidelines §5.
 *
 * Two mechanisms, selected by the active layout:
 *  - §5.1 direct routing to overhead speakers when the layout has a height layer;
 *  - §5.2 spectral coloration ("virtual height") when it does not.
 */
object Elevation {

    /** §5.1 threshold above which an object is considered "overhead". */
    const val HEIGHT_ROUTING_THRESHOLD_DEG = 30f

    /** §5.1 `normalize_elevation_gain` — how much of an object goes to the height layer. */
    fun heightLayerGain(elevationDeg: Float): Float =
        ((elevationDeg - HEIGHT_ROUTING_THRESHOLD_DEG) / (90f - HEIGHT_ROUTING_THRESHOLD_DEG))
            .coerceIn(0f, 1f)

    /**
     * §5.2 presence-band gain, 4 kHz. The guideline's formula is
     * `3 + (elevation / 30) * 6` dB above ear level (and mirrored below), described as
     * a 3 → 9 dB range.
     *
     * Deviation: the pedestal is removed (so 0° elevation is exactly 0 dB) and the curve
     * is clamped to the documented ±9 dB. Without that, every object — including ones at
     * ear level — is coloured by +3 dB at 4 kHz, which fails the ±2 dB frequency-flatness
     * criterion in §14.1.
     */
    fun hfBoostDb(elevationDeg: Float): Float {
        val el = elevationDeg.coerceIn(-90f, 90f)
        val db = if (el >= 0f) (el / 30f) * 6f else -(abs(el) / 30f) * 6f
        return db.coerceIn(-9f, 9f)
    }

    /** §5.2 low-shelf trim, 100 Hz: up to -4 dB above ear level, +4 dB below it. */
    fun lfCutDb(elevationDeg: Float): Float {
        val el = elevationDeg.coerceIn(-90f, 90f)
        return if (el >= 0f) -(el / 90f) * 4f else (abs(el) / 90f) * 4f
    }
}

/**
 * §5.2 virtual height filter: a 4 kHz peaking band plus a 100 Hz shelf, per ear.
 * [amount] scales the whole effect (the "height" user control).
 */
class VirtualHeightFilter {
    private val hf = Biquad()
    private val lf = Biquad()
    private val hfState = BiquadState()
    private val lfState = BiquadState()
    private var active = false

    fun configure(elevationDeg: Float, amount: Float, sampleRate: Int) {
        val a = amount.coerceIn(0f, 1.5f)
        val hfDb = Elevation.hfBoostDb(elevationDeg) * a
        val lfDb = Elevation.lfCutDb(elevationDeg) * a
        active = abs(hfDb) > 0.05f || abs(lfDb) > 0.05f
        if (!active) {
            hf.setIdentity()
            lf.setIdentity()
            return
        }
        // §5.2 design_parametric_eq: centre 4 kHz Q 1.0, centre 100 Hz Q 0.7.
        hf.setPeaking(4000f, 1.0f, hfDb, sampleRate)
        lf.setPeaking(100f, 0.7f, lfDb, sampleRate)
    }

    fun process(x: Float): Float {
        if (!active) return x
        return lf.process(hf.process(x, hfState), lfState)
    }

    fun clear() {
        hfState.clear()
        lfState.clear()
    }
}
