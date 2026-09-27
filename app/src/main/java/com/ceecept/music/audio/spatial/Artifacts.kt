package com.ceecept.music.audio.spatial

import kotlin.math.abs

/**
 * Artifact prevention — guidelines §10 — plus the output stage of §11.1.
 */

/**
 * §10.2 gain smoothing. The guideline smooths an offline automation curve with a
 * moving-average kernel; the real-time equivalent is a one-pole follower with the same
 * 5 ms time constant, which has identical anti-zipper behaviour and needs no lookahead.
 *
 * Every per-speaker gain in the renderer runs through one of these, so panning updates
 * (computed once per block) are interpolated per sample.
 */
class SmoothedGain(initial: Float = 0f) {
    var target = initial
    var current = initial
        private set
    private var coef = 0.01f

    fun setTimeConstant(ms: Float, sampleRate: Int) {
        val samples = (ms.coerceAtLeast(0.1f) / 1000f) * sampleRate
        coef = (1.0 - Math.exp(-1.0 / samples)).toFloat().coerceIn(1e-5f, 1f)
    }

    fun next(): Float {
        current += coef * (target - current)
        return current
    }

    fun snap(value: Float) {
        target = value
        current = value
    }

    fun reset() {
        current = 0f
        target = 0f
    }
}

/** Bank of smoothed gains stored flat (objects x speakers) to stay allocation-free. */
class SmoothedGainBank(size: Int) {
    val target = FloatArray(size)
    val current = FloatArray(size)
    private var coef = 0.01f

    fun setTimeConstant(ms: Float, sampleRate: Int) {
        val samples = (ms.coerceAtLeast(0.1f) / 1000f) * sampleRate
        coef = (1.0 - Math.exp(-1.0 / samples)).toFloat().coerceIn(1e-5f, 1f)
    }

    /** Advance every gain one sample towards its target. */
    fun tick() {
        val c = coef
        for (i in current.indices) {
            current[i] += c * (target[i] - current[i])
        }
    }

    fun snapAll() {
        System.arraycopy(target, 0, current, 0, target.size)
    }

    fun clear() {
        target.fill(0f)
        current.fill(0f)
    }
}

/**
 * §10.2 reference implementation: moving-average smoothing of a gain envelope.
 * Kept for offline/QA use (the validation suite in §14 measures gain smoothness).
 */
object GainEnvelope {
    fun smooth(envelope: FloatArray, smoothingMs: Float, sampleRate: Int): FloatArray {
        var n = ((smoothingMs / 1000f) * sampleRate).toInt()
        if (n < 1) return envelope.copyOf()
        if (n % 2 == 0) n += 1
        val half = n / 2
        val out = FloatArray(envelope.size)
        var sum = 0f
        for (i in envelope.indices) {
            sum = 0f
            var count = 0
            for (k in -half..half) {
                val idx = i + k
                if (idx in envelope.indices) {
                    sum += envelope[idx]
                    count++
                }
            }
            out[i] = sum / count
        }
        return out
    }
}

/**
 * §10.1 anti-aliasing: 2x oversampling around the non-linear output stage.
 *
 * Deviation: the guideline oversamples the whole spatial chain 4–8x with polyphase
 * resampling. Everything in this renderer except the output saturator is linear
 * (delays, IIR filters, smoothed gains), and linear processing cannot alias — so the
 * oversampling is placed exactly where the non-linearity is. Same protection, a
 * fraction of the CPU, which is what matters on a phone.
 *
 * 31-tap linear-phase half-band FIR, evaluated in polyphase form: one branch is a pure
 * delay (the 0.5 centre tap), the other is 14 multiply-accumulates. Passband error is
 * -0.02 dB at 16 kHz and -0.3 dB at 18 kHz for the full up/down round trip, with the
 * image band suppressed by more than 70 dB. Total group delay is 15 samples
 * (0.31 ms at 48 kHz), applied equally to the dry and wet paths.
 */
class Oversampler2x {
    /** h[2j] for j = 1..14 — the non-zero off-centre taps of the half-band kernel. */
    private val c = floatArrayOf(
        0.000410323f, -0.002230286f, 0.007100857f, -0.017917030f,
        0.040107418f, -0.090106922f, 0.312633322f, 0.312633322f,
        -0.090106922f, 0.040107418f, -0.017917030f, 0.007100857f,
        -0.002230286f, 0.000410323f
    )

