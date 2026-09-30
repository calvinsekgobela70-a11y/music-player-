package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin


/**
 * "Ceecept Immerse" 3D audio for headphones.
 *
 * - Virtual speakers rendered with an HRTF model: inter-aural time differences
 *   (Woodworth formula), inter-aural level differences with contralateral HF
 *   shadowing, and pinna (outer-ear) coloration that tracks elevation.
 * - Image-source style early reflections (12 taps) with per-tap direction.
 * - 8-line feedback-delay-network late reverb with modulated lines, damping
 *   and room-size control ("controlled reverb").
 * - 3D coordinates: azimuth / elevation / distance / width place the scene.
 */
data class SpaceParams(
    val enabled: Boolean = true,
    /** Overall immersion 0..1. */
    val strength: Float = 0.8f,
    /** Scene rotation in degrees, -180..180. */
    val azimuth: Float = 0f,
    /** Scene elevation in degrees, -40..90. */
    val elevation: Float = 12f,
    /** Virtual listening distance in metres, 0.5..4. */
    val distance: Float = 1.6f,
    /** Speaker spread 0..1.5 (1 = ±30°). */
    val width: Float = 1f,
    /** Room size 0..1. */
    val roomSize: Float = 0.55f,
    /** Late reverb amount 0..1. */
    val reverb: Float = 0.35f,
    /** HF damping 0..1. */
    val damping: Float = 0.5f
) {
    companion object {
        val DEFAULT = SpaceParams()
        val PRESETS: Map<String, SpaceParams> = mapOf(
            "Off" to SpaceParams(enabled = false),
            "Natural" to SpaceParams(strength = 0.65f, width = 0.9f, roomSize = 0.4f, reverb = 0.22f, damping = 0.45f),
            "Wide Stage" to SpaceParams(strength = 0.8f, width = 1.35f, roomSize = 0.5f, reverb = 0.28f, damping = 0.5f),
            "Concert Hall" to SpaceParams(strength = 0.9f, elevation = 18f, distance = 2.6f, width = 1.1f, roomSize = 0.85f, reverb = 0.55f, damping = 0.35f),
            "Club" to SpaceParams(strength = 0.85f, distance = 1.2f, width = 1.2f, roomSize = 0.65f, reverb = 0.4f, damping = 0.6f),
            "Cinema" to SpaceParams(strength = 1f, elevation = 8f, distance = 2.2f, width = 1.25f, roomSize = 0.75f, reverb = 0.45f, damping = 0.45f),
            "Intimate" to SpaceParams(strength = 0.55f, distance = 0.8f, width = 0.7f, roomSize = 0.3f, reverb = 0.15f, damping = 0.55f)
        )
    }
}

/** Schroeder allpass used for input diffusion. */
private class DiffusionAllpass(maxSamples: Int) {
    private val line = DelayLine(maxSamples)
    var delaySamples = 0f
    var gain = 0.55f

    fun configure(delay: Float, g: Float) {
        line.delay = delay
        delaySamples = delay
        gain = g
    }

    fun process(x: Float): Float {
        val d = line.peek()
        val v = x + gain * d
        line.store(v)
        return -gain * v + d
    }

    fun clear() = line.clear()
}

class SpatializerProcessor : BaseAudioProcessor() {

    val params = AtomicReference(SpaceParams.DEFAULT)

    private var sampleRate = 48000

    // HRTF: 4 paths (Ls->L, Ls->R, Rs->L, Rs->R).
    private var itdLines = Array(4) { DelayLine(512) }
    private val contraLp = FloatArray(4)
    private val contraCoef = FloatArray(4)
    private val pathGain = FloatArray(4)

    // Pinna filters per ear (concha peak + elevation notch).
    private val pinnaPeakL = Biquad(); private val pinnaNotchL = Biquad()
    private val pinnaPeakR = Biquad(); private val pinnaNotchR = Biquad()
    private val pinnaStateL = Array(2) { BiquadState() }
    private val pinnaStateR = Array(2) { BiquadState() }

    // Early reflections.
    private val taps = 12
    private var earlyLines: Array<DelayLine> = emptyArray()
    private val tapDelayMs = FloatArray(taps)
    private val tapAzim = FloatArray(taps)
    private val tapGainL = FloatArray(taps)
    private val tapGainR = FloatArray(taps)

