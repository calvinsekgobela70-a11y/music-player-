package com.ceecept.music

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ceecept.music.audio.AudioEngine
import com.ceecept.music.data.MusicRepository
import com.ceecept.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.uiDataStore by preferencesDataStore("ceecept_ui")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Tiny UI-preferences store (theme). Audio prefs live in [AudioEngine]. */
class UiPrefs(private val context: Context, scope: CoroutineScope) {
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        scope.launch {
            val name = context.uiDataStore.data.first()[Keys.THEME] ?: ThemeMode.SYSTEM.name
            _themeMode.value = runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            context.uiDataStore.edit { it[Keys.THEME] = mode.name }
        }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
    }
}

class CeeceptApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: AudioEngine by lazy { AudioEngine(this, applicationScope) }
    val repository: MusicRepository by lazy { MusicRepository(this, applicationScope) }
    val playerConnection: PlayerConnection by lazy {
        PlayerConnection(this, repository, applicationScope)
    }
    val uiPrefs: UiPrefs by lazy { UiPrefs(this, applicationScope) }
}

fun Context.ceeceptApp(): CeeceptApp = applicationContext as CeeceptApp
