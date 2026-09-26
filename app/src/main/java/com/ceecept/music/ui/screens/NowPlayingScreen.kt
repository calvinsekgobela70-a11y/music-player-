package com.ceecept.music.ui.screens

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ceecept.music.CeeceptApp
import com.ceecept.music.ui.components.ArtworkView
import com.ceecept.music.ui.components.BouncyIconButton
import com.ceecept.music.ui.components.CeeceptSlider
import com.ceecept.music.ui.components.PlayPauseButton
import com.ceecept.music.ui.components.RepeatButton
import com.ceecept.music.ui.components.ShuffleButton
import com.ceecept.music.ui.components.SkipButton
import com.ceecept.music.ui.components.formatDuration
import com.ceecept.music.ui.theme.CeeceptColors
import com.ceecept.music.ui.theme.CeeceptMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Full-screen now-playing overlay. Swipe down (or tap chevron) to dismiss. */
@Composable
fun NowPlayingScreen(
    app: CeeceptApp,
    onClose: () -> Unit,
    onOpenStudio: () -> Unit
) {
    val connection = app.playerConnection
    val track by connection.currentTrack.collectAsStateWithLifecycle()
    val externalTitle by connection.externalTitle.collectAsStateWithLifecycle()
    val isPlaying by connection.isPlaying.collectAsStateWithLifecycle()
    val positionMs by connection.positionMs.collectAsStateWithLifecycle()
    val durationMs by connection.durationMs.collectAsStateWithLifecycle()
    val shuffleOn by connection.shuffleOn.collectAsStateWithLifecycle()
    val repeatMode by connection.repeatMode.collectAsStateWithLifecycle()

    val title = track?.title ?: externalTitle ?: "Nothing playing"
    val subtitle = track?.let { "${it.artist} · ${it.album}" } ?: "Pick a song from your library"

    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .draggable(
                state = rememberDraggableState { delta ->
                    scope.launch {
                        dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f))
                    }
                },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    scope.launch {
                        if (dragOffset.value > 220f) {
                            onClose()
                            dragOffset.snapTo(0f)
                        } else {
                            dragOffset.animateTo(0f, CeeceptMotion.bouncy())
                        }
                    }
                }
            )
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Blurred artwork backdrop.
        var backdropModifier = Modifier
            .fillMaxSize()
            .alpha(0.55f)
        if (Build.VERSION.SDK_INT >= 31) {
            backdropModifier = backdropModifier.blur(90.dp)
        }
        ArtworkView(
            track = track,
            repository = app.repository,
            modifier = backdropModifier,
            cornerRadius = 0.dp,
            thumbSize = 256
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                BouncyIconButton(
                    onClick = onClose,
                    contentDescription = "Close",
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(28.dp)
                    )
                }
                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
                BouncyIconButton(
                    onClick = onOpenStudio,
                    contentDescription = "Open Studio",
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = CeeceptColors.Accent,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            ArtworkView(
                track = track,
                repository = app.repository,
                // fill = true: the art (or its gradient placeholder) always
                // occupies the free space, so the layout never collapses.
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                cornerRadius = 24.dp,
                thumbSize = 1024
            )
            Spacer(Modifier.height(28.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(20.dp))

            var scrubMs by remember { mutableStateOf<Long?>(null) }
            val shownMs = scrubMs ?: positionMs
            CeeceptSlider(
                value = if (durationMs > 0) shownMs.toFloat() / durationMs else 0f,
                onValueChange = { frac ->
                    scrubMs = (frac * durationMs).toLong()
                },
                onValueChangeFinished = {
                    scrubMs?.let { connection.seekTo(it) }
                    scrubMs = null
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(shownMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShuffleButton(
                    active = shuffleOn,
                    onClick = { connection.toggleShuffle() }
                )
                SkipButton(forward = false, onClick = { connection.previous() })
                PlayPauseButton(
                    playing = isPlaying,
                    onClick = { connection.togglePlayPause() },
                    size = 78.dp
                )
                SkipButton(forward = true, onClick = { connection.next() })
                RepeatButton(mode = repeatMode, onClick = { connection.cycleRepeat() })
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Compact bar docked above the tab bar. Tap to expand. */
@Composable
fun MiniPlayer(app: CeeceptApp, onExpand: () -> Unit) {
    val connection = app.playerConnection
    val track by connection.currentTrack.collectAsStateWithLifecycle()
    val externalTitle by connection.externalTitle.collectAsStateWithLifecycle()
    val isPlaying by connection.isPlaying.collectAsStateWithLifecycle()
    val positionMs by connection.positionMs.collectAsStateWithLifecycle()
    val durationMs by connection.durationMs.collectAsStateWithLifecycle()

    if (track == null && externalTitle == null) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onExpand() }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkView(
                    track = track,
                    repository = app.repository,
                    modifier = Modifier.size(44.dp),
                    cornerRadius = 10.dp,
                    thumbSize = 256
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = track?.title ?: externalTitle ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track?.artist ?: "External file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BouncyIconButton(
                    onClick = { connection.togglePlayPause() },
                    contentDescription = if (isPlaying) "Pause" else "Play"
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(26.dp)
                    )
                }
                BouncyIconButton(
                    onClick = { connection.next() },
                    contentDescription = "Next"
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(26.dp)
                    )
                }
            }
            val progress = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = progress,
                color = CeeceptColors.Accent,
                trackColor = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
            )
        }
    }
}
