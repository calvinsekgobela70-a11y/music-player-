package com.ceecept.music.audio.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT, allocation-free after construction.
 *
 * Only power-of-two sizes. Twiddle factors and the bit-reversal permutation are
 * precomputed, so [forward] and [inverse] never touch the allocator — they are safe to
 * call from the audio thread.
 */
class Fft(val n: Int) {

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
    }

    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)
    private val reverse = IntArray(n)

    init {
        for (i in 0 until n / 2) {
            val angle = 2.0 * PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        for (i in 0 until n) {
            reverse[i] = Integer.reverse(i) ushr (32 - levels)
        }
    }

    /** Forward transform of [re] + j[im], both of length [n]. */
    fun forward(re: FloatArray, im: FloatArray) {
        transform(re, im, false)
    }

    /** Inverse transform, scaled by 1/n so `inverse(forward(x)) == x`. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, true)
        val scale = 1f / n
        for (i in 0 until n) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, conjugate: Boolean) {
        for (i in 0 until n) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val c = cosTable[k]
                    val s = if (conjugate) sinTable[k] else -sinTable[k]
                    val l = j + half
                    val tre = re[l] * c - im[l] * s
                    val tim = re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }

    companion object {
        /**
         * Two real signals in one complex transform: pack `a` into the real part and
         * `b` into the imaginary part, transform, then split by conjugate symmetry.
         * Halves the cost of analysing a stereo pair.
         *
         * On entry [re]/[im] hold a/b; on exit [aRe]/[aIm] hold the spectrum of a and
         * [bRe]/[bIm] the spectrum of b, for bins 0..n/2.
         */
        fun unpackTwoReal(
            re: FloatArray, im: FloatArray, n: Int,
            aRe: FloatArray, aIm: FloatArray, bRe: FloatArray, bIm: FloatArray
        ) {
            val half = n / 2
            for (k in 0..half) {
                val kc = if (k == 0) 0 else n - k
                val r1 = re[k]; val i1 = im[k]
                val r2 = re[kc]; val i2 = im[kc]
                aRe[k] = 0.5f * (r1 + r2)
                aIm[k] = 0.5f * (i1 - i2)
                bRe[k] = 0.5f * (i1 + i2)
                bIm[k] = -0.5f * (r1 - r2)
            }
        }

        /**
         * The inverse of [unpackTwoReal]: build a full conjugate-symmetric complex
         * spectrum from two half spectra so one inverse transform yields both real
         * signals (a in the real part, b in the imaginary part).
         */
        fun packTwoReal(
            aRe: FloatArray, aIm: FloatArray, bRe: FloatArray, bIm: FloatArray,
            n: Int, re: FloatArray, im: FloatArray
        ) {
            val half = n / 2
            for (k in 0..half) {
                re[k] = aRe[k] - bIm[k]
                im[k] = aIm[k] + bRe[k]
            }
            for (k in 1 until half) {
                val kc = n - k
                re[kc] = aRe[k] + bIm[k]
                im[kc] = -aIm[k] + bRe[k]
            }
        }
    }
}

/** Window functions used by the STFT stages. */
object Windows {
    /** Periodic Hann, the analysis/synthesis basis for weighted overlap-add. */
    fun hann(n: Int): FloatArray = FloatArray(n) { 0.5f - 0.5f * cos(2.0 * PI * it / n).toFloat() }

    /**
     * Root-Hann. With 75 % overlap the analysis and synthesis windows multiply back to
     * a Hann, which satisfies the overlap-add constant so reconstruction is exact.
     */
    fun sqrtHann(n: Int): FloatArray {
        val h = hann(n)
        return FloatArray(n) { kotlin.math.sqrt(h[it]) }
    }

    /** Sum of w² across all hops — the normalisation a WOLA loop has to divide by. */
    fun wolaNormalisation(window: FloatArray, hop: Int): Float {
        var sum = 0f
        var i = 0
        while (i < window.size) {
            sum += window[i] * window[i]
            i += hop
        }
        return if (sum > 1e-9f) sum else 1f
    }
}
