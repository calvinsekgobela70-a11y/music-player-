package com.ceecept.music.audio.spatial

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Multi-speaker rendering engine — guidelines §4.
 *
 * Both panning laws write normalised gains into a caller-supplied array indexed by
 * speaker order, so the audio thread never allocates.
 */
object Panning {

    /** Objects further than this from a speaker get no energy from it (§4.1). */
    const val ASSIGNMENT_CONE_DEG = 90f

    /**
     * The 90° cone of §4.1 assumes a dense rig. On a two-speaker layout, whose drivers
     * sit 180° apart, it silences everything within 90° of the far speaker: an object
     * 6° left of centre lands entirely in the left channel, and an object dead centre
     * gets no energy at all. The cone is therefore widened to just over the largest gap
     * between adjacent speakers, which leaves 5.1 and 7.1.4 on the guideline's own
     * value and makes stereo behave like a proper pairwise pan.
     */
    fun coneFor(layout: SpeakerLayout): Float {
        val azimuths = layout.speakers.filter { !it.isLfe }.map { it.azimuthDeg }.sorted()
        if (azimuths.size < 2) return 360f
        var maxGap = 360f + azimuths.first() - azimuths.last()
        for (i in 1 until azimuths.size) {
            val gap = azimuths[i] - azimuths[i - 1]
            if (gap > maxGap) maxGap = gap
        }
        return maxOf(ASSIGNMENT_CONE_DEG, maxGap * 1.1f)
    }

    /**
     * §4.1 `adaptive_render()` — gain by angular proximity, with distance compensation.
     * Positions are already listener-relative. LFE is skipped (it is fed by bass
     * management instead, per the `bass_management` rendering hint in §13).
     */
    fun adaptiveRender(
        objAzimuth: Float,
        objElevation: Float,
        objDistance: Float,
        layout: SpeakerLayout,
        out: FloatArray,
        coneDeg: Float = ASSIGNMENT_CONE_DEG
    ) {
        var total = 0f
        for (i in layout.speakers.indices) {
            val sp = layout.speakers[i]
            if (sp.isLfe) {
                out[i] = 0f
                continue
            }
            val angleDiff = Geometry.angularDistanceDeg(
                objAzimuth, objElevation, sp.azimuthDeg, sp.elevationDeg
            )
            if (angleDiff < coneDeg) {
                // Inverse of the angular distance: closer angles, higher gain.
                var gain = 1f / (1f + angleDiff)
                // Distance attenuation relative to the speaker's own radius.
                val distanceFactor = sp.distanceM / (objDistance + 0.1f)
                gain *= distanceFactor.toDouble().pow(0.5).toFloat()
                out[i] = if (gain > 0f) gain else 0f
                total += out[i]
            } else {
                out[i] = 0f
            }
        }
        normalise(out, layout.totalSpeakers, total)
    }

    /**
     * §4.2 vector panning with a tangential curve: `gain = max(0, dot)^1.5`.
     * Horizontal only — used for the bands whose elevation cue is suppressed, and as
     * the reference law for the azimuth-accuracy QA metric (§14.1).
     */
    fun vectorPanning(azimuthDeg: Float, layout: SpeakerLayout, out: FloatArray) {
        val azRad = Geometry.rad(azimuthDeg)
        val panX = sin(azRad)
        val panY = cos(azRad)
        var total = 0f
        for (i in layout.speakers.indices) {
            val sp = layout.speakers[i]
            if (sp.isLfe) {
                out[i] = 0f
                continue
            }
            val spRad = Geometry.rad(sp.azimuthDeg)
            val dot = panX * sin(spRad) + panY * cos(spRad)
            val gain = if (dot > 0f) dot.toDouble().pow(1.5).toFloat() else 0f
            out[i] = gain
            total += gain
        }
        normalise(out, layout.totalSpeakers, total)
    }

