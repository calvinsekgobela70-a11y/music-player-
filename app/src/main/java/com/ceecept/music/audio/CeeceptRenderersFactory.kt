package com.ceecept.music.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Injects the Ceecept DSP chain (16-band EQ -> multiband dynamics + limiter ->
 * 3D spatializer) into ExoPlayer's audio path.
 *
 * The processors run in 32-bit float internally, but the sink delivers 16-bit
 * PCM to the AudioTrack: float output tracks are not reliable on every device
 * (some OEM firmware accepts the float track and plays silence, or fails
 * writes mid-stream). The sink resamples the float chain output to 16-bit
 * automatically, which is the maximally compatible path.
 */
class CeeceptRenderersFactory(
    context: Context,
    private val engine: AudioEngine
) : DefaultRenderersFactory(context) {

    /** Converts the float DSP output to 16-bit before the sink. */
    private val pcm16Tail = FloatToPcm16Processor()

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(
                arrayOf(engine.eq, engine.dynamics, engine.spatial, pcm16Tail)
            )
            .build()
    }
}
