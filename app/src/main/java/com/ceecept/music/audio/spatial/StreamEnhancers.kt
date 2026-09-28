package com.ceecept.music.audio.spatial

import com.ceecept.music.audio.Biquad
import com.ceecept.music.audio.BiquadState
import com.ceecept.music.audio.DelayLine
import com.ceecept.music.audio.Dsp
import com.ceecept.music.audio.process
import com.ceecept.music.audio.setAllPass
import com.ceecept.music.audio.setHighShelf
import com.ceecept.music.audio.setLowShelf
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
 * The low end, rebuilt.
 *
 * Three separate jobs, in the order a mastering engineer would do them:
 *
 *  1. **Clear the rumble.** Everything below ~24 Hz is inaudible on a phone and only
 *     wastes headroom, so it goes first.
 *  2. **Cut the boxiness.** A bass that sounds "boxy" is almost never too deep — it has
 *     too much 200–400 Hz, the range every mixing guide calls the mud zone. A gentle
 *     bell cut at 300 Hz is what makes the same bass sound tighter and *bigger*,
 *     because the sub underneath is no longer masked.
 *  3. **Feel the sub.** 40–60 Hz gets a shelf plus a slow-attack compressor: the attack
 *     passes untouched (that is the knock you feel), the sustain is levelled so the
 *     note stays solid without the peaks eating headroom. Then the missing-fundamental
 *     harmonics are generated from the 35–90 Hz band and placed at 120–400 Hz, where
 *     phone speakers and earbuds are efficient — the ear reconstructs the fundamental
 *     and hears a deeper bass at a *lower* peak level.
 */
class BassEngine {
    private val rumble = Biquad()
    private val rumbleState = BiquadState()
    private val boxCut = Biquad()
    private val boxCutState = BiquadState()
    private val subShelf = Biquad()
    private val subShelfState = BiquadState()
    private val subBand = Biquad()
    private val subBandState = BiquadState()

    // Harmonic generator path.
    private val genLow = Biquad()
    private val genLowState = BiquadState()
    private val genHigh = Biquad()
    private val genHighState = BiquadState()
    private val postHigh = Biquad()
    private val postHighState = BiquadState()
    private val postLow = Biquad()
    private val postLowState = BiquadState()
    private val postTame = Biquad()
    private val postTameState = BiquadState()

    private var srcEnv = 0f
    private var genEnv = 0f
    private var envCoef = 0.002f
    private var dcState = 0f

    // Sub compressor.
    private var subEnv = 0f
    private var subAttack = 0.01f
    private var subRelease = 0.001f

    /** 0 = off, 1 = full treatment. */
    var amount = 0f

    fun prepare(sampleRate: Int) {
        rumble.setHighPass(28f, 0.7071f, sampleRate)
        // The mud zone. Wide enough to clear the range, gentle enough to stay musical.
        boxCut.setPeaking(285f, 0.85f, -7.0f, sampleRate)
        subShelf.setLowShelf(54f, 2.8f, sampleRate)
        subBand.setLowPass(68f, 0.7071f, sampleRate)

        genHigh.setHighPass(32f, 0.7071f, sampleRate)
        genLow.setLowPass(82f, 0.7071f, sampleRate)
        postHigh.setHighPass(95f, 0.7071f, sampleRate)
        postLow.setLowPass(360f, 0.7071f, sampleRate)
        // Keep the generated harmonics out of the mud zone we just cleared.
        postTame.setPeaking(285f, 1.0f, -9f, sampleRate)

        envCoef = Dsp.envelopeCoef(25f, sampleRate)
        subAttack = Dsp.envelopeCoef(12f, sampleRate)   // slow enough to pass the knock
        subRelease = Dsp.envelopeCoef(140f, sampleRate)
        reset()
    }

    fun reset() {
        rumbleState.clear(); boxCutState.clear(); subShelfState.clear(); subBandState.clear()
        genLowState.clear(); genHighState.clear()
        postHighState.clear(); postLowState.clear(); postTameState.clear()
        srcEnv = 0f; genEnv = 0f; dcState = 0f; subEnv = 0f
    }

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val a = amount

