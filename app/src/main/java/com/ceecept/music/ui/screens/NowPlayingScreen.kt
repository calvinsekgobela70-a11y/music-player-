package com.ceecept.music.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.ceecept.music.CeeceptApp
import com.ceecept.music.data.LyricsResult
import com.ceecept.music.data.Track
import com.ceecept.music.data.TrackBookmark
import com.ceecept.music.ui.components.ArtworkBackdrop
import com.ceecept.music.ui.components.ArtworkPalette
import com.ceecept.music.ui.components.ArtworkView
import com.ceecept.music.ui.components.BouncyIconButton
import com.ceecept.music.ui.components.CeeceptSlider
import com.ceecept.music.ui.components.formatDuration
import com.ceecept.music.ui.theme.CeeceptColors
import com.ceecept.music.ui.theme.CeeceptMotion
import com.ceecept.music.ui.theme.glass
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class NowFeature { LYRICS, QUEUE, INFO, MEMORY }

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
    val queue by connection.queueTracks.collectAsStateWithLifecycle()
    val currentIndex by connection.currentIndex.collectAsStateWithLifecycle()
    val historyRevision = app.history.revision.value

    val title = track?.title ?: externalTitle ?: "Nothing playing"
    val subtitle = track?.let { "${it.artist} · ${it.album}" } ?: "Pick a song from your library"

    var backdropBitmap by remember(track?.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track?.id) {
        backdropBitmap = track?.let { app.repository.artwork(it, 768) ?: app.repository.artwork(it, 384) }
    }
    val palette = remember(backdropBitmap) { ArtworkPalette.from(backdropBitmap) }

    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var feature by remember { mutableStateOf<NowFeature?>(null) }
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
                    scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    scope.launch {
                        if (dragOffset.value > 220f && feature == null) {
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
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(width = 78.dp, height = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.36f))
            )
            Spacer(Modifier.height(12.dp))
            NowHeader(
                onClose = onClose,
                onOpenStudio = onOpenStudio,
                onOpenInfo = { if (track != null) feature = NowFeature.INFO }
            )
            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
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
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                PillIconButton(
                    icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    tint = if (liked) CeeceptColors.Accent else Color.White.copy(alpha = 0.90f),
                    contentDescription = if (liked) "Unlike" else "Like",
                    onClick = { track?.let { app.history.toggleLike(it.id) } }
                )
                Spacer(Modifier.width(6.dp))
                PillIconButton(
                    icon = Icons.Filled.PlaylistAdd,
                    tint = Color.White.copy(alpha = 0.90f),
                    contentDescription = "Add to playlist",
                    onClick = { if (track != null) showPlaylistDialog = true }
                )
            }

            Spacer(Modifier.height(10.dp))
            FeatureRail(
                hasLyrics = lyrics != null,
                queueCount = queue.size,
                rating = rating,
                onLyrics = { feature = NowFeature.LYRICS },
                onQueue = { feature = NowFeature.QUEUE },
                onMemory = { feature = NowFeature.MEMORY },
                onStudio = onOpenStudio
            )
            Spacer(Modifier.height(12.dp))

            var scrubMs by remember { mutableStateOf<Long?>(null) }
            val shownMs = scrubMs ?: positionMs
            CeeceptSlider(
                value = if (durationMs > 0) shownMs.toFloat() / durationMs else 0f,
                onValueChange = { frac -> scrubMs = (frac * durationMs).toLong() },
                onValueChangeFinished = {
                    scrubMs?.let { connection.seekTo(it) }
                    scrubMs = null
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(shownMs), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.70f))
                Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.70f))
            }
            Spacer(Modifier.height(10.dp))

            ModernPlaybackControls(
                playing = isPlaying,
                shuffleOn = shuffleOn,
                repeatMode = repeatMode,
                onShuffle = { connection.toggleShuffle() },
                onPrevious = { connection.previous() },
                onPlayPause = { connection.togglePlayPause() },
                onNext = { connection.next() },
                onRepeat = { connection.cycleRepeat() }
            )
            Spacer(Modifier.height(8.dp))
            NowStatusRow(
                djMode = djMode,
                visualizer = app.visualizerRepository.presetFor(track?.id ?: 0L, positionMs),
                onToggleDj = { connection.setDjMode(!djMode) }
            )
        }

        AnimatedVisibility(visible = feature != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
            val currentFeature = feature
            if (currentFeature != null) {
                FeaturePageScaffold(
                    palette = palette,
                    title = when (currentFeature) {
                        NowFeature.LYRICS -> "Lyrics"
                        NowFeature.QUEUE -> "Queue"
                        NowFeature.INFO -> "Info / Tags"
                        NowFeature.MEMORY -> "Memory"
                    },
                    subtitle = title,
                    onClose = { feature = null }
                ) {
                    AnimatedContent(
                        targetState = currentFeature,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "nowFeature"
                    ) { page ->
                        when (page) {
                            NowFeature.LYRICS -> AppleLyricsPage(lyrics, positionMs)
                            NowFeature.QUEUE -> QueueFeaturePage(
                                queue = queue,
                                currentIndex = currentIndex,
                                repository = app.repository,
                                onPlay = { connection.seekToQueueIndex(it) },
                                onRemove = { connection.removeQueueItem(it) },
                                onClearUpcoming = { connection.clearQueueAfterCurrent() }
                            )
                            NowFeature.INFO -> TrackInfoPage(track = track, rating = rating)
                            NowFeature.MEMORY -> MemoryFeaturePage(
                                rating = rating,
                                bookmarks = bookmarks,
                                positionMs = positionMs,
                                onRate = { value -> track?.let { app.history.setRating(it.id, value) } },
                                onBookmark = { track?.let { app.history.addBookmark(it.id, positionMs) } },
                                onSeekBookmark = { connection.seekTo(it.positionMs) },
                                onDeleteBookmark = { bookmark -> track?.let { app.history.removeBookmark(it.id, bookmark.positionMs) } },
                                onAddToPlaylist = { if (track != null) showPlaylistDialog = true }
                            )
                        }
                    }
                }
            }
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
private fun NowHeader(onClose: () -> Unit, onOpenStudio: () -> Unit, onOpenInfo: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        BouncyIconButton(onClick = onClose, contentDescription = "Close", modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.padding(6.dp).size(28.dp))
        }
        Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.72f), modifier = Modifier.align(Alignment.Center))
        Row(modifier = Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillIconButton(Icons.Filled.Info, Color.White.copy(alpha = 0.88f), "Track info", onOpenInfo)
            PillIconButton(Icons.Filled.GraphicEq, CeeceptColors.Accent, "Open Studio", onOpenStudio)
        }
    }
}

