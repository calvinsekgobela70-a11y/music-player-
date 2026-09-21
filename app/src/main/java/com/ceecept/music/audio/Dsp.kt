package com.ceecept.music.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared DSP math for the Ceecept audio engine. All processing is 32-bit float. */
object Dsp {
    fun dbToLinear(db: Float): Float = Math.pow(10.0, (db / 20f).toDouble()).toFloat()

    fun linearToDb(x: Float): Float = (20f * log10(x.coerceAtLeast(1e-9f))).coerceIn(-120f, 60f)

    /** One-pole low-pass coefficient for cutoff [fc] at sample rate [sr]. */
    fun onePoleLowPassCoef(fc: Float, sr: Int): Float {
        val x = 2f * PI.toFloat() * fc / sr
        return (x / (1f + x)).coerceIn(0f, 1f)
    }

    /** Exponential envelope coefficient for a time constant in milliseconds. */
    fun envelopeCoef(timeMs: Float, sr: Int): Float {
        val t = (timeMs.coerceAtLeast(0.01f) / 1000f) * sr
        return (1f - Math.exp(-1.0 / t)).toFloat()
    }

    fun softClip(x: Float): Float {
        // Fast tanh approximation, transparent below ~0.7.
        val ax = kotlin.math.abs(x)
        return if (ax < 0.7f) x else kotlin.math.sign(x) * (0.7f + (ax - 0.7f) / (1f + (ax - 0.7f) * 1.4f))
    }
}

/** RBJ cookbook biquad. Coefficients only — state lives in [BiquadState] (one per channel). */
class Biquad {
    var b0 = 1f
    var b1 = 0f
    var b2 = 0f
    var a1 = 0f
    var a2 = 0f

    fun setIdentity() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
    }

    fun setPeaking(f0: Float, q: Float, gainDb: Float, sr: Int) {
        if (gainDb == 0f) {
            setIdentity()
            return
        }
        val a = Math.pow(10.0, gainDb / 40.0)
        val w = 2.0 * PI * f0.coerceIn(10f, sr / 2.2f) / sr
        val alpha = sin(w) / (2.0 * q)
        val cw = cos(w)
        val a0 = 1.0 + alpha / a
        b0 = ((1.0 + alpha * a) / a0).toFloat()
        b1 = ((-2.0 * cw) / a0).toFloat()
        b2 = ((1.0 - alpha * a) / a0).toFloat()
        a1 = ((-2.0 * cw) / a0).toFloat()
        a2 = ((1.0 - alpha / a) / a0).toFloat()
    }

    fun setLowPass(f0: Float, q: Float, sr: Int) {
        val w = 2.0 * PI * f0.coerceIn(10f, sr / 2.2f) / sr
        val alpha = sin(w) / (2.0 * q)
        val cw = cos(w)
        val a0 = 1.0 + alpha
        b0 = (((1.0 - cw) / 2.0) / a0).toFloat()
        b1 = (((1.0 - cw)) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cw) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun setHighPass(f0: Float, q: Float, sr: Int) {
        val w = 2.0 * PI * f0.coerceIn(10f, sr / 2.2f) / sr
        val alpha = sin(w) / (2.0 * q)
        val cw = cos(w)
        val a0 = 1.0 + alpha
        b0 = (((1.0 + cw) / 2.0) / a0).toFloat()
        b1 = ((-(1.0 + cw)) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cw) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /** Complex magnitude response at frequency [f] — used to draw the EQ curve. */
    fun magnitudeDb(f: Float, sr: Int): Float {
        val w = 2.0 * PI * f / sr
        val cw = cos(w)
        val sw = sin(w)
        val cw2 = cos(2 * w)
        val sw2 = sin(2 * w)
        val numR = b0 + b1 * cw + b2 * cw2
        val numI = -(b1 * sw + b2 * sw2)
        val denR = 1.0 + a1 * cw + a2 * cw2
        val denI = -(a1 * sw + a2 * sw2)
        val mag = sqrt(numR * numR + numI * numI) / sqrt(denR * denR + denI * denI).coerceAtLeast(1e-9)
        return (20.0 * log10(mag.coerceAtLeast(1e-9))).toFloat()
    }
}

class BiquadState {
    var d1 = 0f
    var d2 = 0f
    fun clear() {
        d1 = 0f; d2 = 0f
    }
}

/** Transposed Direct Form II — numerically stable for low-frequency bands. */
fun Biquad.process(x: Float, s: BiquadState): Float {
    val y = b0 * x + s.d1
    s.d1 = b1 * x - a1 * y + s.d2
    s.d2 = b2 * x - a2 * y
    // Flush denormals to keep the CPU fast path.
    if (y != 0f && kotlin.math.abs(y) < 1e-18f) {
        s.d1 = 0f; s.d2 = 0f
        return 0f
    }
    return y
}

/** Fractional delay line with linear interpolation. Not thread-safe (audio thread only). */
class DelayLine(maxDelaySamples: Int) {
    private val buf = FloatArray(maxDelaySamples + 4)
    private var w = 0

    /** Delay in samples, fractional. Clamped to the buffer size. */
    var delay = 0f
        set(value) {
            field = value.coerceIn(0f, (buf.size - 4).toFloat())
        }

    fun push(x: Float): Float {
        val y = peek()
        store(x)
        return y
    }

    /** Read the delayed sample without advancing the write pointer. */
    fun peek(): Float {
        var r = w - delay
        val n = buf.size
        while (r < 0) r += n
        while (r >= n) r -= n
        val i0 = r.toInt()
        val frac = r - i0
        val i1 = if (i0 + 1 < n) i0 + 1 else 0
        return buf[i0] * (1f - frac) + buf[i1] * frac
    }

    /** Write a sample and advance, without reading. */
    fun store(x: Float) {
        buf[w] = x
        w++
        if (w >= buf.size) w = 0
    }

    fun clear() {
        buf.fill(0f)
    }
}

/** Peak envelope follower with independent attack / release. */
class Envelope {
    var value = 0f

    fun process(x: Float, attackCoef: Float, releaseCoef: Float): Float {
        val coef = if (x > value) attackCoef else releaseCoef
        value += coef * (x - value)
        return value
    }

    fun reset() {
        value = 0f
    }
}