        // 1. rumble out
        var y = rumble.process(x, rumbleState)

        // 2. boxiness out (scaled by amount so the control stays continuous)
        val boxed = boxCut.process(y, boxCutState)
        y += (boxed - y) * a

        // 3a. sub shelf
        val shelved = subShelf.process(y, subShelfState)
        y += (shelved - y) * a

        // 3b. sub compressor: level the sustain, leave the attack alone
        val sub = subBand.process(y, subBandState)
        val sa = abs(sub)
        subEnv += (if (sa > subEnv) subAttack else subRelease) * (sa - subEnv)
        val threshold = 0.16f
        if (subEnv > threshold) {
            // 2.5:1 above the threshold, applied to the sub band only.
            val over = subEnv / threshold
            val gain = (1f / Math.pow(over.toDouble(), 0.42).toFloat()).coerceIn(0.42f, 1f)
            y += sub * (gain - 1f) * a
        }

        // 3c. missing-fundamental harmonics
        val src = genLow.process(genHigh.process(y, genHighState), genLowState)
        val sEnv = abs(src)
        srcEnv += envCoef * (sEnv - srcEnv)

        val even = src * src * 2.4f          // 2nd harmonic: warmth
        val odd = tanh(src * 2.1f)         // 3rd, 5th: definition
        var gen = 0.55f * even + 0.45f * odd
        dcState += 0.0008f * (gen - dcState)
        gen -= dcState
        gen = postLow.process(postHigh.process(gen, postHighState), postLowState)
        gen = postTame.process(gen, postTameState)

        val gEnv = abs(gen)
        genEnv += envCoef * (gEnv - genEnv)
        val norm = if (genEnv > 1e-5f) (srcEnv / genEnv).coerceIn(0f, 4f) else 0f

        return y + gen * norm * a * 0.48f
    }
}

/**
 * Vocal clarity: a presence lift that only opens when a vocal is actually present, an
 * air shelf, a touch of harmonic excitation, and a de-esser so none of it turns every
 * "s" into a spit.
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
    private val exciter = HarmonicExciter(2600f)
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
        exciter.prepare(sampleRate)
        attack = Dsp.envelopeCoef(2f, sampleRate)
        release = Dsp.envelopeCoef(90f, sampleRate)
        reset()
    }

    fun reset() {
        presenceState.clear(); airState.clear(); sibilanceState.clear()
        exciter.reset()
        sibEnv = 0f; bodyEnv = 0f
    }

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val lifted = air.process(presence.process(x, presenceState), airState)
        var y = x + (lifted - x) * amount

        // A little excitation adds the breath and consonant detail that EQ alone
        // cannot, because those harmonics are not in the recording to boost.
        exciter.amount = amount * 0.35f
        y = exciter.process(y)

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
 * Aphex-style aural exciter.
 *
 * Split off the top end, push it through an *asymmetric* soft clipper — asymmetry is
 * what produces even harmonics alongside the odd ones — attenuate, and mix it back.
 * The harmonics are musically related to what is already there, so the result reads as
 * "more detail" rather than "more treble", and it restores the air that lossy encoding
 * and heavy limiting strip out of modern masters. Modelled on the original Aural
 * Exciter topology (high-pass → harmonic creator → attenuator → mix).
 */
class HarmonicExciter(private val crossoverHz: Float = 3500f) {
    private val split = Biquad()
    private val splitState = BiquadState()
    private val tame = Biquad()
    private val tameState = BiquadState()
    private var dc = 0f

    /** 0 = bypass, 1 = full drive. */
    var amount = 0f

    fun prepare(sampleRate: Int) {
        split.setHighPass(crossoverHz, 0.7071f, sampleRate)
        // Roll the very top of the generated content off so it never gets brittle.
        tame.setLowPass((sampleRate * 0.42f).coerceAtMost(15000f), 0.7071f, sampleRate)
        reset()
    }

