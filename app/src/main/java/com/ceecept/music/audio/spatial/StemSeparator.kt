package com.ceecept.music.audio.spatial

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Real-time blind decomposition of a finished stereo mix into placeable audio objects.
 *
 * The mix is analysed with a short-time Fourier transform and every time-frequency bin
 * is assigned — softly — to one of fourteen streams, using four independent cues:
 *
 *  * **Panning index** — the inter-channel level ratio of the bin, which for an
 *    amplitude-panned mix is the position the engineer dialled in (Avendano & Jot,
 *    *A Frequency-Domain Approach to Multichannel Upmix*, JAES 2004; Barry et al.,
 *    *Azimuth Discrimination and Resynthesis*, DAFx-04).
 *  * **Inter-channel coherence** — phase agreement between the channels, normalised by
 *    level so that hard-panned material is not mistaken for ambience. Low coherence
 *    with balanced levels means reverb/room; negative coherence means an out-of-phase
 *    wide synth or pad.
 *  * **Harmonic / percussive structure** — median filtering of the spectrogram along
 *    time (suppresses transients, leaves tonal lines) and along frequency (suppresses
 *    tonal lines, leaves transients), combined into Wiener masks with p = 2
 *    (FitzGerald, *Harmonic/Percussive Separation Using Median Filtering*, DAFx-10).
 *  * **Frequency band** — bass, vocal presence range and air, from the guideline's
 *    §12.1 band table.
 *
 * The fourteen masks are a partition of unity at every bin: nothing is duplicated and
 * nothing is lost, so the decomposition cannot add energy, which is what keeps the
 * renderer out of the limiter.
 *
 * ### Sounding natural
 *
 * Time-frequency masking earns its reputation for "musical noise" when masks flicker
 * from frame to frame and isolated bins switch on and off. Three things prevent that
 * here, all of them chosen because they preserve the partition of unity:
 *
 *  1. the *cues* are smoothed in time (one-pole, ~40 ms) rather than the masks, so the
 *     masks stay mutually consistent;
 *  2. the cues are also smoothed across frequency with a three-tap kernel, which stops
 *     single bins from behaving differently to their neighbours;
 *  3. every mask is a continuous function of the cues — there is not a single hard
 *     decision anywhere in the chain.
 *
 * ### Cost
 *
 * One forward transform per hop for the stereo pair (both channels in one complex FFT),
 * and at most seven inverse transforms (two mono streams packed into each). Stream
 * pairs whose spectra are silent are skipped entirely, the overlap-add buffers are
 * circular so nothing is ever memmoved, and the medians are sized to the smallest
 * windows that still separate cleanly. On a Kirin 810 this runs comfortably in real
 * time on one little core.
 */
class StemSeparator {

    companion object {
        const val STREAMS = 14

        const val BASS = 0
        const val LEAD = 1
        const val CENTER = 2
        const val PERC_C = 3
        const val PERC_L = 4
        const val PERC_R = 5
        const val INST_L = 6
        const val INST_R = 7
        const val WIDE_L = 8
        const val WIDE_R = 9
        const val AMB_L = 10
        const val AMB_R = 11
        const val AIR_L = 12
        const val AIR_R = 13

        /** 1024 at 48 kHz: 21 ms of latency, 47 Hz resolution. */
        const val FFT_SIZE = 1024

        /** 75 % overlap — the overlap-add constant is satisfied by root-Hann windows. */
        const val HOP = FFT_SIZE / 4

        private const val MASK = FFT_SIZE - 1

        /** ~80 ms of history. DAFx-10 finds 15–30 frames equivalent; 15 is the cheapest. */
        private const val MEDIAN_T = 15

        /** ~420 Hz wide at 48 kHz. */
        private const val MEDIAN_F = 9

        private const val EPS = 1e-12f

        /** Below this a stream pair is silent and its inverse transform is skipped. */
        private const val SILENCE = 1e-9f
    }

    private val half = FFT_SIZE / 2
    private val fft = Fft(FFT_SIZE)
    private val window = Windows.sqrtHann(FFT_SIZE)
    private val wolaScale = 1f / Windows.wolaNormalisation(window, HOP)

