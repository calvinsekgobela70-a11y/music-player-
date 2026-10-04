package com.ceecept.music.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ceecept.music.CeeceptApp
import com.ceecept.music.data.LyricsResult
import com.ceecept.music.data.TrackBookmark
import com.ceecept.music.ui.components.ArtworkBackdrop
import com.ceecept.music.ui.components.ArtworkPalette
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
import com.ceecept.music.ui.theme.Glass
import com.ceecept.music.ui.theme.glass
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
    val djMode by connection.djMode.collectAsStateWithLifecycle()
    val historyRevision = app.history.revision.value

    val title = track?.title ?: externalTitle ?: "Nothing playing"
    val subtitle = track?.let { "${it.artist} · ${it.album}" } ?: "Pick a song from your library"

    var backdropBitmap by remember(track?.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track?.id) {
        backdropBitmap = track?.let { app.repository.artwork(it, 384) }
    }
    val palette = remember(backdropBitmap) { ArtworkPalette.from(backdropBitmap) }

    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var lyrics by remember(track?.id) { mutableStateOf<LyricsResult?>(null) }
    val liked = remember(track?.id, historyRevision) { track?.let { app.history.isLiked(it.id) } ?: false }
    val rating = remember(track?.id, historyRevision) { track?.let { app.history.rating(it.id) } ?: 0 }
    val bookmarks = remember(track?.id, historyRevision) { track?.let { app.history.bookmarksFor(it.id) } ?: emptyList() }
    LaunchedEffect(track?.id) {
        lyrics = track?.let { app.lyricsRepository.lyricsFor(it) }
    }

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
            .background(palette.dominant)
    ) {
        ArtworkBackdrop(
            palette = palette,
            animated = isPlaying,
            intensity = 1f,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(width = 86.dp, height = 7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.36f))
            )
            Spacer(Modifier.height(18.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                BouncyIconButton(
                    onClick = onClose,
                    contentDescription = "Close",
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier
                            .padding(6.dp)
                            .size(28.dp)
                    )
                }
                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f),
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

            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.Black.copy(alpha = 0.18f))
            ) {
                val bmp = backdropBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ArtworkView(
                        track = track,
                        repository = app.repository,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 32.dp,
                        thumbSize = 512
                    )
                }
            }
            Spacer(Modifier.height(26.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BouncyIconButton(
                    onClick = { track?.let { app.history.toggleLike(it.id) } },
                    contentDescription = if (liked) "Unlike" else "Like"
                ) {
                    Icon(
                        imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = if (liked) CeeceptColors.Accent else Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.padding(8.dp).size(32.dp)
                    )
                }
                BouncyIconButton(
                    onClick = { if (track != null) showPlaylistDialog = true },
                    contentDescription = "Add to playlist"
                ) {
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.padding(8.dp).size(30.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ImmerseChip(app = app, onClick = onOpenStudio)
                DjModeChip(active = djMode, onClick = { connection.setDjMode(!djMode) })
            }
            app.visualizerRepository.presetFor(track?.id ?: 0L, positionMs)?.let { preset ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "MilkDrop visualizer · $preset",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(16.dp), strength = 0.45f)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            TrackMemoryRow(
                rating = rating,
                bookmarks = bookmarks,
                positionMs = positionMs,
                onRate = { value -> track?.let { app.history.setRating(it.id, value) } },
                onBookmark = { track?.let { app.history.addBookmark(it.id, positionMs) } },
                onSeekBookmark = { connection.seekTo(it.positionMs) },
                onDeleteBookmark = { bookmark -> track?.let { app.history.removeBookmark(it.id, bookmark.positionMs) } }
            )
            lyrics?.let { result ->
                Spacer(Modifier.height(10.dp))
                LyricsCard(result = result, positionMs = positionMs)
            }
            Spacer(Modifier.height(16.dp))

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
                    color = Color.White.copy(alpha = 0.70f)
                )
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f)
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

        val current = track
        if (showPlaylistDialog && current != null) {
            AddToPlaylistDialog(
                app = app,
                trackId = current.id,
                onDismiss = { showPlaylistDialog = false }
            )
        }
    }
}

