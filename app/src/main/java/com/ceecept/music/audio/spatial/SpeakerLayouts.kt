package com.ceecept.music.audio.spatial

/** One rendering target: a real or virtual loudspeaker (§2.2). */
data class Speaker(
    val name: String,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val distanceM: Float,
    val isLfe: Boolean = false
)

/**
 * A speaker configuration from §2.2. [isVirtual] marks layouts that are rendered
 * through HRTFs rather than physical drivers.
 */
data class SpeakerLayout(
    val id: String,
    val label: String,
    val speakers: List<Speaker>,
    val hasHeightSpeakers: Boolean,
    val isVirtual: Boolean
) {
    val totalSpeakers: Int get() = speakers.size

    /** Index of the first speaker whose name matches, or -1. */
    fun indexOf(name: String): Int = speakers.indexOfFirst { it.name == name }
}

/** SPEAKER_CONFIGURATIONS (§2.2), transcribed verbatim from the guidelines. */
object SpeakerLayouts {

    val STEREO = SpeakerLayout(
        id = "stereo",
        label = "Stereo",
        speakers = listOf(
            Speaker("L", -90f, 0f, 2.0f),
            Speaker("R", +90f, 0f, 2.0f)
        ),
        hasHeightSpeakers = false,
        isVirtual = false
    )

    val SURROUND_5_1 = SpeakerLayout(
        id = "5.1_surround",
        label = "5.1 Surround",
        speakers = listOf(
            Speaker("FL", -30f, 0f, 2.0f),
            Speaker("FC", 0f, 0f, 2.0f),
            Speaker("FR", +30f, 0f, 2.0f),
            Speaker("SL", -110f, 0f, 2.5f),
            Speaker("SR", +110f, 0f, 2.5f),
            Speaker("LFE", 0f, -45f, 1.5f, isLfe = true)
        ),
        hasHeightSpeakers = false,
        isVirtual = false
    )

    val IMMERSIVE_7_1_4 = SpeakerLayout(
        id = "7.1.4_immersive",
        label = "7.1.4 Immersive",
        speakers = listOf(
            // Horizontal layer (7.1).
            Speaker("FL", -30f, 0f, 2.0f),
            Speaker("FC", 0f, 0f, 2.0f),
            Speaker("FR", +30f, 0f, 2.0f),
            Speaker("SL", -110f, 0f, 2.5f),
            Speaker("SR", +110f, 0f, 2.5f),
            Speaker("SBL", -150f, 0f, 2.5f),
            Speaker("SBR", +150f, 0f, 2.5f),
            Speaker("LFE", 0f, -45f, 1.5f, isLfe = true),
            // Height layer (4 overhead).
            Speaker("HFL", -45f, +60f, 1.8f),
            Speaker("HFR", +45f, +60f, 1.8f),
            Speaker("HBL", -135f, +60f, 1.8f),
            Speaker("HBR", +135f, +60f, 1.8f)
        ),
        hasHeightSpeakers = true,
        isVirtual = false
    )

    val HEADPHONES_BINAURAL = SpeakerLayout(
        id = "headphones_binaural",
        label = "Headphones (binaural)",
        speakers = listOf(
            Speaker("L", -90f, 0f, 0.1f),
            Speaker("R", +90f, 0f, 0.1f)
        ),
        hasHeightSpeakers = false,
        isVirtual = true
    )

    val ALL = listOf(STEREO, SURROUND_5_1, IMMERSIVE_7_1_4, HEADPHONES_BINAURAL)

    fun byId(id: String): SpeakerLayout? = ALL.firstOrNull { it.id == id }
}

/** Result of §11.2 `detect_playback_capabilities()`, filled in from the Android audio router. */
data class PlaybackCapabilities(
    val sampleRate: Int = 48000,
    val numChannels: Int = 2,
    val speakerCount: Int = 2,
    val hasSubwoofer: Boolean = false,
    val hasHeightSpeakers: Boolean = false,
    val isHeadphones: Boolean = false,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors()
)

/**
 * §11.2 rendering strategy. [virtualLayout] is the internal layout objects are panned
 * onto; [binauralize] says whether those speaker feeds are then folded to two channels
 * through the HRTF virtualiser (headphones) or amplitude-downmixed (loudspeakers).
 */
data class RenderStrategy(
    val name: String,
    val virtualLayout: SpeakerLayout,
    val quality: Float,
    val cpuOverhead: Float,
    val binauralize: Boolean
) {
    companion object {
        /**
         * Graceful degradation 3D → 5.1 → stereo, exactly the ordering in §11.2.
         *
         * Ceecept always renders to two physical channels (the phone's output), so the
         * "full 3D" strategies are rendered onto a *virtual* 7.1.4 / 5.1 rig which is
         * then binauralised. That keeps HRTF cost constant (one filter pair per virtual
         * speaker) instead of growing with the object count.
         */
        fun select(caps: PlaybackCapabilities): RenderStrategy = when {
            caps.isHeadphones -> RenderStrategy(
                name = "full_3d_7_1_4",
                virtualLayout = SpeakerLayouts.IMMERSIVE_7_1_4,
                quality = 1.0f,
                cpuOverhead = 1.0f,
                binauralize = true
            )

            caps.hasHeightSpeakers && caps.speakerCount >= 8 -> RenderStrategy(
                name = "full_3d_7_1_4",
                virtualLayout = SpeakerLayouts.IMMERSIVE_7_1_4,
                quality = 1.0f,
                cpuOverhead = 1.0f,
                binauralize = false
            )

            caps.speakerCount >= 6 -> RenderStrategy(
                name = "surround_5_1",
                virtualLayout = SpeakerLayouts.SURROUND_5_1,
                quality = 0.8f,
                cpuOverhead = 0.6f,
                binauralize = false
            )

            else -> RenderStrategy(
                name = "stereo_enhanced",
                virtualLayout = SpeakerLayouts.STEREO,
                quality = 0.5f,
                cpuOverhead = 0.3f,
                binauralize = false
            )
        }
    }
}
