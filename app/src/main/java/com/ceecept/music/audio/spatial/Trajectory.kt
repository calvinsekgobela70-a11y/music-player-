package com.ceecept.music.audio.spatial

import kotlin.math.log2
import kotlin.math.sqrt

/** One keyframe of an object's movement path (§8.1 / §13.1 `trajectory_keyframes`). */
data class Keyframe(
    val timeSec: Float,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val distanceM: Float
)

/**
 * §8.1 keyframe animation with natural cubic-spline interpolation, so objects
 * accelerate and decelerate smoothly instead of hinging at every keyframe.
 *
 * Azimuth is unwrapped before fitting: a path that crosses ±180° must not spin the
 * object the long way round.
 */
class ObjectTrajectory(keyframes: List<Keyframe>) {

    private val times: FloatArray
    private val az: CubicSpline
    private val el: CubicSpline
    private val dist: CubicSpline

    val startTime: Float
    val endTime: Float
    val isEmpty: Boolean

    init {
        val sorted = keyframes.sortedBy { it.timeSec }
        isEmpty = sorted.size < 2
        times = FloatArray(maxOf(sorted.size, 1)) { if (sorted.isEmpty()) 0f else sorted[it].timeSec }
        val azValues = FloatArray(times.size)
        val elValues = FloatArray(times.size)
        val distValues = FloatArray(times.size)
        var previous = 0f
        for (i in sorted.indices) {
            // Unwrap azimuth relative to the previous keyframe.
            var a = sorted[i].azimuthDeg
            if (i > 0) {
                while (a - previous > 180f) a -= 360f
                while (a - previous < -180f) a += 360f
            }
            previous = a
            azValues[i] = a
            elValues[i] = sorted[i].elevationDeg
            distValues[i] = sorted[i].distanceM
        }
        az = CubicSpline(times, azValues)
        el = CubicSpline(times, elValues)
        dist = CubicSpline(times, distValues)
        startTime = if (sorted.isEmpty()) 0f else sorted.first().timeSec
        endTime = if (sorted.isEmpty()) 0f else sorted.last().timeSec
    }

    /** §8.1 `get_position`. [out] receives azimuth, elevation, distance. */
    fun positionAt(timeSec: Float, out: FloatArray) {
        val t = timeSec.coerceIn(startTime, endTime)
        out[0] = Geometry.wrapDeg(az.valueAt(t))
        out[1] = el.valueAt(t).coerceIn(-90f, 90f)
        out[2] = dist.valueAt(t).coerceAtLeast(0.1f)
    }

    /**
     * §8.1 `get_velocity` by central difference over a 1 ms step, in metres/second.
     * [out] receives vx, vy, vz, speed.
     */
    fun velocityAt(timeSec: Float, out: FloatArray, scratchA: FloatArray, scratchB: FloatArray) {
        val dt = 0.001f
        positionAt(timeSec - dt / 2f, scratchA)
        Geometry.sphericalToCartesian(scratchA[0], scratchA[1], scratchA[2], scratchA)
        positionAt(timeSec + dt / 2f, scratchB)
        Geometry.sphericalToCartesian(scratchB[0], scratchB[1], scratchB[2], scratchB)
        val vx = (scratchB[0] - scratchA[0]) / dt
        val vy = (scratchB[1] - scratchA[1]) / dt
        val vz = (scratchB[2] - scratchA[2]) / dt
        out[0] = vx
        out[1] = vy
        out[2] = vz
        out[3] = sqrt(vx * vx + vy * vy + vz * vz)
    }
}

/** Natural cubic spline through (x, y) knots — the real-time equivalent of `CubicSpline`. */
class CubicSpline(private val xs: FloatArray, private val ys: FloatArray) {

    private val m: FloatArray = FloatArray(xs.size)

    init {
        val n = xs.size
        if (n >= 3) {
            // Solve the tridiagonal system for second derivatives (natural boundaries).
            val a = FloatArray(n)
            val b = FloatArray(n)
            val c = FloatArray(n)
            val d = FloatArray(n)
            for (i in 1 until n - 1) {
                val h0 = (xs[i] - xs[i - 1]).coerceAtLeast(1e-6f)
                val h1 = (xs[i + 1] - xs[i]).coerceAtLeast(1e-6f)
                a[i] = h0
                b[i] = 2f * (h0 + h1)
                c[i] = h1
                d[i] = 6f * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0)
            }
            b[0] = 1f; c[0] = 0f; d[0] = 0f
            a[n - 1] = 0f; b[n - 1] = 1f; d[n - 1] = 0f
            // Thomas algorithm.
            for (i in 1 until n) {
                val w = a[i] / b[i - 1]
                b[i] -= w * c[i - 1]
                d[i] -= w * d[i - 1]
            }
            m[n - 1] = d[n - 1] / b[n - 1]
            for (i in n - 2 downTo 0) {
                m[i] = (d[i] - c[i] * m[i + 1]) / b[i]
            }
        }
    }

    fun valueAt(x: Float): Float {
        val n = xs.size
        if (n == 0) return 0f
        if (n == 1) return ys[0]
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var hi = 1
        while (hi < n - 1 && xs[hi] < x) hi++
        val lo = hi - 1
        val h = (xs[hi] - xs[lo]).coerceAtLeast(1e-6f)
        val t = x - xs[lo]
        if (n == 2) return ys[lo] + (ys[hi] - ys[lo]) * (t / h)
        val u = h - t
        return (m[lo] * u * u * u + m[hi] * t * t * t) / (6f * h) +
            (ys[lo] / h - m[lo] * h / 6f) * u +
            (ys[hi] / h - m[hi] * h / 6f) * t
    }
}

/** §8.2 Doppler shift maths (Appendix A.1). */
object Doppler {

    /**
     * `f' = f / (1 - v/c)`, v positive when the source approaches the listener.
     * Returns the pitch ratio (1 = unshifted).
     */
    fun pitchRatio(radialVelocityMs: Float): Float {
        val factor = (1f - radialVelocityMs / Geometry.SPEED_OF_SOUND).coerceIn(0.5f, 1.5f)
        return 1f / factor
    }

    /** `cents = 1200 * log2(doppler_factor)`. */
    fun centsShift(radialVelocityMs: Float): Float {
        val factor = (1f - radialVelocityMs / Geometry.SPEED_OF_SOUND).coerceIn(0.5f, 1.5f)
        return -1200f * log2(factor)
    }

    /**
     * Propagation delay in samples for a source at [distanceM].
     *
     * Deviation from §8.2: the guideline pitch-shifts with a phase vocoder
     * (`librosa.effects.pitch_shift`), which is an offline, block-based, latency-heavy
     * operation. Ceecept instead feeds distance into a fractional delay line — the
     * physically correct mechanism. Doppler then *emerges* from the changing delay, with
     * no added latency and no vocoder smearing, and it is exactly `f/(1-v/c)`.
     */
    fun propagationDelaySamples(distanceM: Float, sampleRate: Int): Float =
        (distanceM / Geometry.SPEED_OF_SOUND) * sampleRate
}