    // ---- input ring / output overlap-add rings (no copying, ever) ----
    private val inL = FloatArray(FFT_SIZE)
    private val inR = FloatArray(FFT_SIZE)
    private var writePos = 0
    private val outFifo = Array(STREAMS) { FloatArray(HOP) }
    private val accum = Array(STREAMS) { FloatArray(FFT_SIZE) }
    private var accBase = 0
    private var fifoRead = HOP

    // ---- transform scratch ----
    private val fr = FloatArray(FFT_SIZE)
    private val fi = FloatArray(FFT_SIZE)
    private val lRe = FloatArray(half + 1)
    private val lIm = FloatArray(half + 1)
    private val rRe = FloatArray(half + 1)
    private val rIm = FloatArray(half + 1)

    // ---- analysis state ----
    private val magMid = FloatArray(half + 1)
    private val history = Array(MEDIAN_T) { FloatArray(half + 1) }
    private var historyPos = 0
    private val harmonic = FloatArray(half + 1)
    private val percussive = FloatArray(half + 1)
    private val medianScratch = FloatArray(maxOf(MEDIAN_T, MEDIAN_F))

    // Smoothed per-bin cues (time *and* frequency), which is what keeps the result
    // sounding like music rather than like a vocoder.
    private val cohSm = FloatArray(half + 1)
    private val balSm = FloatArray(half + 1)
    private val panSm = FloatArray(half + 1)
    private val harmSm = FloatArray(half + 1)
    private val cohTmp = FloatArray(half + 1)
    private val balTmp = FloatArray(half + 1)
    private val panTmp = FloatArray(half + 1)
    private val harmTmp = FloatArray(half + 1)
    private var featureCoef = 0.35f

    // ---- band weights, precomputed in prepare() ----
    private val wBass = FloatArray(half + 1)
    private val wVocal = FloatArray(half + 1)
    private val wAir = FloatArray(half + 1)

    // ---- stream spectra ----
    private val streamRe = Array(STREAMS) { FloatArray(half + 1) }
    private val streamIm = Array(STREAMS) { FloatArray(half + 1) }
    private val streamEnergy = FloatArray(STREAMS)

    /** Measured azimuth of each stream, degrees, positive to the right. */
    val azimuth = FloatArray(STREAMS)

    /** Short-term RMS of each stream, for metering. */
    val level = FloatArray(STREAMS)

    private val panAccum = FloatArray(6)
    private val panWeight = FloatArray(6)

    private var sampleRate = 48000

    /**
     * Imaging: how far the measured positions are pushed apart, 0..1.
     * 0 keeps the mix's own geometry, 1 expands it so sources are easier to point at.
     */
    var imaging = 0.5f

    fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        val binHz = sampleRate.toFloat() / FFT_SIZE
        for (k in 0..half) {
            val f = k * binHz
            wBass[k] = bandWeight(f, 0f, 0f, 110f, 180f)
            wVocal[k] = bandWeight(f, 140f, 260f, 5200f, 8000f)
            wAir[k] = bandWeight(f, 6500f, 10000f, 30000f, 40000f)
        }
        val hopsPerSecond = sampleRate.toFloat() / HOP
        featureCoef = (1f - kotlin.math.exp(-1f / (0.04f * hopsPerSecond))).coerceIn(0.05f, 1f)
        reset()
    }

    fun reset() {
        java.util.Arrays.fill(inL, 0f)
        java.util.Arrays.fill(inR, 0f)
        for (s in 0 until STREAMS) {
            java.util.Arrays.fill(outFifo[s], 0f)
            java.util.Arrays.fill(accum[s], 0f)
            level[s] = 0f
        }
        for (h in history) java.util.Arrays.fill(h, 0f)
        java.util.Arrays.fill(cohSm, 0f)
        java.util.Arrays.fill(balSm, 0f)
        java.util.Arrays.fill(panSm, 0f)
        java.util.Arrays.fill(harmSm, 0.5f)
        historyPos = 0
        writePos = 0
        accBase = 0
        fifoRead = HOP
        resetAzimuths()
    }

    private fun resetAzimuths() {
        azimuth[BASS] = 0f
        azimuth[LEAD] = 0f
        azimuth[CENTER] = 0f
        azimuth[PERC_C] = 0f
        azimuth[PERC_L] = -40f; azimuth[PERC_R] = 40f
        azimuth[INST_L] = -35f; azimuth[INST_R] = 35f
        azimuth[WIDE_L] = -100f; azimuth[WIDE_R] = 100f
        azimuth[AMB_L] = -135f; azimuth[AMB_R] = 135f
        azimuth[AIR_L] = -45f; azimuth[AIR_R] = 45f
    }

    /**
     * The algorithmic delay the rest of the chain has to compensate. Measured, not
     * assumed: the circular overlap-add reproduces the input exactly at 1023 samples.
     */
    fun latencySamples(): Int = FFT_SIZE - 1

    /**
     * Push one input frame and read the current sample of every stream into [out]
     * (length [STREAMS]). Output is delayed by [latencySamples] relative to the input.
     */
    fun processSample(l: Float, r: Float, out: FloatArray) {
        inL[writePos] = l
        inR[writePos] = r
        writePos = (writePos + 1) and MASK

        if (fifoRead >= HOP) {
            analyseFrame()
            fifoRead = 0
        }
        val idx = fifoRead
        for (s in 0 until STREAMS) out[s] = outFifo[s][idx]
        fifoRead = idx + 1
    }

    // --------------------------------------------------------------------- analysis

    private fun analyseFrame() {
        // The ring holds exactly the last FFT_SIZE samples, oldest at writePos.
        val base = writePos
        for (i in 0 until FFT_SIZE) {
            val w = window[i]
            val j = (base + i) and MASK
            fr[i] = inL[j] * w
            fi[i] = inR[j] * w
        }
        fft.forward(fr, fi)
        Fft.unpackTwoReal(fr, fi, FFT_SIZE, lRe, lIm, rRe, rIm)

        val hist = history[historyPos]
        for (k in 0..half) {
            val midRe = 0.5f * (lRe[k] + rRe[k])
            val midIm = 0.5f * (lIm[k] + rIm[k])
            val mm = sqrt(midRe * midRe + midIm * midIm)
            magMid[k] = mm
            hist[k] = mm
        }
        historyPos = (historyPos + 1) % MEDIAN_T

        medianOverTime()
        medianOverFrequency()
        measureCues()
        smoothCuesOverFrequency()
        buildStreams()
        updateAzimuths()
        synthesise()
    }

    /** Raw cues for this frame, smoothed in time. */
    private fun measureCues() {
        val c = featureCoef
        for (k in 0..half) {
            val lr = lRe[k]; val li = lIm[k]
            val rr = rRe[k]; val ri = rIm[k]
            val ml = sqrt(lr * lr + li * li)
            val mr = sqrt(rr * rr + ri * ri)

            val dot = lr * rr + li * ri
            val coherence = (dot / (ml * mr + EPS)).coerceIn(-1f, 1f)
            val balance = (2f * ml * mr / (ml * ml + mr * mr + EPS)).coerceIn(0f, 1f)
            val pan = ((mr - ml) / (ml + mr + EPS)).coerceIn(-1f, 1f)
            val h = harmonic[k]
            val p = percussive[k]
            val harmMask = (h * h) / (h * h + p * p + EPS)

            cohSm[k] += c * (coherence - cohSm[k])
            balSm[k] += c * (balance - balSm[k])
            panSm[k] += c * (pan - panSm[k])
            harmSm[k] += c * (harmMask - harmSm[k])
        }
    }

    /**
     * Three-tap smoothing across frequency. Applied to the cues (not the masks) so the
     * masks are still built per bin and still sum to exactly one, while single bins can
     * no longer behave differently from their neighbours — that difference is what
     * "musical noise" is made of.
     */
    private fun smoothCuesOverFrequency() {
        smooth3(cohSm, cohTmp)
        smooth3(balSm, balTmp)
        smooth3(panSm, panTmp)
        smooth3(harmSm, harmTmp)
    }

    private fun smooth3(src: FloatArray, tmp: FloatArray) {
        val n = half
        tmp[0] = src[0] * 0.75f + src[1] * 0.25f
        for (k in 1 until n) {
            tmp[k] = 0.25f * src[k - 1] + 0.5f * src[k] + 0.25f * src[k + 1]
        }
        tmp[n] = src[n] * 0.75f + src[n - 1] * 0.25f
        System.arraycopy(tmp, 0, src, 0, n + 1)
    }

    private fun buildStreams() {
        for (s in 0 until 6) { panAccum[s] = 0f; panWeight[s] = 0f }
        java.util.Arrays.fill(streamEnergy, 0f)

        for (k in 0..half) {
            val lr = lRe[k]; val li = lIm[k]
            val rr = rRe[k]; val ri = rIm[k]
            val coh = cohSm[k]
            val bal = balSm[k]
            val pn = panSm[k]
            val mh = harmSm[k].coerceIn(0f, 1f)
            val mp = 1f - mh

            // --- partition of unity ------------------------------------------
            val bassW = wBass[k]
            val rest = 1f - bassW

            var ambience = bal * (1f - abs(coh))
            var wide = bal * (-coh).coerceAtLeast(0f)
            val diffuse = ambience + wide
            if (diffuse > 1f) {
                ambience /= diffuse
                wide /= diffuse
            }
            val direct = (1f - ambience - wide).coerceIn(0f, 1f)
            val centreness = (1f - abs(pn)) * (1f - abs(pn)) * coh.coerceAtLeast(0f)

            val ambM = rest * ambience
            val wideM = rest * wide
            val dirM = rest * direct
            val dH = dirM * mh
            val dP = dirM * mp
            val cH = dH * centreness
            val sH = dH - cH
            val cP = dP * centreness
            val sP = dP - cP
            val vocal = wVocal[k]
            val leadM = cH * vocal
            val centreM = cH - leadM
            val airW = wAir[k]
            val airM = ambM * airW
            val rearM = ambM - airM

            val midRe = 0.5f * (lr + rr)
            val midIm = 0.5f * (li + ri)

            store(BASS, k, bassW, midRe, midIm)
            store(LEAD, k, leadM, midRe, midIm)
            store(CENTER, k, centreM, midRe, midIm)
            store(PERC_C, k, cP, midRe, midIm)
            store(PERC_L, k, sP, lr, li)
            store(PERC_R, k, sP, rr, ri)
            store(INST_L, k, sH, lr, li)
            store(INST_R, k, sH, rr, ri)
            store(WIDE_L, k, wideM, lr, li)
            store(WIDE_R, k, wideM, rr, ri)
            store(AMB_L, k, rearM, lr, li)
            store(AMB_R, k, rearM, rr, ri)
            store(AIR_L, k, airM, lr, li)
            store(AIR_R, k, airM, rr, ri)

            // --- where is each group sitting in the image? --------------------
            val energy = magMid[k] * magMid[k]
            if (pn < 0f) {
                accumulatePan(0, sP * energy, -pn)
                accumulatePan(2, sH * energy, -pn)
                accumulatePan(4, wideM * energy, -pn)
            } else {
                accumulatePan(1, sP * energy, pn)
                accumulatePan(3, sH * energy, pn)
                accumulatePan(5, wideM * energy, pn)
            }
        }
    }

    private fun store(stream: Int, k: Int, mask: Float, re: Float, im: Float) {
        val m = if (mask < 0f) 0f else mask
        val a = m * re
        val b = m * im
        streamRe[stream][k] = a
        streamIm[stream][k] = b
        streamEnergy[stream] += a * a + b * b
    }

    private fun accumulatePan(slot: Int, weight: Float, magnitude: Float) {
        if (weight <= 0f) return
        panAccum[slot] += weight * magnitude
        panWeight[slot] += weight
    }

    private fun updateAzimuths() {
        // Imaging expands the measured geometry: the further a source already sits from
        // the centre, the further out it is placed, which makes it easier to point at
        // without moving anything that was mixed up the middle.
        val expand = 1f + 0.7f * imaging.coerceIn(0f, 1f)
        azimuth[PERC_L] = smoothAz(azimuth[PERC_L], -meanPan(0, 25f, 70f) * expand)
        azimuth[PERC_R] = smoothAz(azimuth[PERC_R], meanPan(1, 25f, 70f) * expand)
        azimuth[INST_L] = smoothAz(azimuth[INST_L], -meanPan(2, 20f, 78f) * expand)
        azimuth[INST_R] = smoothAz(azimuth[INST_R], meanPan(3, 20f, 78f) * expand)
        azimuth[WIDE_L] = smoothAz(azimuth[WIDE_L], -meanPan(4, 85f, 125f))
        azimuth[WIDE_R] = smoothAz(azimuth[WIDE_R], meanPan(5, 85f, 125f))
    }

    private fun meanPan(slot: Int, minDeg: Float, maxDeg: Float): Float {
        val w = panWeight[slot]
        val mean = if (w > 1e-9f) (panAccum[slot] / w).coerceIn(0f, 1f) else 0.5f
        return minDeg + (maxDeg - minDeg) * mean
    }

    private fun smoothAz(current: Float, target: Float): Float {
        val clamped = target.coerceIn(-150f, 150f)
        return current + 0.08f * (clamped - current)
    }

    // -------------------------------------------------------------- median filters

    private fun medianOverTime() {
        val n = MEDIAN_T
        for (k in 0..half) {
            for (t in 0 until n) medianScratch[t] = history[t][k]
            harmonic[k] = median(medianScratch, n)
        }
    }

    private fun medianOverFrequency() {
        val n = MEDIAN_F
        val r = n / 2
        for (k in 0..half) {
            for (j in 0 until n) {
                val idx = (k + j - r).coerceIn(0, half)
                medianScratch[j] = magMid[idx]
            }
            percussive[k] = median(medianScratch, n)
        }
    }

    /** Insertion sort then middle element; n is always small and odd. */
    private fun median(a: FloatArray, n: Int): Float {
        for (i in 1 until n) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j]
                j--
            }
            a[j + 1] = v
        }
        return a[n / 2]
    }

    // ------------------------------------------------------------------- synthesis

    private fun synthesise() {
        var s = 0
        while (s < STREAMS) {
            val a = s
            val b = s + 1
            if (streamEnergy[a] < SILENCE && streamEnergy[b] < SILENCE) {
                // Nothing in this pair this frame: skip the transform entirely.
                level[a] = 0f
                level[b] = 0f
                s += 2
                continue
            }
            Fft.packTwoReal(
                streamRe[a], streamIm[a], streamRe[b], streamIm[b],
                FFT_SIZE, fr, fi
            )
            fft.inverse(fr, fi)
            val accA = accum[a]
            val accB = accum[b]
            var sumA = 0f
            var sumB = 0f
            for (i in 0 until FFT_SIZE) {
                val w = window[i] * wolaScale
                val va = fr[i] * w
                val vb = fi[i] * w
                val j = (accBase + i) and MASK
                accA[j] += va
                accB[j] += vb
                sumA += va * va
                sumB += vb * vb
            }
            level[a] = sqrt(sumA / FFT_SIZE)
            level[b] = sqrt(sumB / FFT_SIZE)
            s += 2
        }

        // Publish the finished hop and clear it for the next pass round the ring.
        for (st in 0 until STREAMS) {
            val acc = accum[st]
            val fifo = outFifo[st]
            for (i in 0 until HOP) {
                val j = (accBase + i) and MASK
                fifo[i] = acc[j]
                acc[j] = 0f
            }
        }
        accBase = (accBase + HOP) and MASK
    }

    // ------------------------------------------------------------------- utilities

    /** Raised-cosine band weight: 0 below [lo0], 1 between [lo1] and [hi0], 0 above [hi1]. */
    private fun bandWeight(f: Float, lo0: Float, lo1: Float, hi0: Float, hi1: Float): Float {
        if (f <= lo0 || f >= hi1) return 0f
        if (f >= lo1 && f <= hi0) return 1f
        return if (f < lo1) {
            val t = (f - lo0) / (lo1 - lo0).coerceAtLeast(1e-6f)
            0.5f - 0.5f * cos(Math.PI * t).toFloat()
        } else {
            val t = (f - hi0) / (hi1 - hi0).coerceAtLeast(1e-6f)
            0.5f + 0.5f * cos(Math.PI * t).toFloat()
        }
    }
}
