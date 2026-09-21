package com.ceecept.music.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Injects the Ceecept DSP chain (16-band EQ -> multiband dynamics + limiter ->
 * 3D spatializer) into ExoPlayer's audio path with float precision end to end.
 */
class CeeceptRenderersFactory(
    context: Context,
    private val engine: AudioEngine
) : DefaultRenderersFactory(context) {

    init {
        setEnableAudioFloatOutput(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(
                arrayOf(engine.eq, engine.dynamics, engine.spatial)
            )
            .build()
    }
}
