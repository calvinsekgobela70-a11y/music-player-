package com.ceecept.music.audio.spatial

import com.ceecept.music.audio.Biquad
import com.ceecept.music.audio.BiquadState
import com.ceecept.music.audio.process
import com.ceecept.music.audio.setAllPass

/** One entry of the §12.1 band table. */
data class SpatialBand(
    val name: String,
    val crossoverHz: Float,
    val spatialWidth: Float,
    val diffuse: Boolean
)

/** §12.1 per-band spatial parameters, transcribed from the guidelines. */
object SpatialBands {
    val TABLE = listOf(
        SpatialBand("sub_bass", 100f, 0.0f, true),
        SpatialBand("bass", 250f, 0.2f, true),
        SpatialBand("low_mid", 1000f, 0.6f, false),
        SpatialBand("mid", 4000f, 1.0f, false),
        SpatialBand("high_mid", 12000f, 1.1f, false),
        SpatialBand("treble", 20000f, 1.2f, false)
    )

    val COUNT = TABLE.size

    /** Crossover frequencies between the bands (the last band has no upper split). */
    val CROSSOVERS = FloatArray(COUNT - 1) { TABLE[it].crossoverHz }
}

/**
 * Linkwitz-Riley 4th-order crossover tree (§9.2), 6 bands.
 *
 * Each split is an LR4 pair (two cascaded Butterworth sections), whose low and high
 * outputs sum to a second-order all-pass. Every band below a split is therefore passed
 * through a matching all-pass so that the whole bank still sums to unity magnitude —
 * without that correction the recombined signal has several dB of ripple around each
 * crossover, which would fail the ±2 dB flatness criterion in §14.1.
 */
class LinkwitzRileySplitter(private val bandCount: Int) {

    private val splits = bandCount - 1

    // LR4 = two identical Butterworth sections per branch.
    private val lp1 = Array(splits) { Biquad() }
    private val lp2 = Array(splits) { Biquad() }
    private val hp1 = Array(splits) { Biquad() }
    private val hp2 = Array(splits) { Biquad() }
    private val lp1s = Array(splits) { BiquadState() }
    private val lp2s = Array(splits) { BiquadState() }
    private val hp1s = Array(splits) { BiquadState() }
    private val hp2s = Array(splits) { BiquadState() }

    // Phase-compensating all-passes: band b is corrected by every split above it.
    private val ap = Array(splits) { Array(splits) { Biquad() } }
    private val apState = Array(splits) { Array(splits) { BiquadState() } }

    fun configure(crossovers: FloatArray, sampleRate: Int) {
        val nyquist = sampleRate / 2.2f
        for (i in 0 until splits) {
            val f = crossovers[i].coerceIn(20f, nyquist)
            lp1[i].setLowPass(f, 0.7071f, sampleRate)
            lp2[i].setLowPass(f, 0.7071f, sampleRate)
            hp1[i].setHighPass(f, 0.7071f, sampleRate)
            hp2[i].setHighPass(f, 0.7071f, sampleRate)
            for (b in 0 until splits) {
                ap[b][i].setAllPass(f, 0.7071f, sampleRate)
            }
        }
    }

    /** Split one sample into [out] (length >= bandCount). */
    fun process(x: Float, out: FloatArray) {
        var rest = x
        for (i in 0 until splits) {
            val low = lp2[i].process(lp1[i].process(rest, lp1s[i]), lp2s[i])
            val high = hp2[i].process(hp1[i].process(rest, hp1s[i]), hp2s[i])
            // Align this band with every crossover still to come.
            var band = low
            for (j in i + 1 until splits) {
                band = ap[i][j].process(band, apState[i][j])
            }
            out[i] = band
            rest = high
        }
        out[splits] = rest
    }

    fun clear() {
        for (i in 0 until splits) {
            lp1s[i].clear(); lp2s[i].clear(); hp1s[i].clear(); hp2s[i].clear()
            for (j in 0 until splits) apState[i][j].clear()
        }
    }
}