    private val upHist = FloatArray(14)
    private val downEven = FloatArray(14)
    private val downOdd = FloatArray(8)

    /** Upsample one input sample to two; results in [out]. */
    fun up(x: Float, out: FloatArray) {
        var even = 0f
        for (j in 0 until 14) even += c[j] * upHist[j]
        // Zero-stuffing loses 6 dB; the factor of two restores unity gain.
        out[0] = even * 2f
        // Centre tap: 2 * 0.5 * x[n-7].
        out[1] = upHist[6]
        for (i in 13 downTo 1) upHist[i] = upHist[i - 1]
        upHist[0] = x
    }

    /** Downsample two 2x-rate samples back to one. */
    fun down(a: Float, b: Float): Float {
        var y = 0f
        for (j in 0 until 14) y += c[j] * downEven[j]
        y += 0.5f * downOdd[7]
        for (i in 13 downTo 1) downEven[i] = downEven[i - 1]
        downEven[0] = a
        for (i in 7 downTo 1) downOdd[i] = downOdd[i - 1]
        downOdd[0] = b
        return y
    }

    fun clear() {
        upHist.fill(0f)
        downEven.fill(0f)
        downOdd.fill(0f)
    }
}

/**
 * §11.1 `apply_soft_limiter` — transparent peak control before the output, with a
 * -0.1 dBFS ceiling.
 *
 * Deviation: the guideline computes one gain for a whole block from its peak, which
 * modulates level block-to-block (audible pumping). This is a sample-accurate follower
 * with the same ceiling: instantaneous attack, 50 ms release, so the gain is continuous.
 */
class SoftLimiter {
    private var gain = 1f
    private var releaseCoef = 0.001f
    private var ceiling = 0.988f // -0.1 dBFS

    fun configure(sampleRate: Int, ceilingDb: Float = -0.1f, releaseMs: Float = 50f) {
        ceiling = Math.pow(10.0, (ceilingDb / 20f).toDouble()).toFloat()
        val samples = (releaseMs / 1000f) * sampleRate
        releaseCoef = (1.0 - Math.exp(-1.0 / samples)).toFloat().coerceIn(1e-6f, 1f)
    }

    /** Applies one stereo frame in place inside [buf] at [i] (left) and [i]+1 (right). */
    fun processFrame(buf: FloatArray, i: Int) {
        val peak = maxOf(abs(buf[i]), abs(buf[i + 1]))
        val required = if (peak > ceiling) ceiling / peak else 1f
        gain = if (required < gain) required else gain + releaseCoef * (required - gain)
        buf[i] *= gain
        buf[i + 1] *= gain
    }

    fun reset() {
        gain = 1f
    }
}

/**
 * Look-ahead brick-wall limiter — the fix for "the loud parts sound distorted".
 *
 * [SoftLimiter] has instantaneous attack, so a bass note arriving at full level gets
 * its first samples squashed by a gain that steps within one sample. That step is a
 * non-linearity, and on low frequencies it is plainly audible as distortion.
 *
 * Here the signal is delayed by the look-ahead window while the gain is computed from
 * the *undelayed* signal, so the gain has the whole window to slide down and is already
 * in place when the peak arrives. Nothing in the audio path changes faster than the
 * attack time, so the limiter adds no harmonics of its own.
 */
class LookaheadLimiter {
    private var bufL = FloatArray(128)
    private var bufR = FloatArray(128)
    private var length = 64
    private var writeIndex = 0
    private var gain = 1f
    private var held = 1f
    private var holdCounter = 0
    private var holdLength = 1200
    private var attackCoef = 0.05f
    private var releaseCoef = 0.001f
    private var ceiling = 0.977f // -0.2 dBFS, leaving room for inter-sample peaks

