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
 * Streams are resynthesised by weighted overlap-add and handed to the spatial renderer
 * as individual objects, each with a measured azimuth — a guitar mixed at 40° left ends
 * up on the speaker at 40° left rather than smeared across the front.
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

        /** ~90 ms of history: long enough to average out transients (DAFx-10 uses 15–30). */
        private const val MEDIAN_T = 17

        /** ~500 Hz wide, per the same paper. */
        private const val MEDIAN_F = 11

        private const val EPS = 1e-12f
    }

    private val half = FFT_SIZE / 2
    private val fft = Fft(FFT_SIZE)
    private val window = Windows.sqrtHann(FFT_SIZE)
    private val wolaScale = 1f / Windows.wolaNormalisation(window, HOP)

    // ---- input / output plumbing (classic STFT FIFO, no allocation while running) ----
    private val inFifoL = FloatArray(FFT_SIZE)
    private val inFifoR = FloatArray(FFT_SIZE)
    private val latency = FFT_SIZE - HOP
    private var rover = latency
    private val outFifo = Array(STREAMS) { FloatArray(HOP) }
    private val outAccum = Array(STREAMS) { FloatArray(FFT_SIZE + HOP) }

    // ---- transform scratch ----
    private val fr = FloatArray(FFT_SIZE)
    private val fi = FloatArray(FFT_SIZE)
    private val lRe = FloatArray(half + 1)
    private val lIm = FloatArray(half + 1)
    private val rRe = FloatArray(half + 1)
    private val rIm = FloatArray(half + 1)
    private val aRe = FloatArray(half + 1)
    private val aIm = FloatArray(half + 1)
    private val bRe = FloatArray(half + 1)
    private val bIm = FloatArray(half + 1)
    private val frameOut = FloatArray(FFT_SIZE)
    private val frameOut2 = FloatArray(FFT_SIZE)

    // ---- analysis state ----
    private val magMid = FloatArray(half + 1)
    private val magL = FloatArray(half + 1)
    private val magR = FloatArray(half + 1)
    private val history = Array(MEDIAN_T) { FloatArray(half + 1) }
    private var historyPos = 0
    private val harmonic = FloatArray(half + 1)
    private val percussive = FloatArray(half + 1)
    private val medianScratch = FloatArray(maxOf(MEDIAN_T, MEDIAN_F))

    // Smoothed per-bin cues. Smoothing the *features* rather than the masks keeps the
    // masks consistent with each other (they must still sum to one) while removing the
    // frame-to-frame flicker that would otherwise be heard as musical noise.
    private val cohSm = FloatArray(half + 1)
    private val balSm = FloatArray(half + 1)
    private val panSm = FloatArray(half + 1)
    private val harmSm = FloatArray(half + 1)
    private var featureCoef = 0.35f

    // ---- band weights, precomputed in prepare() ----
    private val wBass = FloatArray(half + 1)
    private val wVocal = FloatArray(half + 1)
    private val wAir = FloatArray(half + 1)

    // ---- results the renderer reads ----
    /** Measured azimuth of each stream, degrees, positive to the right. */
    val azimuth = FloatArray(STREAMS)

    /** Short-term RMS of each stream, for metering. */
    val level = FloatArray(STREAMS)

    private val panAccum = FloatArray(6)
    private val panWeight = FloatArray(6)

    private var sampleRate = 48000

    fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        val binHz = sampleRate.toFloat() / FFT_SIZE
        for (k in 0..half) {
            val f = k * binHz
            wBass[k] = bandWeight(f, 0f, 0f, 110f, 180f)
            wVocal[k] = bandWeight(f, 140f, 260f, 5200f, 8000f)
            wAir[k] = bandWeight(f, 6500f, 10000f, 30000f, 40000f)
        }
        // ~40 ms feature smoothing at the hop rate.
        val hopsPerSecond = sampleRate.toFloat() / HOP
        featureCoef = (1f - kotlin.math.exp(-1f / (0.04f * hopsPerSecond))).coerceIn(0.05f, 1f)
        reset()
    }

    fun reset() {
        java.util.Arrays.fill(inFifoL, 0f)
        java.util.Arrays.fill(inFifoR, 0f)
        for (s in 0 until STREAMS) {
            java.util.Arrays.fill(outFifo[s], 0f)
            java.util.Arrays.fill(outAccum[s], 0f)
            level[s] = 0f
        }
        for (h in history) java.util.Arrays.fill(h, 0f)
        java.util.Arrays.fill(cohSm, 0f)
        java.util.Arrays.fill(balSm, 0f)
        java.util.Arrays.fill(panSm, 0f)
        java.util.Arrays.fill(harmSm, 0.5f)
        historyPos = 0
        rover = latency
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

    /** The algorithmic delay the rest of the chain has to compensate. */
    fun latencySamples(): Int = FFT_SIZE

    /**
     * Push one input frame and read the current sample of every stream into [out]
     * (length [STREAMS]). Output is delayed by [latencySamples] relative to the input.
     */
    fun processSample(l: Float, r: Float, out: FloatArray) {
        inFifoL[rover] = l
        inFifoR[rover] = r
        val read = rover - latency
        for (s in 0 until STREAMS) out[s] = outFifo[s][read]
        rover++
        if (rover >= FFT_SIZE) {
            rover = latency
            analyseFrame()
            for (s in 0 until STREAMS) {
                System.arraycopy(outAccum[s], 0, outFifo[s], 0, HOP)
                System.arraycopy(outAccum[s], HOP, outAccum[s], 0, FFT_SIZE)
                java.util.Arrays.fill(outAccum[s], FFT_SIZE, FFT_SIZE + HOP, 0f)
            }
            System.arraycopy(inFifoL, HOP, inFifoL, 0, latency)
            System.arraycopy(inFifoR, HOP, inFifoR, 0, latency)
        }
    }

    // --------------------------------------------------------------------- analysis

    private fun analyseFrame() {
        // Both channels in one complex transform.
        for (i in 0 until FFT_SIZE) {
            val w = window[i]
            fr[i] = inFifoL[i] * w
            fi[i] = inFifoR[i] * w
        }
        fft.forward(fr, fi)
        Fft.unpackTwoReal(fr, fi, FFT_SIZE, lRe, lIm, rRe, rIm)

        val hist = history[historyPos]
        for (k in 0..half) {
            val lr = lRe[k]; val li = lIm[k]
            val rr = rRe[k]; val ri = rIm[k]
            val ml = sqrt(lr * lr + li * li)
            val mr = sqrt(rr * rr + ri * ri)
            magL[k] = ml
            magR[k] = mr
            val midRe = 0.5f * (lr + rr)
            val midIm = 0.5f * (li + ri)
            val mm = sqrt(midRe * midRe + midIm * midIm)
            magMid[k] = mm
            hist[k] = mm
        }
        historyPos = (historyPos + 1) % MEDIAN_T

        medianOverTime()
        medianOverFrequency()

        for (s in 0 until 6) { panAccum[s] = 0f; panWeight[s] = 0f }
        for (s in 0 until STREAMS) level[s] = 0f

        val c = featureCoef

        for (k in 0..half) {
            val ml = magL[k]
            val mr = magR[k]
            val lr = lRe[k]; val li = lIm[k]
            val rr = rRe[k]; val ri = rIm[k]

            // --- cues -------------------------------------------------------
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

            val coh = cohSm[k]
            val bal = balSm[k]
            val pn = panSm[k]
            val mh = harmSm[k].coerceIn(0f, 1f)
            val mp = 1f - mh

            // --- partition of unity ------------------------------------------
            val bassW = wBass[k]
            val rest = 1f - bassW

            // Incoherent but level-balanced: room, reverb, crowd — ambience.
            var ambience = bal * (1f - abs(coh))
            // Anti-correlated and balanced: deliberately widened pads and synths.
            var wide = bal * (-coh).coerceAtLeast(0f)
            val diffuse = ambience + wide
            if (diffuse > 1f) {
                ambience /= diffuse
                wide /= diffuse
            }
            val direct = (1f - ambience - wide).coerceIn(0f, 1f)

            // Centre-ness: near the middle of the image *and* phase-coherent.
            val centreness = (1f - abs(pn)) * (1f - abs(pn)) * coh.coerceAtLeast(0f)

            val restBass = rest
            val ambM = restBass * ambience
            val wideM = restBass * wide
            val dirM = restBass * direct
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

            // Blend back toward "everything is one centred object" when the user
            // dials separation down, so the control is continuous and artefact-free.
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

        updateAzimuths()
        synthesise()
    }

    /** Scratch holding the fourteen stream spectra for the current frame. */
    private val streamRe = Array(STREAMS) { FloatArray(half + 1) }
    private val streamIm = Array(STREAMS) { FloatArray(half + 1) }

    private fun store(stream: Int, k: Int, mask: Float, re: Float, im: Float) {
        val m = if (mask < 0f) 0f else mask
        streamRe[stream][k] = m * re
        streamIm[stream][k] = m * im
    }


    private fun accumulatePan(slot: Int, weight: Float, magnitude: Float) {
        if (weight <= 0f) return
        panAccum[slot] += weight * magnitude
        panWeight[slot] += weight
    }

    private fun updateAzimuths() {
        // Percussion and instruments follow the mix; pads and ambience stay wide so
        // the rear and height layers always have something to do.
        azimuth[PERC_L] = smoothAz(azimuth[PERC_L], -meanPan(0, 25f, 70f))
        azimuth[PERC_R] = smoothAz(azimuth[PERC_R], meanPan(1, 25f, 70f))
        azimuth[INST_L] = smoothAz(azimuth[INST_L], -meanPan(2, 20f, 75f))
        azimuth[INST_R] = smoothAz(azimuth[INST_R], meanPan(3, 20f, 75f))
        azimuth[WIDE_L] = smoothAz(azimuth[WIDE_L], -meanPan(4, 85f, 120f))
        azimuth[WIDE_R] = smoothAz(azimuth[WIDE_R], meanPan(5, 85f, 120f))
    }

    private fun meanPan(slot: Int, minDeg: Float, maxDeg: Float): Float {
        val w = panWeight[slot]
        val mean = if (w > 1e-9f) (panAccum[slot] / w).coerceIn(0f, 1f) else 0.5f
        return minDeg + (maxDeg - minDeg) * mean
    }

    private fun smoothAz(current: Float, target: Float): Float = current + 0.08f * (target - current)

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
            Fft.packTwoReal(
                streamRe[a], streamIm[a], streamRe[b], streamIm[b],
                FFT_SIZE, fr, fi
            )
            fft.inverse(fr, fi)
            var sumA = 0f
            var sumB = 0f
            for (i in 0 until FFT_SIZE) {
                val w = window[i] * wolaScale
                val va = fr[i] * w
                val vb = fi[i] * w
                frameOut[i] = va
                frameOut2[i] = vb
                sumA += va * va
                sumB += vb * vb
            }
            val accA = outAccum[a]
            val accB = outAccum[b]
            for (i in 0 until FFT_SIZE) {
                accA[i] += frameOut[i]
                accB[i] += frameOut2[i]
            }
            level[a] = sqrt(sumA / FFT_SIZE)
            level[b] = sqrt(sumB / FFT_SIZE)
            s += 2
        }
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
