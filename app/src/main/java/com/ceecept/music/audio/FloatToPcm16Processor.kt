package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Terminal converter of the DSP chain: float (or any supported PCM) in,
 * 16-bit PCM out.
 *
 * The DSP runs in 32-bit float end to end, but the AudioTrack always receives
 * plain 16-bit PCM. This avoids two device-specific failure modes seen in the
 * wild: firmware that accepts a float AudioTrack and plays silence, and sinks
 * that refuse to configure when a float-producing chain meets a 16-bit output.
 */
class FloatToPcm16Processor : BaseAudioProcessor() {

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AF): AF {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_FLOAT &&
            enc != C.ENCODING_PCM_16BIT &&
            enc != C.ENCODING_PCM_24BIT &&
            enc != C.ENCODING_PCM_32BIT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return AF(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFormat = inputAudioFormat
        if (inFormat === AF.NOT_SET) return
        val channels = inFormat.channelCount
        val bytesPerFrame = inFormat.bytesPerFrame
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frames = remaining / bytesPerFrame
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = replaceOutputBuffer(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) {
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
            out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.flip()
    }
}
