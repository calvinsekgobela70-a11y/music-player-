package com.ceecept.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ceecept.music.CeeceptApp
import com.ceecept.music.data.AlbumEntry
import com.ceecept.music.data.ArtistEntry
import com.ceecept.music.data.Track
import com.ceecept.music.ui.components.ArtworkView
import com.ceecept.music.ui.components.BouncyIconButton
import com.ceecept.music.ui.components.CeeceptTabRow
import com.ceecept.music.ui.components.formatDuration
import com.ceecept.music.ui.theme.CeeceptColors
import com.ceecept.music.ui.theme.CeeceptMotion

private sealed interface LibraryRoute {
    data object Root : LibraryRoute
    data class Artist(val name: String) : LibraryRoute
    data class Album(val albumId: Long) : LibraryRoute
}

@Composable
fun LibraryScreen(app: CeeceptApp) {
    val context = LocalContext.current
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = grants[audioPermission] == true
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) app.repository.refresh()
    }

    if (!hasPermission) {
        PermissionGate(
            onGrant = {
                val perms = mutableListOf(audioPermission)
                if (Build.VERSION.SDK_INT >= 33) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            }
        )
        return
    }

    var route by remember { mutableStateOf<LibraryRoute>(LibraryRoute.Root) }

    BackHandler(enabled = route != LibraryRoute.Root) {
        route = LibraryRoute.Root
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (targetState == LibraryRoute.Root) {
                (slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = CeeceptMotion.screen()
                ) + fadeIn()) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = CeeceptMotion.screen()
                    ) + fadeOut())
            } else {
                (slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = CeeceptMotion.screen()
                ) + fadeIn()) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { -it / 3 },
                        animationSpec = CeeceptMotion.screen()
                    ) + fadeOut())
            }
        },
        label = "libraryNav"
    ) { current ->
        when (current) {
            LibraryRoute.Root -> LibraryRoot(
                app = app,
                onArtist = { route = LibraryRoute.Artist(it) },
                onAlbum = { route = LibraryRoute.Album(it) }
            )
            is LibraryRoute.Artist -> ArtistDetail(
                app = app,
                name = current.name,
                onBack = { route = LibraryRoute.Root },
                onAlbum = { route = LibraryRoute.Album(it) }
            )
            is LibraryRoute.Album -> AlbumDetail(
                app = app,
                albumId = current.albumId,
                onBack = { route = LibraryRoute.Root }
            )
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(CeeceptColors.accentGradient()),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.LibraryMusic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Your music lives here",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ceecept plays audio stored on your phone — MP3, WAV, FLAC, OGG, M4A and more. Nothing ever leaves your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Allow access to audio",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .clickable { onGrant() }
                    .padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun LibraryRoot(
    app: CeeceptApp,
    onArtist: (String) -> Unit,
    onAlbum: (Long) -> Unit
) {
    val tracks by app.repository.tracks.collectAsStateWithLifecycle()
    val isLoading by app.repository.isLoading.collectAsStateWithLifecycle()
    val currentTrack by app.playerConnection.currentTrack.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search songs, artists, albums") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(12.dp))
        CeeceptTabRow(
            tabs = listOf("Songs", "Artists", "Albums"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(8.dp))

        when {
            isLoading && tracks.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            tracks.isEmpty() -> {
                EmptyLibrary()
            }
            else -> {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        fadeIn(animationSpec = CeeceptMotion.screen<Float>()) togetherWith
                            fadeOut(animationSpec = CeeceptMotion.screen())
                    },
                    label = "libraryTab"
                ) { t ->
                    when (t) {
                        0 -> SongList(
                            tracks = tracks.filter { it.matches(query) },
                            currentId = currentTrack?.id,
                            app = app
                        )
                        1 -> ArtistList(
                            artists = app.repository.artists().filter {
                                it.name.contains(query, ignoreCase = true)
                            },
                            onArtist = onArtist
                        )
                        else -> AlbumGrid(
                            albums = app.repository.albums().filter {
                                it.title.contains(query, ignoreCase = true) ||
                                    it.artist.contains(query, ignoreCase = true)
                            },
                            app = app,
                            onAlbum = onAlbum
                        )
                    }
                }
            }
        }
    }
}