@Composable
private fun FeatureRail(
    hasLyrics: Boolean,
    queueCount: Int,
    rating: Int,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onMemory: () -> Unit,
    onStudio: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeatureButton("Lyrics", if (hasLyrics) "SYNC" else "LOCAL", Icons.Filled.AutoAwesome, onLyrics, Modifier.weight(1f))
        FeatureButton("Queue", queueCount.coerceAtLeast(0).toString(), Icons.Filled.QueueMusic, onQueue, Modifier.weight(1f))
        FeatureButton("Memory", if (rating > 0) "$rating★" else "Rate", Icons.Filled.BookmarkAdd, onMemory, Modifier.weight(1f))
        FeatureButton("Studio", "DSP", Icons.Filled.GraphicEq, onStudio, Modifier.weight(1f))
    }
}

@Composable
private fun FeatureButton(label: String, value: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .glass(RoundedCornerShape(18.dp), strength = 0.72f)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.58f), maxLines = 1)
    }
}

@Composable
private fun PillIconButton(icon: ImageVector, tint: Color, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .glass(CircleShape, strength = 0.78f)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun ModernPlaybackControls(
    playing: Boolean,
    shuffleOn: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ControlButton(Icons.Filled.Shuffle, shuffleOn, "Shuffle", onShuffle, 48)
        ControlButton(Icons.Filled.SkipPrevious, false, "Previous", onPrevious, 56)
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(42.dp))
        }
        ControlButton(Icons.Filled.SkipNext, false, "Next", onNext, 56)
        ControlButton(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, repeatMode != Player.REPEAT_MODE_OFF, "Repeat", onRepeat, 48)
    }
}

@Composable
private fun ControlButton(icon: ImageVector, active: Boolean, contentDescription: String, onClick: () -> Unit, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (active) CeeceptColors.Accent.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.14f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (active) Color.Black else Color.White, modifier = Modifier.size((size * 0.46f).dp))
    }
}

