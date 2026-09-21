package com.ceecept.music.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    private var saveJob: Job? = null

    init {
        appScope.launch { restore() }
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
        _spaceParams.value = params
        _spacePreset.value = presetName
        spatial.params.set(params)
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
            p[Keys.DYN] = dynList.joinToString(",")
            p[Keys.DYN_PRESET] = _dynamicsPreset.value
            p[Keys.SPACE] = listOf(
                if (s.enabled) 1f else 0f, s.strength, s.azimuth, s.elevation,
                s.distance, s.width, s.roomSize, s.reverb, s.damping
            ).joinToString(",")
            p[Keys.SPACE_PRESET] = _spacePreset.value
        }
    }

    private fun bandToList(b: BandParams): List<Float> = listOf(
        if (b.gateOn) 1f else 0f, b.gateDb, b.thresholdDb, b.ratio, b.attackMs, b.releaseMs, b.makeupDb
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
        eq.setAll(_eqGains.value, _eqPreamp.value)
        eq.enabled = _eqEnabled.value
        // Dynamics
        p[Keys.DYN]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.let { list ->
            if (list.size == 3 + 7 * 3 + 4) {
                fun bandAt(o: Int) = BandParams(
                    gateOn = list[o] > 0.5f, gateDb = list[o + 1], thresholdDb = list[o + 2],
                    ratio = list[o + 3], attackMs = list[o + 4], releaseMs = list[o + 5],
                    makeupDb = list[o + 6]
                )
                val d = DynamicsParams(
                    enabled = list[0] > 0.5f, xoverLowHz = list[1], xoverHighHz = list[2],
                    low = bandAt(3), mid = bandAt(10), high = bandAt(17),
                    limiterOn = list[24] > 0.5f, limiterCeilingDb = list[25],
                    limiterReleaseMs = list[26], outputDb = list[27]
                )
                _dynamicsParams.value = d
            }
        }
        _dynamicsPreset.value = p[Keys.DYN_PRESET] ?: "Transparent"
        dynamics.params.set(_dynamicsParams.value)
        // Space
        p[Keys.SPACE]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.let { list ->
            if (list.size == 9) {
                _spaceParams.value = SpaceParams(
                    enabled = list[0] > 0.5f, strength = list[1], azimuth = list[2],
                    elevation = list[3], distance = list[4], width = list[5],
                    roomSize = list[6], reverb = list[7], damping = list[8]
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
        val DYN = stringPreferencesKey("dyn")
        val DYN_PRESET = stringPreferencesKey("dyn_preset")
        val SPACE = stringPreferencesKey("space")
        val SPACE_PRESET = stringPreferencesKey("space_preset")
    }
}
