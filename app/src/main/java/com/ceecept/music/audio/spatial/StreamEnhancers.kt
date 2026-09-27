package com.ceecept.music.audio.spatial

import com.ceecept.music.audio.Biquad
import com.ceecept.music.audio.BiquadState
import com.ceecept.music.audio.DelayLine
import com.ceecept.music.audio.Dsp
import com.ceecept.music.audio.process
import com.ceecept.music.audio.setAllPass
import com.ceecept.music.audio.setHighShelf
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Per-stream enhancement.
 *
 * Splitting the programme into streams is what makes this possible: each processor only
 * ever sees the material it was designed for, so the vocal EQ never touches the bass,
 * the transient shaper never grabs a sustained pad, and the bass harmonics are generated
 * from bass alone instead of from a full mix (which is what makes conventional "bass
 * boost" sound like mud).
 */

/**
 * Psychoacoustic bass: the *missing fundamental*.
 *
 * Phones, laptops and earbuds cannot move enough air below ~100 Hz, so boosting the
 * fundamental just wastes headroom and drives the limiter — which is exactly how a
 * "loud" player ends up distorting. Instead we synthesise the harmonic series of the
 * bass note in the 100–500 Hz range, where small transducers are efficient; the ear
 * reconstructs the missing fundamental and hears a deeper, louder bass at a *lower*
 * peak level (Oo & Gan, virtual bass systems; multiband harmonic generation, 2013).
 *
 * Even harmonics come from a squarer and odd harmonics from a soft saturator, per the
 * usual recommendation to combine two non-linear devices; the harmonic level tracks the
 * envelope of the source band so the effect never runs away on quiet passages.
 */
class VirtualBass {
    private val bandLow = Biquad()
    private val bandLowState = BiquadState()
    private val bandHigh = Biquad()
    private val bandHighState = BiquadState()
    private val postLow = Biquad()
    private val postLowState = BiquadState()
    private val postHigh = Biquad()
    private val postHighState = BiquadState()
    private var srcEnv = 0f
    private var genEnv = 0f
    private var envCoef = 0.002f
    private var dcState = 0f

    /** 0 = off, 1 = full harmonic series. */
    var amount = 0f

    fun prepare(sampleRate: Int) {
        // Source band: the part of the bass that small speakers cannot reproduce.
        bandHigh.setHighPass(35f, 0.7071f, sampleRate)
        bandLow.setLowPass(110f, 0.7071f, sampleRate)
        // Keep only the generated harmonics, in the band where the ear does the work.
        postHigh.setHighPass(110f, 0.7071f, sampleRate)
        postLow.setLowPass(520f, 0.7071f, sampleRate)
        envCoef = Dsp.envelopeCoef(25f, sampleRate)
        reset()
    }

    fun reset() {
        bandLowState.clear(); bandHighState.clear()
        postLowState.clear(); postHighState.clear()
        srcEnv = 0f; genEnv = 0f; dcState = 0f
    }

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val src = bandLow.process(bandHigh.process(x, bandHighState), bandLowState)
        val a = abs(src)
        srcEnv += envCoef * (a - srcEnv)

        // Two non-linear devices: x² contributes the even harmonics, tanh the odd ones.
        val even = src * src * 4f
        val odd = tanh(src * 3.2f)
        var gen = 0.5f * even + 0.5f * odd
        // Remove the DC the squarer introduces before it reaches the speaker.
        dcState += 0.0008f * (gen - dcState)
        gen -= dcState
        gen = postLow.process(postHigh.process(gen, postHighState), postLowState)

        val g = abs(gen)
        genEnv += envCoef * (g - genEnv)
        // Level the harmonics against the source band so the effect is programme-independent.
        val norm = if (genEnv > 1e-5f) (srcEnv / genEnv).coerceIn(0f, 4f) else 0f
        return x + gen * norm * amount * 0.9f
    }
}

/**
 * Vocal clarity: a presence lift that only opens when a vocal is actually present, an
 * air shelf, and a de-esser so the lift does not turn every "s" into a spit.
 *
 * Because this only ever runs on the LEAD stream, the presence band can be pushed much
 * harder than a full-mix EQ would allow before cymbals and guitars turn harsh.
 */
class VocalEnhancer {
    private val presence = Biquad()
    private val presenceState = BiquadState()
    private val air = Biquad()
    private val airState = BiquadState()
    private val sibilance = Biquad()
    private val sibilanceState = BiquadState()
    private var sibEnv = 0f
    private var bodyEnv = 0f
    private var attack = 0.01f
    private var release = 0.001f

