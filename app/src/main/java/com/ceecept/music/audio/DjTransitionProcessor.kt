package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Real-time DJ transition processor used by Ceecept DJ Mode.
 *
 * The queue/decision layer follows the AUTOMIX guideline: beat/key/structure analysis,
 * phrase-locked mix points, compatibility scoring, and fallback when a track is not
 * safely mixable. This processor performs the audible handover treatment available in
 * a single-player Media3 chain: outgoing EQ/bass removal, incoming intro opening,
 * transition loudness compensation, and beat-synced echo tails for drop/echo mixes.
 */
class DjTransitionProcessor : BaseAudioProcessor() {

    companion object {
        const val MODE_NONE = 0
        const val MODE_INTRO = 1
        const val MODE_OUTRO = 2

        const val TYPE_SIMPLE = 0
        const val TYPE_LONG_BLEND = 1
        const val TYPE_MEDIUM_BLEND = 2
        const val TYPE_SHORT_BLEND = 3
        const val TYPE_DROP_MIX = 4
        const val TYPE_ECHO_COLD = 5
    }

    @Volatile var enabled: Boolean = false
    @Volatile private var mode: Int = MODE_NONE
    @Volatile private var progress: Float = 0f
    @Volatile private var bpm: Float = 120f
    @Volatile private var transitionType: Int = TYPE_SIMPLE
    @Volatile private var overlapIntensity: Float = 0f

    private var channels = 2
    private var sampleRate = 48000
    private var scratch = FloatArray(0)

    private var hp = Biquad()
    private var lp = Biquad()
    private var presence = Biquad()
    private var hpStates: Array<BiquadState> = emptyArray()
    private var lpStates: Array<BiquadState> = emptyArray()
    private var presenceStates: Array<BiquadState> = emptyArray()
    private var echoLines: Array<DelayLine> = emptyArray()
    private var lastMode = -1
    private var lastType = -1
    private var lastBucket = -1

