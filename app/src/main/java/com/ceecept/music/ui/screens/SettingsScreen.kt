package com.ceecept.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ceecept.music.BuildConfig
import com.ceecept.music.CeeceptApp
import com.ceecept.music.ThemeMode
import com.ceecept.music.audio.DynamicsParams
import com.ceecept.music.audio.EqualizerProcessor
import com.ceecept.music.audio.SpaceParams
import com.ceecept.music.ui.components.SectionHeader
import com.ceecept.music.ui.theme.CeeceptColors
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(app: CeeceptApp) {
    val themeMode by app.uiPrefs.themeMode.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SectionHeader("Appearance")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                ThemeRow(
                    mode = ThemeMode.SYSTEM,
                    selected = themeMode,
                    icon = Icons.Filled.SettingsBrightness,
                    label = "System",
                    onSelect = { app.uiPrefs.setThemeMode(it) }
                )
                ThemeRow(
                    mode = ThemeMode.DARK,
                    selected = themeMode,
                    icon = Icons.Filled.DarkMode,
                    label = "Dark",
                    onSelect = { app.uiPrefs.setThemeMode(it) }
                )
                ThemeRow(
                    mode = ThemeMode.LIGHT,
                    selected = themeMode,
                    icon = Icons.Filled.LightMode,
                    label = "Light",
                    onSelect = { app.uiPrefs.setThemeMode(it) }
                )
            }
        }

        SectionHeader("Signal path")
        SignalPathCard(app)

        SectionHeader("Playback")
        PlaybackFeatureCard(app)

        SectionHeader("Visualizer")
        ActionRow(
            icon = Icons.Filled.AutoAwesome,
            title = "MilkDrop preset library",
            subtitle = "${app.visualizerRepository.presets.size} uploaded-app presets imported for the adaptive background visualizer"
        )

        SectionHeader("Library scanner")
        ActionRow(
            icon = Icons.Filled.Update,
            title = "Full rescan",
            subtitle = "Refresh songs, folders, albums, genres, composers, years, artwork and local lyrics cache",
            onClick = {
                app.lyricsRepository.clear()
                app.repository.refresh()
            }
        )

        SectionHeader("Studio")
        ActionRow(
            icon = Icons.Filled.RestartAlt,
            title = "Reset Studio",
            subtitle = "Restore default EQ, dynamics and 3D settings",
            onClick = {
                app.engine.applyPowerampPreset("Poweramp Balanced")
                app.engine.applyEqPreset("Flat")
                app.engine.applyDynamicsPreset("Transparent")
                app.engine.applySpacePreset("Wide Stage")
            }
        )

        SectionHeader("About")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CeeceptColors.accentGradient()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "C",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = "Ceecept", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                InfoLine(
                    icon = Icons.Filled.WifiOff,
                    text = "Offline-first. Your music and settings never leave this phone."
                )
                Spacer(Modifier.height(8.dp))
                InfoLine(
                    icon = Icons.Filled.Info,
                    text = "Plays MP3, WAV, FLAC, OGG, Opus, M4A/AAC and more — rendered through a 16-band EQ, multiband dynamics and HRTF 3D spatializer."
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlaybackFeatureCard(app: CeeceptApp) {
    var keepNotification by remember { mutableStateOf(app.history.keepNotification) }
    var resumeHeadset by remember { mutableStateOf(app.history.resumeOnHeadset) }
    var gapless by remember { mutableStateOf(app.history.gaplessPreload) }
    var crossfade by remember { mutableStateOf(app.history.crossfadeEnabled) }
    var crossfadeSeconds by remember { mutableStateOf(app.history.crossfadeSeconds) }
    val sleepEnd by app.playerConnection.sleepTimerEndMs.collectAsStateWithLifecycle()
    val remaining = remember(sleepEnd) {
        val left = sleepEnd - System.currentTimeMillis()
        if (left > 0) "Active · ${((left + 59_999L) / 60_000L).coerceAtLeast(1L)} min left" else "Off"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            ToggleRow(
                icon = Icons.Filled.Notifications,
                title = "Keep notification",
                subtitle = "Helps EMUI/Huawei keep background playback alive while paused or screen-off",
                checked = keepNotification,
                onChange = {
                    keepNotification = it
                    app.history.keepNotification = it
                }
            )
            ToggleRow(
                icon = Icons.Filled.Headphones,
                title = "Resume on headset",
                subtitle = "Remember the uploaded app's headset behavior: reconnect/play button can resume the saved session",
                checked = resumeHeadset,
                onChange = {
                    resumeHeadset = it
                    app.history.resumeOnHeadset = it
                }
            )
            ToggleRow(
                icon = Icons.Filled.Info,
                title = "Preload gapless tracks",
                subtitle = "Keep ExoPlayer ready for smoother album/queue transitions",
                checked = gapless,
                onChange = {
                    gapless = it
                    app.history.gaplessPreload = it
                }
            )
            ToggleRow(
                icon = Icons.Filled.AutoAwesome,
                title = "Crossfade / DJ deck overlap",
                subtitle = "Uses a second prepared deck with equal-power fade so AutoMix transitions are audibly seamless",
                checked = crossfade,
                onChange = {
                    crossfade = it
                    app.history.crossfadeEnabled = it
                }
            )
            ActionRowInline(
                icon = Icons.Filled.AutoAwesome,
                title = "Fade length",
                subtitle = "$crossfadeSeconds sec overlap · DJ mode stretches this to the nearest phrase when analysis is available",
                actions = listOf(
                    "6" to {
                        crossfadeSeconds = 6
                        app.history.crossfadeSeconds = 6
                    },
                    "10" to {
                        crossfadeSeconds = 10
                        app.history.crossfadeSeconds = 10
                    },
                    "14" to {
                        crossfadeSeconds = 14
                        app.history.crossfadeSeconds = 14
                    },
                    "20" to {
                        crossfadeSeconds = 20
                        app.history.crossfadeSeconds = 20
                    }
                )
            )
            ActionRowInline(
                icon = Icons.Filled.Timer,
                title = "Sleep timer",
                subtitle = remaining,
                actions = listOf(
                    "15" to { app.playerConnection.setSleepTimer(15) },
                    "30" to { app.playerConnection.setSleepTimer(30) },
                    "60" to { app.playerConnection.setSleepTimer(60) },
                    "Off" to { app.playerConnection.clearSleepTimer() }
                )
            )
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRowInline(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actions: List<Pair<String, () -> Unit>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (label, action) ->
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable { action() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(
    mode: ThemeMode,
    selected: ThemeMode,
    icon: ImageVector,
    label: String,
    onSelect: (ThemeMode) -> Unit
) {
    val isSelected = mode == selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .clickable { onSelect(mode) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun SignalPathCard(app: CeeceptApp) {
    var sampleRate by remember { mutableStateOf(0) }
    var channels by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            sampleRate = app.engine.eq.lastSampleRate
            channels = app.engine.eq.lastChannelCount
            delay(1000)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SignalLine(
                "Source",
                if (sampleRate > 0) "$sampleRate Hz · $channels ch" else "Play something to inspect"
            )
            SignalLine("Precision", "32-bit float end-to-end")
            SignalLine("Chain", "EQ/AutoEQ → Tone/DVC → Dynamics → Immerse 3D → DJ")
        }
    }
}

@Composable
private fun SignalLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
