package com.ceecept.music.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ceecept.music.audio.spatial.OutputRouteDetector
import com.ceecept.music.audio.spatial.PlaybackCapabilities
import com.ceecept.music.audio.spatial.RenderStrategy
import com.ceecept.music.audio.spatial.SpeakerLayouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.ceeceptDataStore: DataStore<Preferences> by preferencesDataStore("ceecept_audio")

/**
 * Application-scoped owner of the DSP chain + all of its observable state.
 * The UI reads StateFlows and calls setters; setters push lock-free updates to
 * the audio processors and debounce-persist to DataStore.
 */
class AudioEngine(
    private val context: Context,
    private val appScope: CoroutineScope
) {
    val eq = EqualizerProcessor()
    val dynamics = DynamicsProcessor()
    val spatial = SpatializerProcessor()

    // ---- Equalizer state ----
    private val _eqGains = MutableStateFlow(FloatArray(EqualizerProcessor.BANDS))
    val eqGains: StateFlow<FloatArray> = _eqGains.asStateFlow()

    private val _eqPreamp = MutableStateFlow(0f)
    val eqPreamp: StateFlow<Float> = _eqPreamp.asStateFlow()

    private val _eqEnabled = MutableStateFlow(true)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqPreset = MutableStateFlow("Flat")
    val eqPreset: StateFlow<String> = _eqPreset.asStateFlow()

    private val _eqMusicalQ = MutableStateFlow(true)
    val eqMusicalQ: StateFlow<Boolean> = _eqMusicalQ.asStateFlow()

    private val _eqAutoGain = MutableStateFlow(true)
    val eqAutoGain: StateFlow<Boolean> = _eqAutoGain.asStateFlow()

    private val _eqSubsonic = MutableStateFlow(true)
    val eqSubsonic: StateFlow<Boolean> = _eqSubsonic.asStateFlow()

    // ---- Dynamics state ----
    private val _dynamicsParams = MutableStateFlow(DynamicsParams.DEFAULT)
    val dynamicsParams: StateFlow<DynamicsParams> = _dynamicsParams.asStateFlow()

    private val _dynamicsPreset = MutableStateFlow("Transparent")
    val dynamicsPreset: StateFlow<String> = _dynamicsPreset.asStateFlow()

    // ---- Spatial state ----
    private val _spaceParams = MutableStateFlow(SpaceParams.DEFAULT)
    val spaceParams: StateFlow<SpaceParams> = _spaceParams.asStateFlow()

    private val _spacePreset = MutableStateFlow("Wide Stage")
    val spacePreset: StateFlow<String> = _spacePreset.asStateFlow()

    // ---- Output routing (guidelines 11.2) ----
    private val routeDetector = OutputRouteDetector(context)

    private val _capabilities = MutableStateFlow(PlaybackCapabilities())
    val capabilities: StateFlow<PlaybackCapabilities> = _capabilities.asStateFlow()

    private val _renderStrategy = MutableStateFlow(RenderStrategy.select(PlaybackCapabilities()))
    val renderStrategy: StateFlow<RenderStrategy> = _renderStrategy.asStateFlow()

    private var saveJob: Job? = null

    init {
        appScope.launch { runCatching { restore() } }
        runCatching {
            routeDetector.observe { caps ->
                _capabilities.value = caps
                applyStrategy(caps)
            }
        }
    }

    /** Re-run capability detection on demand (e.g. when the Studio screen opens). */
    fun refreshOutputRoute() {
        runCatching {
            val caps = routeDetector.detect()
            _capabilities.value = caps
            applyStrategy(caps)
        }
    }

    /**
     * Resolve the virtual speaker rig. Normally this follows the output route (§11.2),
     * but the user can pin it: "7.1.4" keeps the full twelve-speaker arrangement around
     * the head even on earbuds, which is what the binaural renderer is happiest with.
     */
    private fun applyStrategy(caps: PlaybackCapabilities) {
        val auto = RenderStrategy.select(caps)
        val strategy = when (_spaceParams.value.rigMode) {
            1 -> auto.copy(virtualLayout = SpeakerLayouts.STEREO)
            2 -> auto.copy(virtualLayout = SpeakerLayouts.SURROUND_5_1)
            3 -> auto.copy(virtualLayout = SpeakerLayouts.IMMERSIVE_7_1_4)
            else -> auto
        }
        _renderStrategy.value = strategy
        spatial.strategy.set(strategy)
    }

    // ---------- Equalizer ----------

    fun setEqBand(index: Int, gainDb: Float) {
        val copy = _eqGains.value.copyOf()
        copy[index.coerceIn(0, EqualizerProcessor.BANDS - 1)] =
            gainDb.coerceIn(-EqualizerProcessor.MAX_GAIN_DB, EqualizerProcessor.MAX_GAIN_DB)
        _eqGains.value = copy
        _eqPreset.value = "Custom"
        eq.setBandGain(index, gainDb)
        scheduleSave()
    }

    fun setEqPreamp(db: Float) {
        _eqPreamp.value = db.coerceIn(-12f, 12f)
        eq.preampDb = _eqPreamp.value
        scheduleSave()
    }

    fun setEqEnabled(on: Boolean) {
        _eqEnabled.value = on
        eq.enabled = on
        scheduleSave()
    }

    fun setEqMusicalQ(on: Boolean) {
        _eqMusicalQ.value = on
        eq.musicalQ = on
        scheduleSave()
    }

    fun setEqAutoGain(on: Boolean) {
        _eqAutoGain.value = on
        eq.autoGain = on
        // Force a coefficient rebuild so autoGainDb updates immediately.
        eq.setAll(_eqGains.value, _eqPreamp.value)
        scheduleSave()
    }

    fun setEqSubsonic(on: Boolean) {
        _eqSubsonic.value = on
        eq.subsonicFilter = on
        scheduleSave()
    }

    fun applyEqPreset(name: String) {
        val preset = EqualizerProcessor.PRESETS[name] ?: return
        _eqGains.value = preset.copyOf()
        _eqPreset.value = name
        eq.setAll(preset, _eqPreamp.value)
        scheduleSave()
    }

    // ---------- Dynamics ----------

    fun updateDynamics(params: DynamicsParams, presetName: String = "Custom") {
        _dynamicsParams.value = params
        _dynamicsPreset.value = presetName
        dynamics.params.set(params)
        scheduleSave()
    }

    fun applyDynamicsPreset(name: String) {
        val preset = DynamicsParams.PRESETS[name] ?: return
        updateDynamics(preset, name)
    }

    // ---------- Spatial ----------

    fun updateSpace(params: SpaceParams, presetName: String = "Custom") {
        val previous = _spaceParams.value
        _spaceParams.value = params
        _spacePreset.value = presetName
        spatial.params.set(params)
        if (previous.rigMode != params.rigMode) applyStrategy(_capabilities.value)
        scheduleSave()
    }

    fun applySpacePreset(name: String) {
        val preset = SpaceParams.PRESETS[name] ?: return
        updateSpace(preset, name)
    }

    // ---------- Persistence ----------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = appScope.launch {
            delay(400)
            save()
        }
    }

    private suspend fun save() {
        val eqCsv = _eqGains.value.joinToString(",")
        val d = _dynamicsParams.value
        val s = _spaceParams.value
        val dynList = mutableListOf<Float>()
        dynList.add(if (d.enabled) 1f else 0f)
        dynList.add(d.xoverLowHz)
        dynList.add(d.xoverHighHz)
        dynList.addAll(bandToList(d.low))
        dynList.addAll(bandToList(d.mid))
        dynList.addAll(bandToList(d.high))
        dynList.add(if (d.limiterOn) 1f else 0f)
        dynList.add(d.limiterCeilingDb)
        dynList.add(d.limiterReleaseMs)
        dynList.add(d.outputDb)
        context.ceeceptDataStore.edit { p ->
            p[Keys.EQ_GAINS] = eqCsv
            p[Keys.EQ_PREAMP] = _eqPreamp.value
            p[Keys.EQ_ENABLED] = _eqEnabled.value
            p[Keys.EQ_PRESET] = _eqPreset.value
            p[Keys.EQ_MUSICAL_Q] = _eqMusicalQ.value
            p[Keys.EQ_AUTO_GAIN] = _eqAutoGain.value
            p[Keys.EQ_SUBSONIC] = _eqSubsonic.value
            p[Keys.DYN] = dynList.joinToString(",")
            p[Keys.DYN_PRESET] = _dynamicsPreset.value
            p[Keys.SPACE] = listOf(
                if (s.enabled) 1f else 0f, s.strength, s.azimuth, s.elevation,
                s.distance, s.width, s.roomSize, s.reverb, s.damping,
                s.height, if (s.multiband) 1f else 0f, s.orbitHz,
                if (s.stems) 1f else 0f, s.vocal, s.bass, s.punch, s.ambience,
                s.rigMode.toFloat(), s.imaging, s.warmth, s.padLevel
            ).joinToString(",")
            p[Keys.SPACE_PRESET] = _spacePreset.value
        }
    }

    private fun bandToList(b: BandParams): List<Float> = listOf(
        if (b.gateOn) 1f else 0f, b.gateDb, b.thresholdDb, b.ratio, b.attackMs, b.releaseMs,
        b.makeupDb, b.kneeDb, b.rmsBlend, if (b.autoMakeup) 1f else 0f,
        if (b.programRelease) 1f else 0f
    )

    private suspend fun restore() {
        val p = context.ceeceptDataStore.data.first()
        // EQ
        p[Keys.EQ_GAINS]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.let { list ->
            if (list.size == EqualizerProcessor.BANDS) {
                _eqGains.value = list.toFloatArray()
            }
        }
        _eqPreamp.value = p[Keys.EQ_PREAMP] ?: 0f
        _eqEnabled.value = p[Keys.EQ_ENABLED] ?: true
        _eqPreset.value = p[Keys.EQ_PRESET] ?: "Flat"
        _eqMusicalQ.value = p[Keys.EQ_MUSICAL_Q] ?: true
        _eqAutoGain.value = p[Keys.EQ_AUTO_GAIN] ?: true
        _eqSubsonic.value = p[Keys.EQ_SUBSONIC] ?: true
        eq.musicalQ = _eqMusicalQ.value
        eq.autoGain = _eqAutoGain.value
        eq.subsonicFilter = _eqSubsonic.value
        eq.setAll(_eqGains.value, _eqPreamp.value)
        eq.enabled = _eqEnabled.value
        // Dynamics
        p[Keys.DYN]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.let { list ->
            val oldBand = 7
            val newBand = 11
            val bandSize = when {
                list.size == 3 + oldBand * 3 + 4 -> oldBand
                list.size == 3 + newBand * 3 + 4 -> newBand
                else -> 0
            }
            if (bandSize > 0) {
                fun bandAt(o: Int) = BandParams(
                    gateOn = list[o] > 0.5f, gateDb = list[o + 1], thresholdDb = list[o + 2],
                    ratio = list[o + 3], attackMs = list[o + 4], releaseMs = list[o + 5],
                    makeupDb = list[o + 6],
                    kneeDb = if (bandSize > 7) list[o + 7] else 8f,
                    rmsBlend = if (bandSize > 8) list[o + 8] else 0.5f,
                    autoMakeup = if (bandSize > 9) list[o + 9] > 0.5f else true,
                    programRelease = if (bandSize > 10) list[o + 10] > 0.5f else true
                )
                val tail = 3 + bandSize * 3
                val d = DynamicsParams(
                    enabled = list[0] > 0.5f, xoverLowHz = list[1], xoverHighHz = list[2],
                    low = bandAt(3), mid = bandAt(3 + bandSize), high = bandAt(3 + bandSize * 2),
                    limiterOn = list[tail] > 0.5f, limiterCeilingDb = list[tail + 1],
                    limiterReleaseMs = list[tail + 2], outputDb = list[tail + 3]
                )
                _dynamicsParams.value = d
            }
        }
        _dynamicsPreset.value = p[Keys.DYN_PRESET] ?: "Transparent"
        dynamics.params.set(_dynamicsParams.value)
        // Space
        p[Keys.SPACE]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.let { list ->
            // v1.0.4 stored 9 values; the object-based renderer adds height, the
            // per-band width switch and the orbit rate. Both layouts are accepted.
            if (list.size >= 9) {
                _spaceParams.value = SpaceParams(
                    enabled = list[0] > 0.5f, strength = list[1], azimuth = list[2],
                    elevation = list[3], distance = list[4], width = list[5],
                    roomSize = list[6], reverb = list[7], damping = list[8],
                    height = if (list.size > 9) list[9] else 1f,
                    multiband = if (list.size > 10) list[10] > 0.5f else true,
                    orbitHz = if (list.size > 11) list[11] else 0f,
                    stems = if (list.size > 12) list[12] > 0.5f else true,
                    vocal = if (list.size > 13) list[13] else 0.45f,
                    bass = if (list.size > 14) list[14] else 0.45f,
                    punch = if (list.size > 15) list[15] else 0.35f,
                    ambience = if (list.size > 16) list[16] else 0.5f,
                    rigMode = if (list.size > 17) list[17].toInt() else 0,
                    imaging = if (list.size > 18) list[18] else 0.5f,
                    warmth = if (list.size > 19) list[19] else 0.35f,
                    padLevel = if (list.size > 20) list[20] else 0.6f
                )
            }
        }
        _spacePreset.value = p[Keys.SPACE_PRESET] ?: "Wide Stage"
        spatial.params.set(_spaceParams.value)
    }

    private object Keys {
        val EQ_GAINS = stringPreferencesKey("eq_gains")
        val EQ_PREAMP = floatPreferencesKey("eq_preamp")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val EQ_MUSICAL_Q = booleanPreferencesKey("eq_musical_q")
        val EQ_AUTO_GAIN = booleanPreferencesKey("eq_auto_gain")
        val EQ_SUBSONIC = booleanPreferencesKey("eq_subsonic")
        val DYN = stringPreferencesKey("dyn")
        val DYN_PRESET = stringPreferencesKey("dyn_preset")
        val SPACE = stringPreferencesKey("space")
        val SPACE_PRESET = stringPreferencesKey("space_preset")
    }
}