    /** 0 = flat, 1 = full presence lift. */
    var amount = 0f

    fun prepare(sampleRate: Int) {
        presence.setPeaking(2900f, 0.9f, 4.5f, sampleRate)
        air.setHighShelf(9000f, 3.0f, sampleRate)
        sibilance.setHighPass(6200f, 0.7071f, sampleRate)
        attack = Dsp.envelopeCoef(2f, sampleRate)
        release = Dsp.envelopeCoef(90f, sampleRate)
        reset()
    }

    fun reset() {
        presenceState.clear(); airState.clear(); sibilanceState.clear()
        sibEnv = 0f; bodyEnv = 0f
    }

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val lifted = air.process(presence.process(x, presenceState), airState)
        var y = x + (lifted - x) * amount

        // De-esser: compare the sibilant band against the body of the voice and duck
        // the excess, rather than shelving the whole top end down.
        val sib = sibilance.process(y, sibilanceState)
        val sa = abs(sib)
        sibEnv += (if (sa > sibEnv) attack else release) * (sa - sibEnv)
        val ba = abs(y)
        bodyEnv += (if (ba > bodyEnv) attack else release) * (ba - bodyEnv)
        val ratio = if (bodyEnv > 1e-5f) sibEnv / bodyEnv else 0f
        if (ratio > 0.35f) {
            val excess = ((ratio - 0.35f) / 0.65f).coerceIn(0f, 1f)
            y -= sib * excess * 0.7f * amount
        }
        return y
    }
}

/**
 * Transient shaper for the percussive streams: the difference between a fast and a slow
 * envelope is, by construction, the attack of every hit. Emphasising it makes drums
 * snap without touching their level, which is what keeps them audible once they have
 * been spread around the room.
 */
class TransientShaper {
    private var fast = 0f
    private var slow = 0f
    private var fastCoef = 0.05f
    private var slowCoef = 0.002f

    /** 0 = untouched, 1 = strong attack emphasis. */
    var amount = 0f

    fun prepare(sampleRate: Int) {
        fastCoef = Dsp.envelopeCoef(1.5f, sampleRate)
        slowCoef = Dsp.envelopeCoef(55f, sampleRate)
        reset()
    }

    fun reset() {
        fast = 0f; slow = 0f
    }

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val a = abs(x)
        fast += fastCoef * (a - fast)
        slow += slowCoef * (a - slow)
        val attack = (fast - slow).coerceAtLeast(0f)
        val boost = 1f + (attack / (slow + 1e-4f)).coerceIn(0f, 1.5f) * amount * 0.8f
        return x * boost
    }
}

/**
 * Decorrelator for the wide and ambient streams.
 *
 * A chain of all-pass sections with mutually prime tunings scrambles phase without
 * touching magnitude, so the left and right copies of a pad stop being the same signal
 * and the stereo image opens up — with none of the comb filtering a delay would cause,
 * and no level change at all.
 */
class Decorrelator(private val seed: Int) {
    private val stages = Array(4) { Biquad() }
    private val states = Array(4) { BiquadState() }
    private var delay = DelayLine(64)

    fun prepare(sampleRate: Int) {
        val base = if (seed % 2 == 0) 420f else 610f
        val ratios = floatArrayOf(1f, 2.31f, 4.77f, 9.13f)
        for (i in stages.indices) {
            stages[i].setAllPass(base * ratios[i] * (1f + 0.07f * seed), 0.55f + 0.1f * i, sampleRate)
        }
        delay = DelayLine((0.01f * sampleRate).toInt() + 8)
        // Sub-millisecond offsets only: enough to break coherence, short enough that the
        // image stays put and no echo is audible.
        delay.delay = (0.0003f + 0.00017f * seed) * sampleRate
        reset()
    }

    fun reset() {
        for (s in states) s.clear()
        delay.clear()
    }

    /** [amount] crossfades between the original and the decorrelated copy. */
    fun process(x: Float, amount: Float): Float {
        if (amount <= 0.001f) return x
        var y = delay.push(x)
        for (i in stages.indices) y = stages[i].process(y, states[i])
        return x + (y - x) * amount.coerceIn(0f, 1f)
    }
}

/** Gentle high shelf used to open up the height/air streams. */
class AirLift {
    private val shelf = Biquad()
    private val state = BiquadState()
    var amount = 0f

    fun prepare(sampleRate: Int) {
        shelf.setHighShelf(7500f, 4.5f, sampleRate)
        state.clear()
    }

    fun reset() = state.clear()

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val y = shelf.process(x, state)
        return x + (y - x) * amount
    }
}
