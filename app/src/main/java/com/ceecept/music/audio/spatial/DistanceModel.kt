package com.ceecept.music.audio.spatial

import kotlin.math.log10

/**
 * Distance perception modelling — guidelines §7.
 *
 * All three cues the brain uses are modelled: level (inverse square law), spectrum
 * (air absorption) and the direct/reverberant ratio (reflection and tail amount).
 */
object DistanceModel {

    /** §7.1 inverse square law. `Gain(dB) = 20*log10(reference / distance)`. */
    fun attenuationDb(distanceM: Float, referenceDistanceM: Float = 1.0f): Float =
        20f * log10(referenceDistanceM / (distanceM + 1e-10f))

    fun attenuationLinear(distanceM: Float, referenceDistanceM: Float = 1.0f): Float =
        Math.pow(10.0, (attenuationDb(distanceM, referenceDistanceM) / 20f).toDouble()).toFloat()

    /**
     * §7.2 air absorption cutoff. The guideline's step table (20 k / 12 k / 8 k / 4 k)
     * is interpolated so that a moving object glides through the bands instead of
     * stepping — a stepped cutoff is audible as a click during trajectories.
     */
    fun airAbsorptionCutoffHz(distanceM: Float): Float {
        val d = distanceM.coerceIn(0f, 30f)
        return when {
            d < 2f -> 20000f
            d < 5f -> lerp(20000f, 12000f, (d - 2f) / 3f)
            d < 10f -> lerp(12000f, 8000f, (d - 5f) / 5f)
            d < 20f -> lerp(8000f, 4000f, (d - 10f) / 10f)
            else -> 4000f
        }
    }

    /** §7.3 wet amount: close-miked sources are dry, distant sources are ambient. */
    fun reverbWet(distanceM: Float): Float = when {
        distanceM < 1f -> 0.05f
        distanceM < 3f -> lerp(0.05f, 0.15f, (distanceM - 1f) / 2f)
        distanceM < 8f -> lerp(0.15f, 0.35f, (distanceM - 3f) / 5f)
        else -> lerp(0.35f, 0.60f, ((distanceM - 8f) / 8f).coerceIn(0f, 1f))
    }

    /** §7.3 first reflection arrival — proportional to path length (2.9 ms per metre). */
    fun earlyReflectionDelayMs(distanceM: Float): Float = distanceM * 2.9f

    /** §7.3 decay time; [roomSize] 0..1 maps small(0.5s) → medium(1.2s) → large(2.0s). */
    fun decayTimeSec(distanceM: Float, roomSize: Float): Float {
        val base = when {
            roomSize < 0.33f -> lerp(0.5f, 1.2f, roomSize / 0.33f)
            roomSize < 0.66f -> lerp(1.2f, 2.0f, (roomSize - 0.33f) / 0.33f)
            else -> lerp(2.0f, 3.6f, (roomSize - 0.66f) / 0.34f)
        }
        return base + distanceM / 10f
    }

    /** §7.3 pre-delay before the tail starts. */
    const val PREDELAY_MS = 10f

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
