package com.ceecept.music.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ceecept.music.CeeceptApp
import com.ceecept.music.CrashReporter
import com.ceecept.music.ui.screens.LibraryScreen
import com.ceecept.music.ui.screens.MiniPlayer
import com.ceecept.music.ui.screens.NowPlayingScreen
import com.ceecept.music.ui.screens.SettingsScreen
import com.ceecept.music.ui.screens.StudioScreen
import com.ceecept.music.ui.theme.CeeceptMotion
import com.ceecept.music.ui.theme.CeeceptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Library", Icons.Filled.LibraryMusic),
    Tab("Studio", Icons.Filled.GraphicEq),
    Tab("Settings", Icons.Filled.Settings)
)

/**
 * App root: theme + tab scaffold with seamless spring transitions,
 * mini player docked above the tab bar, full-screen now-playing overlay,
 * and a crash-report dialog when a previous run recorded a failure.
 */
@Composable
fun CeeceptRoot(app: CeeceptApp, useCustomFont: Boolean = true) {
    val themeMode by app.uiPrefs.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var crashReport by remember { mutableStateOf<String?>(null) }

    // Warm up playback connection + audio engine eagerly.
    LaunchedEffect(Unit) {
        app.playerConnection
        app.engine
        crashReport = withContext(Dispatchers.IO) { CrashReporter.load(app) }
        // Re-check after async startup (e.g. playback service) has run.
        delay(4000)
        if (crashReport == null) {
            crashReport = withContext(Dispatchers.IO) { CrashReporter.load(app) }
        }
    }

    CeeceptTheme(mode = themeMode, useCustomFont = useCustomFont) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showNowPlaying by rememberSaveable { mutableStateOf(false) }

        BackHandler(enabled = showNowPlaying) {
            showNowPlaying = false
        }

        crashReport?.let { report ->
            CrashReportDialog(
                report = report,
                onDismiss = {
                    CrashReporter.clear(context)
                    crashReport = null
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        TABS.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.icon, contentDescription = item.title) },
                                label = { Text(item.title) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally(
                                    initialOffsetX = { it / 4 },
                                    animationSpec = CeeceptMotion.screen()
                                ) + fadeIn()) togetherWith
                                    (slideOutHorizontally(
                                        targetOffsetX = { -it / 4 },
                                        animationSpec = CeeceptMotion.screen()
                                    ) + fadeOut())
                            } else {
                                (slideInHorizontally(
                                    initialOffsetX = { -it / 4 },
                                    animationSpec = CeeceptMotion.screen()
                                ) + fadeIn()) togetherWith
                                    (slideOutHorizontally(
                                        targetOffsetX = { it / 4 },
                                        animationSpec = CeeceptMotion.screen()
                                    ) + fadeOut())
                            }
                        },
                        label = "tabs"
                    ) { current ->
                        when (current) {
                            0 -> LibraryScreen(app)
                            1 -> StudioScreen(app)
                            else -> SettingsScreen(app)
                        }
                    }
                    AnimatedVisibility(
                        visible = !showNowPlaying,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        MiniPlayer(app = app, onExpand = { showNowPlaying = true })
                    }
                }
            }

            AnimatedVisibility(
                visible = showNowPlaying,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = CeeceptMotion.screen()
                ) + fadeIn(),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = CeeceptMotion.screen()
                ) + fadeOut()
            ) {
                NowPlayingScreen(
                    app = app,
                    onClose = { showNowPlaying = false },
                    onOpenStudio = {
                        showNowPlaying = false
                        tab = 1
                    }
                )
            }
        }
    }
}

@Composable
private fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Something went wrong") },
        text = {
            Column {
                Text(
                    "Ceecept hit a problem during startup. Tap Copy report and send it to the developer so it can be fixed:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Ceecept report", report))
                Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text("Copy report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}