    /**
     * Blend of both laws. [tangential] 0 = pure adaptive (§4.1), 1 = pure vector (§4.2).
     * The engine uses a blend: the adaptive law keeps elevation information, the vector
     * law removes the dead spots the pure 1/(1+angle) curve leaves between speakers.
     */
    fun blended(
        objAzimuth: Float,
        objElevation: Float,
        objDistance: Float,
        layout: SpeakerLayout,
        tangential: Float,
        out: FloatArray,
        scratch: FloatArray,
        coneDeg: Float = ASSIGNMENT_CONE_DEG
    ) {
        adaptiveRender(objAzimuth, objElevation, objDistance, layout, out, coneDeg)
        if (tangential <= 0f) return
        vectorPanning(objAzimuth, layout, scratch)
        val t = tangential.coerceIn(0f, 1f)
        var total = 0f
        for (i in 0 until layout.totalSpeakers) {
            out[i] = out[i] * (1f - t) + scratch[i] * t
            total += out[i]
        }
        normalise(out, layout.totalSpeakers, total)
    }

    /**
     * Angular spread (§2.3 `size_spread`): widen an object by smearing its gain vector
     * towards neighbouring speakers. [spreadDeg] 0 = point source.
     */
    fun applySpread(out: FloatArray, layout: SpeakerLayout, objAzimuth: Float, objElevation: Float, spreadDeg: Float) {
        if (spreadDeg <= 1f) return
        var total = 0f
        for (i in 0 until layout.totalSpeakers) {
            val sp = layout.speakers[i]
            if (sp.isLfe) continue
            val angle = Geometry.angularDistanceDeg(objAzimuth, objElevation, sp.azimuthDeg, sp.elevationDeg)
            // Gaussian window of width `spreadDeg` added to the point-source gains.
            val w = Math.exp(-(angle * angle) / (2.0 * spreadDeg * spreadDeg)).toFloat()
            out[i] = out[i] + w * 0.6f
            total += out[i]
        }
        normalise(out, layout.totalSpeakers, total)
    }

    private fun normalise(out: FloatArray, n: Int, total: Float) {
        if (total > 1e-10f) {
            val inv = 1f / total
            for (i in 0 until n) out[i] *= inv
        }
    }
}

/**
 * Pairwise (VBAP-style) panner — the renderer's primary law.
 *
 * Why this exists alongside [Panning]: the guideline's two laws (§4.1 inverse-angle,
 * §4.2 tangential vector) both hand energy to *every* speaker inside a 90° cone, which
 * smears the phantom image. Measured with the Gerzon velocity vector, the localisation
 * error of §4.1 on the 7.1.4 layout peaks at ~19°, and §14.1 demands better than 5°.
 *
 * This law keeps the guideline's geometry (§2.2 layouts, §3 spherical frame) but drives
 * only the two speakers that bracket the object, with the tangent law between them, so
 * the velocity vector lands exactly on the requested azimuth. Elevation is handled the
 * way immersive rigs do it: pan within the horizontal layer, pan within the height
 * layer, then crossfade the two with constant power (§5.1).
 */
class PairwisePanner(layout: SpeakerLayout) {

    private class LayerSpeaker(val index: Int, val azimuth: Float)

    private val horizontal: List<LayerSpeaker>
    private val height: List<LayerSpeaker>
    private val speakerCount = layout.totalSpeakers

    init {
        val h = ArrayList<LayerSpeaker>()
        val v = ArrayList<LayerSpeaker>()
        layout.speakers.forEachIndexed { index, speaker ->
            if (speaker.isLfe) return@forEachIndexed
            if (speaker.elevationDeg > Elevation.HEIGHT_ROUTING_THRESHOLD_DEG) {
                v.add(LayerSpeaker(index, speaker.azimuthDeg))
            } else {
                h.add(LayerSpeaker(index, speaker.azimuthDeg))
            }
        }
        horizontal = h.sortedBy { it.azimuth }
        height = v.sortedBy { it.azimuth }
    }

    val hasHeightLayer: Boolean get() = height.isNotEmpty()