@Composable
private fun TrackMemoryRow(
    rating: Int,
    bookmarks: List<TrackBookmark>,
    positionMs: Long,
    onRate: (Int) -> Unit,
    onBookmark: () -> Unit,
    onSeekBookmark: (TrackBookmark) -> Unit,
    onDeleteBookmark: (TrackBookmark) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), strength = 0.7f)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.weight(1f)) {
                repeat(5) { i ->
                    val star = i + 1
                    Icon(
                        imageVector = if (star <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Rate $star",
                        tint = if (star <= rating) CeeceptColors.Accent else Color.White.copy(alpha = 0.50f),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onRate(if (rating == star) 0 else star) }
                    )
                }
            }
            BouncyIconButton(onClick = onBookmark, contentDescription = "Add bookmark") {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.88f),
                    modifier = Modifier.padding(6.dp).size(24.dp)
                )
            }
        }
        if (bookmarks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bookmarks.take(3).forEach { bookmark ->
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (kotlin.math.abs(bookmark.positionMs - positionMs) < 1500) 0.22f else 0.12f))
                            .clickable { onSeekBookmark(bookmark) }
                            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(bookmark.label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete bookmark",
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp)
                                .clickable { onDeleteBookmark(bookmark) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsCard(result: LyricsResult, positionMs: Long) {
    val activeIndex = remember(result, positionMs) {
        if (!result.timed) -1 else result.lines.indexOfLast { it.timeMs <= positionMs }
    }
    val displayLines = if (result.timed) {
        val start = (activeIndex - 1).coerceAtLeast(0)
        result.lines.drop(start).take(4).mapIndexed { i, line -> i + start to line.text }
    } else {
        result.plainText.lines().take(4).mapIndexed { i, line -> i to line }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), strength = 0.75f)
            .padding(12.dp)
    ) {
        Text(
            text = "Lyrics · ${result.source}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.76f)
        )
        Spacer(Modifier.height(4.dp))
        displayLines.forEach { (index, line) ->
            Text(
                text = line,
                style = if (index == activeIndex) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (index == activeIndex || !result.timed) Color.White else Color.White.copy(alpha = 0.58f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .glass(RoundedCornerShape(22.dp), strength = 1.2f)
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
                        tint = Color.White.copy(alpha = 0.92f),
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
                        tint = Color.White.copy(alpha = 0.92f),
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

/**
 * Live state of the spatial renderer: which layout the current output route resolved
 * to, and whether it is being binauralised. Tap to jump straight to the Studio.
 */
@Composable
private fun ImmerseChip(app: CeeceptApp, onClick: () -> Unit) {
    val params by app.engine.spaceParams.collectAsStateWithLifecycle()
    val strategy by app.engine.renderStrategy.collectAsStateWithLifecycle()
    val active = params.enabled
    val accent = CeeceptColors.Accent
    val label = if (!active) {
        "IMMERSE OFF"
    } else {
        val rig = when (strategy.virtualLayout.id) {
            "7.1.4_immersive" -> "7.1.4"
            "5.1_surround" -> "5.1"
            else -> "STEREO"
        }
        val fold = if (strategy.binauralize) "HRTF" else "SPEAKERS"
        "IMMERSE · $rig · $fold"
    }
    Box(
        modifier = Modifier
            .glass(RoundedCornerShape(50), strength = 0.7f)
            .background(if (active) accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DjModeChip(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .glass(RoundedCornerShape(50), strength = 0.7f)
            .background(if (active) CeeceptColors.Accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = if (active) CeeceptColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (active) "DJ MODE · AUTOMIX" else "DJ MODE",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) CeeceptColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddToPlaylistDialog(app: CeeceptApp, trackId: Long, onDismiss: () -> Unit) {
    val revision = app.history.revision.value
    val playlists = remember(revision) { app.history.playlistNames() }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (playlists.isEmpty()) {
                    Text(
                        "Create your first playlist, then Ceecept will remember it offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    playlists.forEach { name ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    app.history.addToPlaylist(name, trackId)
                                    onDismiss()
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(48) },
                    label = { Text("New playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val clean = newName.trim()
                        if (clean.isNotEmpty()) {
                            app.history.createPlaylist(clean)
                            app.history.addToPlaylist(clean, trackId)
                            onDismiss()
                        }
                    },
                    enabled = newName.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create and add")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