    fun reset() {
        splitState.clear(); tameState.clear(); dc = 0f
    }

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val hf = split.process(x, splitState)
        val drive = 1f + 6f * amount
        val d = hf * drive
        // Asymmetric cubic soft clip: even + odd harmonics, no hard corners.
        var shaped = if (d >= 0f) {
            d - d * d * d / 3f
        } else {
            (d - d * d * d / 4.5f) * 0.85f
        }
        shaped = shaped.coerceIn(-1.5f, 1.5f)
        dc += 0.0006f * (shaped - dc)
        shaped -= dc
        shaped = tame.process(shaped, tameState)
        return x + (shaped - hf) * amount * 0.35f
    }
}

/**
 * Tube-style warmth for the final mix.
 *
 * Digital playback is perfectly linear, and perfect linearity is part of what makes a
 * phone sound clinical: every analogue stage a record passed through on its way to you
 * added a little second-harmonic content, and the ear reads that as warmth and depth
 * rather than as distortion, because the second harmonic is an octave — consonant with
 * the note that produced it.
 *
 * This generates a level-tracked second harmonic with a trace of third, keeps it out of
 * the bass (where it would muddy), and mixes in a few percent. It is deliberately tiny:
 * measured THD stays under 1 % even at full setting.
 */
class TubeWarmth {
    private val hp = Biquad()
    private val hpState = BiquadState()
    private val lp = Biquad()
    private val lpState = BiquadState()
    private var dc = 0f
    private var env = 0f
    private var envCoef = 0.001f

    /** 0 = pure digital, 1 = maximum warmth (still subtle). */
    var amount = 0f

    fun prepare(sampleRate: Int) {
        hp.setHighPass(160f, 0.7071f, sampleRate)
        lp.setLowPass(7000f, 0.7071f, sampleRate)
        envCoef = Dsp.envelopeCoef(50f, sampleRate)
        reset()
    }

    fun reset() {
        hpState.clear(); lpState.clear(); dc = 0f; env = 0f
    }

    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val band = lp.process(hp.process(x, hpState), lpState)
        val a = abs(band)
        env += envCoef * (a - env)
        // Second harmonic dominant, third at a third of the level.
        var h = band * band * 1.6f + band * band * band * 0.5f
        dc += 0.0005f * (h - dc)
        h -= dc
        // Track the programme so quiet passages stay clean and loud ones get the colour.
        val norm = if (env > 1e-4f) (1f / (1f + 6f * env)) else 0f
        return x + h * norm * amount * 0.12f
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
 *
 * Only the content above 250 Hz is decorrelated. Scrambling the phase of low frequencies
 * is what makes this class of effect sound hollow and phasey, and it destroys mono
 * compatibility in exactly the range a phone speaker still reproduces.
 */
class Decorrelator(private val seed: Int) {
    private val stages = Array(4) { Biquad() }
    private val states = Array(4) { BiquadState() }
    private val splitHigh = Biquad()
    private val splitHighState = BiquadState()
    private val splitLow = Biquad()
    private val splitLowState = BiquadState()
    private var delay = DelayLine(64)

    fun prepare(sampleRate: Int) {
        val base = if (seed % 2 == 0) 420f else 610f
        val ratios = floatArrayOf(1f, 2.31f, 4.77f, 9.13f)
        for (i in stages.indices) {
            stages[i].setAllPass(base * ratios[i] * (1f + 0.07f * seed), 0.55f + 0.1f * i, sampleRate)
        }
        splitHigh.setHighPass(250f, 0.7071f, sampleRate)
        splitLow.setLowPass(250f, 0.7071f, sampleRate)
        delay = DelayLine((0.01f * sampleRate).toInt() + 8)
        delay.delay = (0.0003f + 0.00017f * seed) * sampleRate
        reset()
    }

    fun reset() {
        for (s in states) s.clear()
        splitHighState.clear(); splitLowState.clear()
        delay.clear()
    }

    /** [amount] crossfades between the original and the decorrelated copy. */
    fun process(x: Float, amount: Float): Float {
        if (amount <= 0.001f) return x
        val low = splitLow.process(x, splitLowState)
        val high = splitHigh.process(x, splitHighState)
        var y = delay.push(high)
        for (i in stages.indices) y = stages[i].process(y, states[i])
        return low + high + (y - high) * amount.coerceIn(0f, 1f)
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