@Composable
private fun NowStatusRow(djMode: Boolean, visualizer: String?, onToggleDj: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .glass(RoundedCornerShape(50), strength = 0.60f)
                .background(if (djMode) CeeceptColors.Accent.copy(alpha = 0.20f) else Color.Transparent)
                .clickable { onToggleDj() }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(if (djMode) "DJ MODE · AUTOMIX" else "DJ MODE", style = MaterialTheme.typography.labelSmall, color = if (djMode) CeeceptColors.Accent else Color.White.copy(alpha = 0.68f))
        }
        visualizer?.let {
            Text(
                text = "MilkDrop · $it",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.66f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .glass(RoundedCornerShape(50), strength = 0.52f)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun FeaturePageScaffold(
    palette: ArtworkPalette,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(palette.dominant)) {
        ArtworkBackdrop(palette = palette, animated = true, intensity = 1.05f, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillIconButton(Icons.Filled.KeyboardArrowDown, Color.White.copy(alpha = 0.92f), "Close", onClose)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.60f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun AppleLyricsPage(result: LyricsResult?, positionMs: Long) {
    if (result == null) {
        EmptyFeature("No local lyrics found", "Put a matching .lrc or .txt next to this song and rescan.")
        return
    }
    val activeIndex = remember(result, positionMs) {
        if (!result.timed) -1 else result.lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    }
    val lines = if (result.timed) {
        val start = (activeIndex - 4).coerceAtLeast(0)
        result.lines.drop(start).take(9).mapIndexed { i, line -> (start + i) to line.text }
    } else {
        result.plainText.lines().filter { it.isNotBlank() }.take(14).mapIndexed { i, line -> i to line }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .glass(RoundedCornerShape(30.dp), strength = 0.58f)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Source · ${result.source}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.58f))
        Spacer(Modifier.height(12.dp))
        lines.forEach { (index, text) ->
            val active = !result.timed || index == activeIndex
            val alpha by animateFloatAsState(targetValue = if (active) 1f else 0.34f, animationSpec = tween(180), label = "lyricAlpha")
            val scale by animateFloatAsState(targetValue = if (active) 1.035f else 0.965f, animationSpec = tween(180), label = "lyricScale")
            Text(
                text = text,
                style = if (active) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
            )
        }
    }
}

@Composable
private fun QueueFeaturePage(
    queue: List<Track>,
    currentIndex: Int,
    repository: com.ceecept.music.data.MusicRepository,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClearUpcoming: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${queue.size} songs", style = MaterialTheme.typography.titleMedium, color = Color.White)
            TextButton(onClick = onClearUpcoming) { Text("Clear upcoming", color = CeeceptColors.Accent) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (index == currentIndex) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
                        .clickable { onPlay(index) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkView(item, repository, modifier = Modifier.size(48.dp), cornerRadius = 12.dp, thumbSize = 256)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.58f), maxLines = 1)
                    }
                    if (index == currentIndex) Text("LIVE", style = MaterialTheme.typography.labelSmall, color = CeeceptColors.Accent)
                    else BouncyIconButton(onClick = { onRemove(index) }, contentDescription = "Remove") {
                        Icon(Icons.Filled.Delete, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.padding(6.dp).size(19.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackInfoPage(track: Track?, rating: Int) {
    if (track == null) {
        EmptyFeature("No track selected", "Start playing a song to inspect its tags.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .glass(RoundedCornerShape(28.dp), strength = 0.66f)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        InfoTag("Title", track.title)
        InfoTag("Artist", track.artist)
        InfoTag("Album", track.album)
        InfoTag("Album artist", track.albumArtist.ifBlank { "—" })
        InfoTag("Genre", track.genre.ifBlank { "—" })
        InfoTag("Composer", track.composer.ifBlank { "—" })
        InfoTag("Year", if (track.year > 0) track.year.toString() else "—")
        InfoTag("Rating", if (rating > 0) "$rating / 5" else "Not rated")
        InfoTag("Duration", formatDuration(track.durationMs))
        InfoTag("File", track.filePath.ifBlank { track.uri.toString() })
    }
}

@Composable
private fun MemoryFeaturePage(
    rating: Int,
    bookmarks: List<TrackBookmark>,
    positionMs: Long,
    onRate: (Int) -> Unit,
    onBookmark: () -> Unit,
    onSeekBookmark: (TrackBookmark) -> Unit,
    onDeleteBookmark: (TrackBookmark) -> Unit,
    onAddToPlaylist: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(28.dp), strength = 0.66f)
                .padding(18.dp)
        ) {
            Text("Rating", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { i ->
                    val star = i + 1
                    Icon(
                        imageVector = if (star <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Rate $star",
                        tint = if (star <= rating) CeeceptColors.Accent else Color.White.copy(alpha = 0.42f),
                        modifier = Modifier.size(34.dp).clickable { onRate(if (rating == star) 0 else star) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onBookmark) { Text("Bookmark ${formatDuration(positionMs)}") }
                Button(onClick = onAddToPlaylist) { Text("Playlist") }
            }
        }
        Text("Bookmarks", style = MaterialTheme.typography.titleMedium, color = Color.White)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(bookmarks, key = { _, item -> item.positionMs }) { _, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = if (abs(item.positionMs - positionMs) < 1500) 0.20f else 0.09f))
                        .clickable { onSeekBookmark(item) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.label, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
                    Text(formatDuration(item.positionMs), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.62f))
                    BouncyIconButton(onClick = { onDeleteBookmark(item) }, contentDescription = "Delete") {
                        Icon(Icons.Filled.Delete, null, tint = Color.White.copy(alpha = 0.70f), modifier = Modifier.padding(6.dp).size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoTag(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = CeeceptColors.Accent)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.86f))
    }
}

@Composable
private fun EmptyFeature(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.62f), textAlign = TextAlign.Center)
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
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.padding(8.dp).size(26.dp)
                    )
                }
                BouncyIconButton(onClick = { connection.next() }, contentDescription = "Next") {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.padding(8.dp).size(26.dp))
                }
            }
            val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = progress,
                color = CeeceptColors.Accent,
                trackColor = Color.Transparent,
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
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
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
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
                        ) { Text(name, style = MaterialTheme.typography.titleMedium) }
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
                ) { Text("Create and add") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
