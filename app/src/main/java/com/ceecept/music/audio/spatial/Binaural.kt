package com.ceecept.music.audio.spatial

import com.ceecept.music.audio.Biquad
import com.ceecept.music.audio.BiquadState
import com.ceecept.music.audio.DelayLine
import com.ceecept.music.audio.process
import com.ceecept.music.audio.setHighShelf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Binaural rendering for headphones — guidelines §6.
 *
 * Deviation from §6.1: the guideline convolves every object with measured CIPIC/KEMAR
 * impulse responses. Ceecept is an offline, permission-free phone app — shipping and
 * FFT-convolving an HRTF database per object is neither licence- nor CPU-friendly, and
 * the quantisation to 15° steps in §6.1 makes panning audibly step anyway.
 *
 * Instead this is a *parametric* HRTF built from the three mechanisms §6.2 says the
 * brain actually uses, evaluated continuously (no angle quantisation):
 *   1. ITD — `(head_width / c) * sin(azimuth)`, scaled by cos(elevation).
 *   2. ILD — frequency dependent: negligible below ~1 kHz, growing with angle above it
 *      (§6.2 `calculate_ild`), realised as a high-shelf on the contralateral ear.
 *   3. Spectral coloration — concha resonance, elevation-dependent pinna notch and the
 *      front/back high-frequency difference.
 *
 * It is applied to a *fixed* set of virtual speakers rather than to objects, so the cost
 * is constant (12 filter pairs for 7.1.4) no matter how many objects are in the scene.
 */
class BinauralVirtualizer(private val maxSpeakers: Int) {

    private var sampleRate = 48000
    private var count = 0

    private var delayL: Array<DelayLine> = emptyArray()
    private var delayR: Array<DelayLine> = emptyArray()

    private val shelfL = Array(maxSpeakers) { Biquad() }
    private val shelfR = Array(maxSpeakers) { Biquad() }
    private val shelfStateL = Array(maxSpeakers) { BiquadState() }
    private val shelfStateR = Array(maxSpeakers) { BiquadState() }

    private val conchaL = Array(maxSpeakers) { Biquad() }
    private val conchaR = Array(maxSpeakers) { Biquad() }
    private val conchaStateL = Array(maxSpeakers) { BiquadState() }
    private val conchaStateR = Array(maxSpeakers) { BiquadState() }

    private val notchL = Array(maxSpeakers) { Biquad() }
    private val notchR = Array(maxSpeakers) { Biquad() }
    private val notchStateL = Array(maxSpeakers) { BiquadState() }
    private val notchStateR = Array(maxSpeakers) { BiquadState() }

    private val gainL = FloatArray(maxSpeakers)
    private val gainR = FloatArray(maxSpeakers)

    fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        val maxDelay = (0.0015f * sampleRate).toInt() + 8
        delayL = Array(maxSpeakers) { DelayLine(maxDelay) }
        delayR = Array(maxSpeakers) { DelayLine(maxDelay) }
    }

    /**
     * Configure one filter pair per speaker of [layout]. [elevationBoost] scales the
     * height cue (user "height" control); [strength] scales the whole HRTF colouring so
     * the effect can be dialled back towards plain stereo.
     */
    /**
     * Imaging: multiplies the inter-aural cues. Above 1 the head is effectively made
     * larger, which exaggerates ITD and ILD and makes directions easier to point at —
     * the single most effective control for "where is this coming from?".
     */
    var cueScale = 1f

    fun configure(layout: SpeakerLayout, strength: Float, elevationBoost: Float) {
        count = minOf(layout.totalSpeakers, maxSpeakers)
        val s = strength.coerceIn(0f, 1f)
        // Common delay so that both ears can be delayed relative to each other.
        val maxItdSec = Geometry.HEAD_WIDTH_M / Geometry.SPEED_OF_SOUND
        val baseDelay = maxItdSec * 0.5f * sampleRate

        for (i in 0 until count) {
            val sp = layout.speakers[i]
            val azRad = Geometry.rad(sp.azimuthDeg)
            val elRad = Geometry.rad(sp.elevationDeg)

            // --- 1. Interaural time difference (§6.2) -------------------------------
            // Positive = source on the right, so the left (far) ear is delayed.
            val itdSec = (Geometry.HEAD_WIDTH_M / Geometry.SPEED_OF_SOUND) * sin(azRad) * cos(elRad)
            val itdSamples = itdSec * sampleRate * s * cueScale
            delayL[i].delay = (baseDelay + itdSamples * 0.5f).coerceAtLeast(0f)
            delayR[i].delay = (baseDelay - itdSamples * 0.5f).coerceAtLeast(0f)

            // --- 2. Interaural level difference (§6.2) ------------------------------
            // |sin(az)| drives the head shadow; the shelf leaves LF untouched, which is
            // exactly the "ild_db = 0 below 1 kHz" behaviour in calculate_ild().
            val shadow = abs(sin(azRad)) * cos(elRad)
            val ildDb = shadow * 12f * s * cueScale
            val right = sp.azimuthDeg > 0f
            // Contralateral ear (opposite the source) loses high frequencies.
            shelfL[i].setHighShelf(1200f, if (right) -ildDb else 0f, sampleRate)
            shelfR[i].setHighShelf(1200f, if (right) 0f else -ildDb, sampleRate)
            // Broadband component of the ILD, small compared with the spectral part.
            val broadband = 1f - 0.22f * shadow * s
            gainL[i] = if (right) broadband else 1f
            gainR[i] = if (right) 1f else broadband

            // --- 3. Spectral coloration (§6.2 / §5.2) -------------------------------
            // Concha resonance ~4 kHz gives externalisation.
            val conchaDb = 3.0f * s
            conchaL[i].setPeaking(4200f, 1.1f, conchaDb, sampleRate)
            conchaR[i].setPeaking(4300f, 1.1f, conchaDb, sampleRate)
            // Pinna notch climbs 6 -> 11 kHz as the source rises: the dominant
            // elevation cue, reinforced by the §5.2 presence boost.
            val elNorm = ((sp.elevationDeg + 40f) / 130f).coerceIn(0f, 1f)
            val notchHz = 6000f + elNorm * 5000f
            val behind = abs(sp.azimuthDeg) > 90f
            // Sources behind the head lose HF (the pinna faces forward).
            val notchDb = (-7f - if (behind) 3f else 0f) * s
            notchL[i].setPeaking(notchHz, 3.2f, notchDb, sampleRate)
            notchR[i].setPeaking(notchHz * 1.02f, 3.2f, notchDb, sampleRate)

            // Height speakers additionally get the §5.2 virtual-height tilt, scaled by
            // the user's height control.
            if (sp.elevationDeg > Elevation.HEIGHT_ROUTING_THRESHOLD_DEG && elevationBoost > 0f) {
                val boost = Elevation.hfBoostDb(sp.elevationDeg) * elevationBoost * 0.5f
                conchaL[i].setPeaking(4200f, 1.1f, conchaDb + boost, sampleRate)
                conchaR[i].setPeaking(4300f, 1.1f, conchaDb + boost, sampleRate)
            }
        }
    }

    /**
     * Render one frame: [busses] holds one sample per speaker, the binaural result is
     * accumulated into [out] (index 0 = left ear, 1 = right ear).
     */
    fun processFrame(busses: FloatArray, out: FloatArray) {
        var l = 0f
        var r = 0f
        if (delayL.isEmpty()) {
            // prepare() has not run yet: pass the first two busses through rather
            // than dropping audio.
            out[0] = if (busses.isNotEmpty()) busses[0] else 0f
            out[1] = if (busses.size > 1) busses[1] else 0f
            return
        }
        for (i in 0 until count) {
            val x = busses[i]
            var yl = delayL[i].push(x)
            var yr = delayR[i].push(x)
            yl = shelfL[i].process(yl, shelfStateL[i])
            yr = shelfR[i].process(yr, shelfStateR[i])
            yl = conchaL[i].process(yl, conchaStateL[i])
            yr = conchaR[i].process(yr, conchaStateR[i])
            yl = notchL[i].process(yl, notchStateL[i])
            yr = notchR[i].process(yr, notchStateR[i])
            l += yl * gainL[i]
            r += yr * gainR[i]
        }
        out[0] = l
        out[1] = r
    }

    fun clear() {
        for (i in 0 until maxSpeakers) {
            if (i < delayL.size) {
                delayL[i].clear()
                delayR[i].clear()
            }
            shelfStateL[i].clear(); shelfStateR[i].clear()
            conchaStateL[i].clear(); conchaStateR[i].clear()
            notchStateL[i].clear(); notchStateR[i].clear()
        }
    }
}

/**
 * Loudspeaker fold-down: when the output is a phone speaker or a stereo line-out we must
 * not apply HRTF (the listener's own head already does that). Speaker feeds are summed
 * with the §4.2 tangential law so the energy of surround/height channels is preserved.
 */
class StereoFolddown(private val maxSpeakers: Int) {
    private val gainL = FloatArray(maxSpeakers)
    private val gainR = FloatArray(maxSpeakers)
    private var count = 0

    fun configure(layout: SpeakerLayout) {
        count = minOf(layout.totalSpeakers, maxSpeakers)
        for (i in 0 until count) {
            val sp = layout.speakers[i]
            val azRad = Geometry.rad(sp.azimuthDeg)
            // Constant-power position on the L..R axis, height layer slightly recessed.
            val pan = ((sin(azRad) + 1f) / 2f).coerceIn(0f, 1f)
            val theta = pan * (Math.PI.toFloat() / 2f)
            val trim = if (sp.elevationDeg > 30f) 0.7f else 1f
            gainL[i] = cos(theta) * trim
            gainR[i] = sin(theta) * trim
        }
    }

    fun processFrame(busses: FloatArray, out: FloatArray) {
        var l = 0f
        var r = 0f
        for (i in 0 until count) {
            l += busses[i] * gainL[i]
            r += busses[i] * gainR[i]
        }
        out[0] = l
        out[1] = r
    }
}