    /** Write unit-power gains for one object into [out]. */
    fun pan(azimuthDeg: Float, elevationDeg: Float, out: FloatArray) {
        for (i in 0 until minOf(out.size, speakerCount)) out[i] = 0f
        val az = Geometry.wrapDeg(azimuthDeg)
        if (height.isEmpty() || elevationDeg <= 0f) {
            panLayer(az, horizontal, 1f, out)
            return
        }
        // Constant-power crossfade between the two layers.
        val w = (elevationDeg / 60f).coerceIn(0f, 1f)
        val theta = w * (Math.PI.toFloat() / 2f)
        panLayer(az, horizontal, cos(theta), out)
        panLayer(az, height, sin(theta), out)
    }

    private fun panLayer(az: Float, layer: List<LayerSpeaker>, scale: Float, out: FloatArray) {
        if (layer.isEmpty() || scale <= 0f) return
        if (layer.size == 1) {
            out[layer[0].index] += scale
            return
        }
        // Find the circular pair that brackets the object.
        var lower = layer[layer.size - 1]
        var upper = layer[0]
        var lowerAz = layer[layer.size - 1].azimuth - 360f
        var upperAz = layer[0].azimuth
        var target = az
        var found = false
        for (k in layer.indices) {
            val a = layer[k]
            val b = layer[(k + 1) % layer.size]
            var lo = a.azimuth
            var hi = b.azimuth
            if (hi < lo) hi += 360f
            var t = az
            while (t < lo) t += 360f
            while (t > hi) t -= 360f
            if (t in lo..hi) {
                lower = a; upper = b; lowerAz = lo; upperAz = hi; target = t
                found = true
                break
            }
        }
        if (!found) {
            out[layer[0].index] += scale
            return
        }
        val half = (upperAz - lowerAz) / 2f
        val mid = (lowerAz + upperAz) / 2f
        val rel = target - mid
        var g1: Float
        var g2: Float
        when {
            half < 1e-3f -> {
                g1 = 1f; g2 = 0f
            }
            // A pair spanning 180° or more has no tangent-law solution (tan blows up);
            // fall back to a constant-power sine/cosine sweep across the arc.
            half >= 89.9f -> {
                val t = ((rel / half) + 1f) * (Math.PI.toFloat() / 4f)
                g1 = cos(t); g2 = sin(t)
            }
            else -> {
                val tanRel = kotlin.math.tan(Geometry.rad(rel))
                val tanHalf = kotlin.math.tan(Geometry.rad(half))
                g1 = tanHalf - tanRel
                g2 = tanHalf + tanRel
            }
        }
        if (g1 < 0f) g1 = 0f
        if (g2 < 0f) g2 = 0f
        val norm = kotlin.math.sqrt(g1 * g1 + g2 * g2)
        if (norm < 1e-9f) return
        out[lower.index] += scale * g1 / norm
        out[upper.index] += scale * g2 / norm
    }

    /**
     * §2.3 `size_spread` / `diffuse_amount`: blend the point-source solution towards an
     * even spread over the layer, preserving total power. Used for the bass bands,
     * which the guideline marks non-directional.
     */
    fun applyDiffusion(out: FloatArray, amount: Float) {
        if (amount <= 0f) return
        val a = amount.coerceIn(0f, 1f)
        val n = horizontal.size + height.size
        if (n == 0) return
        val even = 1f / kotlin.math.sqrt(n.toFloat())
        var power = 0f
        for (s in horizontal) {
            out[s.index] = out[s.index] * (1f - a) + even * a
            power += out[s.index] * out[s.index]
        }
        for (s in height) {
            out[s.index] = out[s.index] * (1f - a) + even * a
            power += out[s.index] * out[s.index]
        }
        if (power > 1e-12f) {
            val norm = 1f / kotlin.math.sqrt(power)
            for (s in horizontal) out[s.index] *= norm
            for (s in height) out[s.index] *= norm
        }
    }
}