private fun Track.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, ignoreCase = true) ||
        artist.contains(query, ignoreCase = true) ||
        album.contains(query, ignoreCase = true)
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.AudioFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(text = "No music found", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Copy MP3, WAV, FLAC or other audio files to your phone and they will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SongList(
    tracks: List<Track>,
    currentId: Long?,
    app: CeeceptApp,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp)
) {
    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            SongRow(
                track = track,
                isCurrent = track.id == currentId,
                app = app,
                onClick = { app.playerConnection.playQueue(tracks, index) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    track: Track,
    isCurrent: Boolean,
    app: CeeceptApp,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkView(
            track = track,
            repository = app.repository,
            modifier = Modifier.size(52.dp),
            cornerRadius = 10.dp,
            thumbSize = 256
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(visible = isCurrent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EqualizerBars()
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Mini "now playing" bars that bounce while the row's track is current. */
@Composable
private fun EqualizerBars() {
    val isPlaying by remember { mutableStateOf(true) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp)
    ) {
        listOf(0.5f, 1f, 0.65f, 0.85f).forEachIndexed { i, _ ->
            val target = if (isPlaying) (6 + (i * 7 % 12)).dp else 4.dp
            val height by androidx.compose.animation.core.animateDpAsState(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CeeceptColors.Accent)
            )
        }
    }
}

@Composable
private fun ArtistList(artists: List<ArtistEntry>, onArtist: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(artists, key = { _, a -> a.name }) { _, artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtist(artist.name) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(CeeceptColors.Violet, CeeceptColors.AccentDeep)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${artist.trackCount} songs · ${artist.albumCount} albums",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<AlbumEntry>,
    app: CeeceptApp,
    onAlbum: (Long) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums, key = { it.albumId }) { album ->
            Column(
                modifier = Modifier.clickable { onAlbum(album.albumId) }
            ) {
                ArtworkView(
                    track = album.sample,
                    repository = app.repository,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    cornerRadius = 16.dp,
                    thumbSize = 512
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ArtistDetail(
    app: CeeceptApp,
    name: String,
    onBack: () -> Unit,
    onAlbum: (Long) -> Unit
) {
    val tracks = remember(name) { app.repository.tracksByArtist(name) }
    val isPlaying by app.playerConnection.isPlaying.collectAsStateWithLifecycle()
    val currentTrack by app.playerConnection.currentTrack.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        DetailHeader(
            title = name,
            subtitle = "${tracks.size} songs",
            icon = Icons.Filled.Person,
            onBack = onBack,
            onPlay = { if (tracks.isNotEmpty()) app.playerConnection.playQueue(tracks, 0) },
            onShuffle = {
                if (tracks.isNotEmpty()) {
                    app.playerConnection.playQueue(tracks.shuffled(), 0)
                    if (!isPlaying) app.playerConnection.togglePlayPause()
                }
            }
        )
        SongList(tracks = tracks, currentId = currentTrack?.id, app = app)
    }
}

@Composable
private fun AlbumDetail(app: CeeceptApp, albumId: Long, onBack: () -> Unit) {
    val tracks = remember(albumId) { app.repository.tracksByAlbum(albumId) }
    val album = remember(albumId) { app.repository.albums().find { it.albumId == albumId } }
    val isPlaying by app.playerConnection.isPlaying.collectAsStateWithLifecycle()
    val currentTrack by app.playerConnection.currentTrack.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BouncyIconButton(onClick = onBack, contentDescription = "Back") {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                )
            }
            ArtworkView(
                track = tracks.firstOrNull(),
                repository = app.repository,
                modifier = Modifier
                    .padding(8.dp)
                    .size(72.dp),
                cornerRadius = 14.dp,
                thumbSize = 512
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album?.title ?: "Album",
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album?.artist ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        DetailActions(
            onPlay = { if (tracks.isNotEmpty()) app.playerConnection.playQueue(tracks, 0) },
            onShuffle = {
                if (tracks.isNotEmpty()) {
                    app.playerConnection.playQueue(tracks.shuffled(), 0)
                    if (!isPlaying) app.playerConnection.togglePlayPause()
                }
            }
        )
        SongList(tracks = tracks, currentId = currentTrack?.id, app = app)
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BouncyIconButton(onClick = onBack, contentDescription = "Back") {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                )
            }
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CeeceptColors.accentGradient()),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DetailActions(onPlay = onPlay, onShuffle = onShuffle)
    }
}

@Composable
private fun DetailActions(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .clickable { onPlay() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(text = "Play", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .clickable { onShuffle() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(text = "Shuffle", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
