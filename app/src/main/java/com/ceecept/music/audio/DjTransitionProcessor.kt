package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Real-time transition processor used by DJ Mode.
 *
 * Apple Music AutoMix and Spotify Automix are not just fixed crossfades: they analyse
 * tempo/structure, then apply beat-aware transitions. Ceecept's local-file version uses
 * queue ordering + this processor. The queue layer chooses compatible songs; this layer
 * performs the audible handover treatment around item boundaries:
 *
 *  * intro: high-pass opens and level blooms in,
 *  * outro: bass is progressively removed so the next track can land cleanly,
 *  * final beats: beat-synced echo tail, giving the transition an intentional DJ feel.
 *
 * It is deliberately cheap: one HPF, one LPF and one delay read/write per channel, only
 * while a transition is active. Outside transitions it is a transparent float pass-through.
 */
class DjTransitionProcessor : BaseAudioProcessor() {

    companion object {
        const val MODE_NONE = 0
        const val MODE_INTRO = 1
        const val MODE_OUTRO = 2
    }

    @Volatile var enabled: Boolean = false
    @Volatile private var mode: Int = MODE_NONE
    @Volatile private var progress: Float = 0f
    @Volatile private var bpm: Float = 120f

    private var channels = 2
    private var sampleRate = 48000
    private var scratch = FloatArray(0)

    private var hp = Biquad()
    private var lp = Biquad()
    private var hpStates: Array<BiquadState> = emptyArray()
    private var lpStates: Array<BiquadState> = emptyArray()
    private var echoLines: Array<DelayLine> = emptyArray()
    private var lastMode = -1
    private var lastBucket = -1

    fun setTransition(enabled: Boolean, mode: Int, progress: Float, bpm: Float) {
        this.enabled = enabled
        this.mode = mode.coerceIn(MODE_NONE, MODE_OUTRO)
        this.progress = progress.coerceIn(0f, 1f)
        this.bpm = bpm.coerceIn(70f, 190f)
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
        echoLines = Array(channels) { DelayLine((sampleRate * 1.2f).toInt()).also { it.delay = sampleRate * 0.25f } }
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
            val gain = when (mode) {
                MODE_INTRO -> 0.82f + 0.18f * p
                MODE_OUTRO -> 1f - 0.20f * p
                else -> 1f
            }
            val echoAmount = if (mode == MODE_OUTRO) ((p - 0.62f) / 0.38f).coerceIn(0f, 1f) else 0f
            for (f in 0 until frames) {
                val base = f * channels
                for (c in 0 until channels) {
                    var x = s[base + c]
                    x = hp.process(x, hpStates[c])
                    x = lp.process(x, lpStates[c])
                    if (echoAmount > 0f) {
                        val delayed = echoLines[c].peek()
                        echoLines[c].store(x + delayed * 0.34f * echoAmount)
                        x += delayed * 0.32f * echoAmount
                    } else {
                        echoLines[c].store(x)
                    }
                    s[base + c] = (x * gain).coerceIn(-1.25f, 1.25f)
                }
            }
        }

        val out = replaceOutputBuffer(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * channels) out.putFloat(s[i])
        out.flip()
    }

    private fun configureFilters(force: Boolean) {
        val bucket = (progress * 32f).toInt()
        if (!force && mode == lastMode && bucket == lastBucket) return
        lastMode = mode
        lastBucket = bucket
        val p = progress.coerceIn(0f, 1f)
        when (mode) {
            MODE_INTRO -> {
                val hpHz = 520f * (1f - p) + 28f * p
                hp.setHighPass(hpHz, 0.7071f, sampleRate)
                lp.setLowPass(19000f, 0.7071f, sampleRate)
            }
            MODE_OUTRO -> {
                val hpHz = 26f + 820f * p
                val lpHz = 19000f - 9000f * p
                hp.setHighPass(hpHz, 0.7071f, sampleRate)
                lp.setLowPass(max(4500f, lpHz), 0.7071f, sampleRate)
            }
            else -> {
                hp.setIdentity()
                lp.setIdentity()
            }
        }
        val beatSec = 60f / bpm.coerceIn(70f, 190f)
        val delaySamples = (beatSec * 0.5f * sampleRate).coerceIn(800f, sampleRate * 0.8f)
        for (d in echoLines) d.delay = delaySamples
    }

    override fun onFlush() {
        hpStates.forEach { it.clear() }
        lpStates.forEach { it.clear() }
        echoLines.forEach { it.clear() }
    }

    override fun onReset() = onFlush()
}
