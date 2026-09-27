package com.ceecept.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ceecept.music.data.MusicRepository
import com.ceecept.music.data.PlaybackHistory
import com.ceecept.music.data.Track
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI-facing bridge to [PlayerService]. Holds the MediaController, mirrors
 * player state into StateFlows and exposes transport controls.
 *
 * IMPORTANT: every MediaController method must be called on the main
 * (application) thread — MediaController throws IllegalStateException
 * otherwise. All access here is therefore pinned to Dispatchers.Main /
 * a main-thread Handler, and only plain state reads cross threads.
 */
class PlayerConnection(
    context: Context,
    private val repository: MusicRepository,
    private val history: PlaybackHistory,
    appScope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _externalTitle = MutableStateFlow<String?>(null)
    val externalTitle: StateFlow<String?> = _externalTitle.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffleOn = MutableStateFlow(false)
    val shuffleOn: StateFlow<Boolean> = _shuffleOn.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    /** MediaController delivers these on the main thread. */
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            syncPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = playbackState
            syncPosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resolveCurrent(mediaItem)
            syncPosition()
            mediaItem?.mediaId?.toLongOrNull()?.let { history.recordPlay(it) }
            persistState()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val c = _controller.value
            _queueSize.value = c?.mediaItemCount ?: 0
            _currentIndex.value = c?.currentMediaItemIndex ?: 0
            resolveCurrent(c?.currentMediaItem)
            syncPosition()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleOn.value = shuffleModeEnabled
            history.shuffle = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
            history.repeatMode = repeatMode
        }
    }

    init {
        connect()
        // Position ticker — MUST run on Main: it touches MediaController.
        appScope.launch(Dispatchers.Main) {
            var tick = 0
            while (isActive) {
                delay(250)
                val c = _controller.value
                if (c != null && c.isPlaying) {
                    syncPosition()
                    // Save the resume point about once a second.
                    if (++tick % 4 == 0) persistState()
                }
            }
        }
    }

    private fun connect() {
        try {
            val token = SessionToken(appContext, ComponentName(appContext, PlayerService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            future.addListener(
                {
                    try {
                        // get() returns immediately here (future is complete);
                        // then hop to Main before touching the controller.
                        val controller = future.get()
                        mainHandler.post { onControllerReady(controller) }
                    } catch (e: Exception) {
                        com.ceecept.music.CrashReporter.recordSoft(
                            appContext, "PlayerConnection.ready", e
                        )
                    }
                },
                MoreExecutors.directExecutor()
            )
        } catch (e: Exception) {
            com.ceecept.music.CrashReporter.recordSoft(appContext, "PlayerConnection.connect", e)
        }
    }

    /** Persist what is playing so the app can pick it up again next launch. */
    private fun persistState() {
        val c = _controller.value ?: return
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        history.saveNowPlaying(id, c.currentPosition.coerceAtLeast(0), c.currentMediaItemIndex)
    }

    /** Store the queue itself, so "resume" brings back the whole listening session. */
    private fun persistQueue(tracks: List<Track>, index: Int) {
        history.queueIds = tracks.map { it.id }
        history.queueIndex = index
    }

    /**
     * Restore the last session: the same queue, the same track, the same position —
     * paused, so nothing starts playing on its own when the app is opened.
     */
    private fun restoreSession(controller: MediaController) {
        if (!history.resumeOnLaunch) return
        if (controller.mediaItemCount > 0) return
        val ids = history.queueIds
        if (ids.isEmpty()) return
        val tracks = repository.findAll(ids)
        if (tracks.isEmpty()) return
        val index = history.queueIndex.coerceIn(0, tracks.lastIndex)
        controller.setMediaItems(tracks.map { it.toMediaItem() })
        controller.seekTo(index, history.lastPositionMs.coerceAtLeast(0))
        controller.shuffleModeEnabled = history.shuffle
        controller.repeatMode = history.repeatMode
        controller.prepare()
        restored = true
    }

    /** True once a previous session has been put back in place. */
    @Volatile
    var restored = false
        private set

    /** Runs on the main thread. */
    private fun onControllerReady(controller: MediaController) {
        controller.addListener(listener)
        _controller.value = controller
        _isPlaying.value = controller.isPlaying
        _playbackState.value = controller.playbackState
        _shuffleOn.value = controller.shuffleModeEnabled
        _repeatMode.value = controller.repeatMode
        _queueSize.value = controller.mediaItemCount
        resolveCurrent(controller.currentMediaItem)
        syncPosition()
        // The library may not have finished scanning yet; retry shortly if so.
        runCatching { restoreSession(controller) }
        if (!restored) {
            mainHandler.postDelayed({
                _controller.value?.let { c -> runCatching { restoreSession(c) } }
            }, 1500)
        }
    }

    private fun resolveCurrent(item: MediaItem?) {
        val id = item?.mediaId
        val trackId = id?.toLongOrNull()
        if (trackId != null) {
            _currentTrack.value = repository.findById(trackId)
            _externalTitle.value = null
        } else if (item != null) {
            _currentTrack.value = null
            _externalTitle.value = item.mediaMetadata.title?.toString() ?: "Audio"
        } else {
            _currentTrack.value = null
            _externalTitle.value = null
        }
        _currentIndex.value = _controller.value?.currentMediaItemIndex ?: 0
    }

    /** Must be called on the main thread (reads MediaController position). */
    private fun syncPosition() {
        val c = _controller.value ?: return
        _positionMs.value = c.currentPosition.coerceAtLeast(0)
        _durationMs.value = c.duration.let { if (it < 0) 0 else it }
    }

    /** Posts transport actions to the main thread so callers are safe from anywhere. */
    private fun onMain(block: (MediaController) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _controller.value?.let(block)
        } else {
            mainHandler.post { _controller.value?.let(block) }
        }
    }

    // ---------- Transport (all main-thread safe) ----------

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        persistQueue(tracks, index)
        history.recordPlay(tracks[index].id)
        onMain { c ->
            c.setMediaItems(tracks.map { it.toMediaItem() })
            c.seekToDefaultPosition(index)
            c.prepare()
            c.play()
        }
    }

    /** Resume the restored session (used by the "continue listening" button). */
    fun resumePlayback() {
        onMain { c ->
            if (c.mediaItemCount > 0) {
                c.prepare()
                c.play()
            }
        }
    }

    /** Resume the saved session even if the controller connected before the library scan finished. */
    fun resumeLastKnown() {
        onMain { c ->
            if (c.mediaItemCount == 0) restoreSession(c)
            if (c.mediaItemCount > 0) {
                val index = history.queueIndex.coerceIn(0, c.mediaItemCount - 1)
                c.seekTo(index, history.lastPositionMs.coerceAtLeast(0))
                c.prepare()
                c.play()
            }
        }
    }

    fun playExternal(uri: Uri, title: String) {
        onMain { c ->
            val item = MediaItem.Builder()
                .setMediaId("ext:$uri")
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(title).setArtist("External file").build()
                )
                .build()
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    fun togglePlayPause() {
        onMain { c -> if (c.isPlaying) c.pause() else c.play() }
    }

    fun next() {
        onMain { it.seekToNextMediaItem() }
    }

    fun previous() {
        onMain { it.seekToPreviousMediaItem() }
    }

    fun seekTo(positionMs: Long) {
        _positionMs.value = positionMs.coerceAtLeast(0)
        onMain { it.seekTo(positionMs.coerceAtLeast(0)) }
    }

    fun toggleShuffle() {
        onMain { c -> c.shuffleModeEnabled = !c.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        onMain { c ->
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setTrackNumber(trackNumber)
                .build()
        )
        .build()
}