    // Late reverb: 8-line FDN + diffusion.
    private val fdnN = 8
    private var fdnLines: Array<DelayLine> = emptyArray()
    private val fdnBase = FloatArray(fdnN)
    private val fdnFb = FloatArray(fdnN)
    private val fdnLp = FloatArray(fdnN)
    private var fdnLpCoef = 0.3f
    private val lfoPhase = FloatArray(fdnN)
    private val lfoInc = FloatArray(fdnN)
    private val fdnRead = FloatArray(fdnN)
    private var diffL1 = DiffusionAllpass(1024)
    private var diffL2 = DiffusionAllpass(1024)
    private var diffR1 = DiffusionAllpass(1024)
    private var diffR2 = DiffusionAllpass(1024)

    // Air absorption + mix smoothing.
    private var airL = 0f
    private var airR = 0f
    private var airCoef = 1f
    private var wetGain = 0f
    private var dryGain = 1f

    private var appliedParams: SpaceParams? = null
    private var scratch: FloatArray = FloatArray(0)

    companion object {
        private const val HEAD_RADIUS = 0.0875f
        private const val SPEED_OF_SOUND = 343f
        // Co-prime-ish FDN line lengths (ms @ any rate, scaled).
        private val FDN_MS = floatArrayOf(29.7f, 33.9f, 37.3f, 41.5f, 45.9f, 50.1f, 54.7f, 59.3f)
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AF): AF {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT &&
            enc != C.ENCODING_PCM_24BIT &&
            enc != C.ENCODING_PCM_32BIT &&
            enc != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..8) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        allocate()
        appliedParams = null
        // Immerse always renders a stereo headphone image.
        return AF(sampleRate, 2, C.ENCODING_PCM_FLOAT)
    }

    private fun allocate() {
        val sr = sampleRate
        val itdMax = (0.002f * sr).toInt() + 8
        itdLines = Array(4) { DelayLine(itdMax) }
        val earlyMax = (0.12f * sr).toInt() + 8
        earlyLines = Array(taps) { DelayLine(earlyMax) }
        val fdnMax = (0.13f * sr).toInt() + 8
        fdnLines = Array(fdnN) { DelayLine(fdnMax) }
        val apMax = (0.03f * sr).toInt() + 8
        diffL1 = DiffusionAllpass(apMax); diffL2 = DiffusionAllpass(apMax)
        diffR1 = DiffusionAllpass(apMax); diffR2 = DiffusionAllpass(apMax)
        // Fixed tap geometry (golden-angle spread, prime-ish delays).
        for (i in 0 until taps) {
            tapAzim[i] = ((i * 137.5f) % 360f) - 180f
            tapDelayMs[i] = 4f + i * 5.9f + (i * 37 % 11) * 0.7f
        }
        val lfoRates = floatArrayOf(0.09f, 0.13f, 0.07f, 0.17f, 0.11f, 0.19f, 0.15f, 0.23f)
        for (i in 0 until fdnN) {
            lfoInc[i] = (2f * PI.toFloat() * lfoRates[i] / sr)
            lfoPhase[i] = i * 0.9f
        }
    }

    /** Recompute all derived coefficients from [p]. Called on the audio thread. */
    private fun rebuild(p: SpaceParams) {
        val sr = sampleRate.toFloat()

        // --- Virtual speaker HRTF ---
        val spread = 30f * p.width
        val azL = p.azimuth - spread
        val azR = p.azimuth + spread
        configurePath(0, azL) // Ls -> L
        configurePath(1, azL) // Ls -> R (contralateral handling inside)
        configurePath(2, azR) // Rs -> L
        configurePath(3, azR) // Rs -> R

        // --- Pinna (outer ear) coloration tracks elevation ---
        val elevN = ((p.elevation + 40f) / 130f).coerceIn(0f, 1f)
        val notchF = 5500f + elevN * 5500f
        pinnaPeakL.setPeaking(4200f, 1.1f, 3.5f, sampleRate)
        pinnaNotchL.setPeaking(notchF, 3.2f, -7f, sampleRate)
        pinnaPeakR.setPeaking(4300f, 1.1f, 3.5f, sampleRate)
        pinnaNotchR.setPeaking(notchF * 1.02f, 3.2f, -7f, sampleRate)

        // --- Early reflections ---
        val timeScale = 0.5f + p.roomSize
        for (i in 0 until taps) {
            val dMs = tapDelayMs[i] * timeScale
            earlyLines[i].delay = dMs / 1000f * sr
            val az = Math.toRadians((tapAzim[i] + p.azimuth).toDouble())
            val pan = (sin(az) * 0.5 + 0.5).toFloat()
            val decay = Math.exp((-dMs / 1000f) * 26.0 / timeScale).toFloat()
            val g = decay * 0.30f
            val theta = pan * PI.toFloat() / 2f
            tapGainL[i] = cos(theta) * g
            tapGainR[i] = sin(theta) * g
        }

        // --- Late FDN reverb ---
        val t60 = 0.35f + p.roomSize * 3.85f
        val lenScale = 0.55f + 0.9f * p.roomSize
        for (i in 0 until fdnN) {
            val dSec = FDN_MS[i] / 1000f * lenScale
            fdnBase[i] = dSec * sr
            val g = Math.pow(10.0, (-3.0 * dSec / t60).toDouble()).toFloat()
            fdnFb[i] = g.coerceAtMost(0.985f)
        }
        fdnLpCoef = Dsp.onePoleLowPassCoef(18000f - p.damping * 15500f, sampleRate)
        diffL1.configure(0.0073f * sr, 0.55f)
        diffL2.configure(0.0117f * sr, 0.5f)
        diffR1.configure(0.0081f * sr, 0.55f)
        diffR2.configure(0.0123f * sr, 0.5f)

        // --- Air absorption + mix ---
        val distN = ((p.distance - 0.5f) / 3.5f).coerceIn(0f, 1f)
        airCoef = Dsp.onePoleLowPassCoef(19000f - distN * 14500f, sampleRate)
        val s = p.strength.coerceIn(0f, 1f)
        dryGain = cos(s * PI.toFloat() / 2f * 0.85f) * (1.4f / (0.6f + 0.5f * p.distance))
        wetGain = sin(s * PI.toFloat() / 2f) * 1.15f
        appliedParams = p
    }

    /**
     * Configures one HRTF path: [path] 0=Ls->L, 1=Ls->R, 2=Rs->L, 3=Rs->R.
     * Uses the Woodworth ITD model + contralateral level/HF shadowing.
     */
    private fun configurePath(path: Int, sourceAzimDeg: Float) {
        val toLeftEar = path == 0 || path == 2
        // Angle from the ear's axis: +ve means contralateral (shadowed).
        val rel = if (toLeftEar) sourceAzimDeg else -sourceAzimDeg
        val clamped = rel.coerceIn(-90f, 90f)
        val rad = Math.toRadians(clamped.toDouble())
        // Woodworth: ITD = a/c * (theta + sin theta) for the far ear.
        val itd = if (rad > 0) {
            HEAD_RADIUS / SPEED_OF_SOUND * (rad + sin(rad))
        } else {
            0.0
        }
        itdLines[path].delay = ((0.00008 + itd) * sampleRate).toFloat()
        val shadow = (sin(rad).coerceAtLeast(0.0)).toFloat() // 0 ipsi .. 1 full contra
        pathGain[path] = 1f - 0.55f * shadow
        contraCoef[path] = Dsp.onePoleLowPassCoef(14000f - shadow * 11200f, sampleRate)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFormat = inputAudioFormat
        if (inFormat === AF.NOT_SET) return
        val p = params.get()
        if (appliedParams !== p) rebuild(p)

        val inChannels = inFormat.channelCount
        val bytesPerFrame = inFormat.bytesPerFrame
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frames = remaining / bytesPerFrame
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        val s = scratch

        // Decode + fold down to stereo.
        for (f in 0 until frames) {
            var l = 0f
            var r = 0f
            for (c in 0 until inChannels) {
                val v = when (inFormat.encoding) {
                    C.ENCODING_PCM_16BIT -> inputBuffer.short / 32768f
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = inputBuffer.get().toInt() and 0xFF
                        val b1 = inputBuffer.get().toInt() and 0xFF
                        val b2 = inputBuffer.get().toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    C.ENCODING_PCM_32BIT -> inputBuffer.int / 2147483648f
                    else -> inputBuffer.float
                }
                when (c) {
                    0 -> l += v
                    1 -> r += v
                    else -> {
                        l += v * 0.4f
                        r += v * 0.4f
                    }
                }
            }
            if (inChannels > 2) {
                l *= 0.8f
                r *= 0.8f
            }
            if (inChannels == 1) r = l
            s[f * 2] = l
            s[f * 2 + 1] = r
        }

        if (!p.enabled) {
            writeOutput(s, frames)
            return
        }

        val revWet = p.reverb * 0.9f + 0.05f
        val dg = dryGain
        val wg = wetGain

        for (f in 0 until frames) {
            val inL = s[f * 2]
            val inR = s[f * 2 + 1]
            val mono = (inL + inR) * 0.5f

            // --- HRTF virtual speakers ---
            var hL = pathGain[0] * lp(0, itdLines[0].push(inL)) +
                    pathGain[2] * lp(2, itdLines[2].push(inR))
            var hR = pathGain[1] * lp(1, itdLines[1].push(inL)) +
                    pathGain[3] * lp(3, itdLines[3].push(inR))
            hL = pinnaNotchL.process(pinnaPeakL.process(hL, pinnaStateL[0]), pinnaStateL[1])
            hR = pinnaNotchR.process(pinnaPeakR.process(hR, pinnaStateR[0]), pinnaStateR[1])

            // --- Early reflections ---
            var eL = 0f
            var eR = 0f
            for (i in 0 until taps) {
                val t = earlyLines[i].push(mono)
                eL += t * tapGainL[i]
                eR += t * tapGainR[i]
            }

            // --- Late FDN reverb ---
            val dL = diffL2.process(diffL1.process(inL + mono * 0.3f))
            val dR = diffR2.process(diffR1.process(inR + mono * 0.3f))
            for (i in 0 until fdnN) {
                lfoPhase[i] += lfoInc[i]
                if (lfoPhase[i] > 2f * PI.toFloat()) lfoPhase[i] -= 2f * PI.toFloat()
                fdnLines[i].delay = fdnBase[i] * (1f + 0.004f * sin(lfoPhase[i]))
                fdnRead[i] = fdnLines[i].peek()
            }
            // Damping + decay + Householder mixing.
            var mean = 0f
            for (i in 0 until fdnN) {
                fdnLp[i] += fdnLpCoef * (fdnRead[i] - fdnLp[i])
                fdnRead[i] = fdnLp[i] * fdnFb[i]
                mean += fdnRead[i]
            }
            mean = mean * 2f / fdnN
            for (i in 0 until fdnN) {
                val mixed = fdnRead[i] - mean
                val input = (if (i % 2 == 0) dL else dR) * 0.35f
                fdnLines[i].store(input + mixed)
            }
            val rL = (fdnRead[0] - fdnRead[2] + fdnRead[4] - fdnRead[6]) * 0.3f
            val rR = (-fdnRead[1] + fdnRead[3] - fdnRead[5] + fdnRead[7]) * 0.3f

            // --- Air absorption + mix ---
            var wetL = hL * 0.9f + eL * 1.1f + rL * revWet * 1.4f
            var wetR = hR * 0.9f + eR * 1.1f + rR * revWet * 1.4f
            airL += airCoef * (wetL - airL)
            airR += airCoef * (wetR - airR)
            wetL = airL
            wetR = airR

            s[f * 2] = Dsp.softClip(inL * dg + wetL * wg)
            s[f * 2 + 1] = Dsp.softClip(inR * dg + wetR * wg)
        }

        writeOutput(s, frames)
    }

    private fun lp(path: Int, x: Float): Float {
        contraLp[path] += contraCoef[path] * (x - contraLp[path])
        return contraLp[path]
    }

    private fun writeOutput(s: FloatArray, frames: Int) {
        val out = replaceOutputBuffer(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * 2) out.putFloat(s[i].coerceIn(-1f, 1f))
        out.flip()
    }

    override fun onFlush() {
        itdLines.forEach { it.clear() }
        earlyLines.forEach { it.clear() }
        fdnLines.forEach { it.clear() }
        diffL1.clear(); diffL2.clear(); diffR1.clear(); diffR2.clear()
        contraLp.fill(0f)
        fdnLp.fill(0f)
        pinnaStateL.forEach { it.clear() }
        pinnaStateR.forEach { it.clear() }
        airL = 0f; airR = 0f
    }

    override fun onReset() {
        onFlush()
    }
}