    fun setTransition(
        enabled: Boolean,
        mode: Int,
        progress: Float,
        bpm: Float,
        type: Int = TYPE_SIMPLE,
        overlapIntensity: Float = 0f
    ) {
        this.enabled = enabled
        this.mode = mode.coerceIn(MODE_NONE, MODE_OUTRO)
        this.progress = progress.coerceIn(0f, 1f)
        this.bpm = bpm.coerceIn(70f, 190f)
        this.transitionType = type.coerceIn(TYPE_SIMPLE, TYPE_ECHO_COLD)
        this.overlapIntensity = overlapIntensity.coerceIn(0f, 1f)
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AF): AF {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        hpStates = Array(channels) { BiquadState() }
        lpStates = Array(channels) { BiquadState() }
        presenceStates = Array(channels) { BiquadState() }
        echoLines = Array(channels) { DelayLine((sampleRate * 2.2f).toInt()).also { it.delay = sampleRate * 0.25f } }
        configureFilters(force = true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFormat = inputAudioFormat
        if (inFormat === AF.NOT_SET) return
        val frames = inputBuffer.remaining() / inFormat.bytesPerFrame
        if (frames <= 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (scratch.size < frames * channels) scratch = FloatArray(frames * channels)
        val s = scratch
        var idx = 0
        repeat(frames * channels) { s[idx++] = inputBuffer.float }

        if (enabled && mode != MODE_NONE) {
            configureFilters(force = false)
            val p = progress.coerceIn(0f, 1f)
            val masterDuck = Dsp.dbToLinear(-3f * overlapIntensity)
            val gain = when (mode) {
                MODE_INTRO -> incomingGain(p) * masterDuck
                MODE_OUTRO -> outgoingGain(p) * masterDuck
                else -> 1f
            }
            val echoAmount = echoAmount(p)
            for (f in 0 until frames) {
                val base = f * channels
                for (c in 0 until channels) {
                    var x = s[base + c]
                    x = hp.process(x, hpStates[c])
                    x = lp.process(x, lpStates[c])
                    x = presence.process(x, presenceStates[c])
                    if (echoAmount > 0f) {
                        val delayed = echoLines[c].peek()
                        val feedback = when (transitionType) {
                            TYPE_DROP_MIX -> 0.48f
                            TYPE_ECHO_COLD -> 0.58f
                            else -> 0.34f
                        } * echoAmount
                        echoLines[c].store(x + delayed * feedback)
                        x += delayed * (0.34f + 0.16f * echoAmount) * echoAmount
                    } else {
                        echoLines[c].store(x)
                    }
                    s[base + c] = Dsp.softClip(x * gain).coerceIn(-1.15f, 1.15f)
                }
            }
        }

        val out = replaceOutputBuffer(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * channels) out.putFloat(s[i])
        out.flip()
    }

    private fun incomingGain(p: Float): Float = when (transitionType) {
        TYPE_LONG_BLEND -> 0.70f + 0.30f * ease(p)
        TYPE_MEDIUM_BLEND -> 0.76f + 0.24f * ease(p)
        TYPE_SHORT_BLEND -> 0.88f + 0.12f * ease(p)
        TYPE_DROP_MIX, TYPE_ECHO_COLD -> if (p < 0.18f) 0.92f else 1f
        else -> 0.84f + 0.16f * ease(p)
    }

    private fun outgoingGain(p: Float): Float = when (transitionType) {
        TYPE_LONG_BLEND -> 1f - 0.20f * ease(p)
        TYPE_MEDIUM_BLEND -> 1f - 0.28f * ease(p)
        TYPE_SHORT_BLEND -> 1f - 0.42f * ease(p)
        TYPE_DROP_MIX -> 1f - 0.70f * ease(p)
        TYPE_ECHO_COLD -> 1f - 0.86f * ease(p)
        else -> 1f - 0.30f * ease(p)
    }

    private fun echoAmount(p: Float): Float = when (transitionType) {
        TYPE_DROP_MIX -> ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
        TYPE_ECHO_COLD -> ((p - 0.30f) / 0.70f).coerceIn(0f, 1f)
        TYPE_SHORT_BLEND -> ((p - 0.72f) / 0.28f).coerceIn(0f, 1f) * 0.55f
        else -> ((p - 0.82f) / 0.18f).coerceIn(0f, 1f) * 0.28f
    }

    private fun configureFilters(force: Boolean) {
        val bucket = (progress * 48f).toInt()
        val type = transitionType
        if (!force && mode == lastMode && type == lastType && bucket == lastBucket) return
        lastMode = mode
        lastType = type
        lastBucket = bucket
        val p = progress.coerceIn(0f, 1f)
        when (mode) {
            MODE_INTRO -> configureIntro(type, p)
            MODE_OUTRO -> configureOutro(type, p)
            else -> {
                hp.setIdentity()
                lp.setIdentity()
                presence.setIdentity()
            }
        }
        val beatSec = 60f / bpm.coerceIn(70f, 190f)
        val division = if (type == TYPE_ECHO_COLD || type == TYPE_DROP_MIX) 0.25f else 0.5f
        val delaySamples = (beatSec * division * sampleRate).coerceIn(700f, sampleRate * 1.2f)
        for (d in echoLines) d.delay = delaySamples
    }

    private fun configureIntro(type: Int, p: Float) {
        val opened = ease(p)
        val hpStart = when (type) {
            TYPE_LONG_BLEND -> 260f
            TYPE_MEDIUM_BLEND -> 360f
            TYPE_SHORT_BLEND -> 620f
            TYPE_DROP_MIX, TYPE_ECHO_COLD -> 90f
            else -> 420f
        }
        val hpHz = hpStart * (1f - opened) + 24f * opened
        hp.setHighPass(hpHz, 0.82f, sampleRate)
        lp.setLowPass(19_000f, 0.7071f, sampleRate)
        presence.setIdentity()
    }

    private fun configureOutro(type: Int, p: Float) {
        val e = ease(p)
        val hpHz = when (type) {
            TYPE_LONG_BLEND -> if (p < 0.48f) 24f else 24f + 520f * ((p - 0.48f) / 0.52f)
            TYPE_MEDIUM_BLEND -> 26f + 920f * e
            TYPE_SHORT_BLEND -> 40f + 1_900f * e
            TYPE_DROP_MIX -> 90f + 3_200f * e
            TYPE_ECHO_COLD -> 180f + 4_800f * e
            else -> 28f + 850f * e
        }
        val lpHz = when (type) {
            TYPE_ECHO_COLD -> 12_000f - 4_200f * e
            TYPE_DROP_MIX -> 14_000f - 5_500f * e
            TYPE_SHORT_BLEND -> 18_000f - 7_000f * e
            else -> 19_000f - 4_500f * e
        }
        hp.setHighPass(hpHz, if (type >= TYPE_DROP_MIX) 1.35f else 0.82f, sampleRate)
        lp.setLowPass(max(4_500f, lpHz), 0.7071f, sampleRate)
        // Mid/vocal duck for clashing-key or short transitions, matching the guideline's
        // "avoid two vocals fighting" rule using a gentle wide dip.
        val midDuck = when (type) {
            TYPE_SHORT_BLEND -> -2.5f * e
            TYPE_DROP_MIX -> -4.5f * e
            TYPE_ECHO_COLD -> -5.5f * e
            else -> 0f
        }
        if (midDuck == 0f) presence.setIdentity() else presence.setPeaking(1200f, 0.75f, midDuck, sampleRate)
    }

    private fun ease(x: Float): Float = x * x * (3f - 2f * x)

    override fun onFlush() {
        hpStates.forEach { it.clear() }
        lpStates.forEach { it.clear() }
        presenceStates.forEach { it.clear() }
        echoLines.forEach { it.clear() }
    }

    override fun onReset() = onFlush()
}