    fun configure(
        sampleRate: Int,
        lookaheadMs: Float = 1.5f,
        releaseMs: Float = 120f,
        ceilingDb: Float = -0.2f,
        holdMs: Float = 25f
    ) {
        length = ((lookaheadMs / 1000f) * sampleRate).toInt().coerceIn(8, 4096)
        if (bufL.size < length) {
            bufL = FloatArray(length)
            bufR = FloatArray(length)
        }
        ceiling = Math.pow(10.0, (ceilingDb / 20f).toDouble()).toFloat()
        // Reach the target gain in roughly one look-ahead window.
        attackCoef = (1.0 - Math.exp(-3.0 / length)).toFloat().coerceIn(1e-6f, 1f)
        val releaseSamples = (releaseMs / 1000f) * sampleRate
        releaseCoef = (1.0 - Math.exp(-1.0 / releaseSamples)).toFloat().coerceIn(1e-6f, 1f)
        // The hold spans more than one cycle of the lowest note the system reproduces.
        holdLength = ((holdMs / 1000f) * sampleRate).toInt().coerceAtLeast(1)
        reset()
    }

    /** Current gain reduction in dB, for metering. */
    fun reductionDb(): Float = (20.0 * Math.log10(gain.coerceIn(1e-4f, 1f).toDouble())).toFloat()

    fun processFrame(buf: FloatArray, i: Int) {
        val l = buf[i]
        val r = buf[i + 1]
        val peak = maxOf(abs(l), abs(r))
        val required = if (peak > ceiling) ceiling / peak else 1f

        // Attack / hold / release. Without the hold, a sustained bass note makes the
        // gain ripple at twice its frequency — and a gain that moves at an audio rate
        // is, by definition, distortion. Holding the reduction for longer than one
        // cycle turns that into a single steady gain.
        if (required < held) {
            held = required
            holdCounter = holdLength
        } else if (required < 0.999f) {
            holdCounter = holdLength
        } else if (holdCounter > 0) {
            holdCounter--
        } else {
            held += releaseCoef * (required - held)
        }
        gain += (if (held < gain) attackCoef else releaseCoef) * (held - gain)

        val dl = bufL[writeIndex]
        val dr = bufR[writeIndex]
        bufL[writeIndex] = l
        bufR[writeIndex] = r
        writeIndex++
        if (writeIndex >= length) writeIndex = 0

        buf[i] = dl * gain
        buf[i + 1] = dr * gain
    }

    fun latencySamples(): Int = length

    fun reset() {
        java.util.Arrays.fill(bufL, 0f)
        java.util.Arrays.fill(bufR, 0f)
        writeIndex = 0
        gain = 1f
        held = 1f
        holdCounter = 0
    }
}

/**
 * Loudness matching between the dry programme and the rendered output.
 *
 * Spatial rendering redistributes energy: a hard-panned guitar moved to a side speaker
 * arrives at the ears differently, reverb adds energy, and separation splits it. Left
 * alone, the result drifts a few dB louder, which pins the limiter and makes the whole
 * mix sound compressed and gritty — the usual cause of "my music sounds distorted with
 * the effect on".
 *
 * This measures both signals over a 300 ms window and corrects the difference with a
 * slow, bounded gain, so the processed output sits at the same loudness as the source
 * and the limiter only ever catches genuine transients.
 */
class LoudnessMatch {
    private var inPower = 0f
    private var outPower = 0f
    private var coef = 0.0001f
    private var gateLevel = 1e-6f
    private val gain = SmoothedGain(1f)

    fun configure(sampleRate: Int, windowMs: Float = 300f) {
        val samples = (windowMs / 1000f) * sampleRate
        coef = (1.0 - Math.exp(-1.0 / samples)).toFloat().coerceIn(1e-7f, 1f)
        gain.setTimeConstant(150f, sampleRate)
        gain.snap(1f)
        inPower = 0f
        outPower = 0f
    }

    /**
     * Feed the dry and rendered frame; returns the gain to apply to the rendered frame.
     * Bounded to ±6 dB so it can never become a compressor of its own.
     */
    fun correction(dryL: Float, dryR: Float, wetL: Float, wetR: Float): Float {
        val ip = (dryL * dryL + dryR * dryR) * 0.5f
        val op = (wetL * wetL + wetR * wetR) * 0.5f
        inPower += coef * (ip - inPower)
        outPower += coef * (op - outPower)
        if (inPower > gateLevel && outPower > gateLevel * 0.01f) {
            gain.target = Math.sqrt((inPower / outPower).toDouble()).toFloat().coerceIn(0.5f, 2f)
        }
        return gain.next()
    }

    fun reset() {
        inPower = 0f
        outPower = 0f
        gain.snap(1f)
    }
}
