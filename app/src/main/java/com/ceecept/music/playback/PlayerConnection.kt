package com.ceecept.music.playback

import android.content.ComponentName
import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ceecept.music.audio.AudioEngine
import com.ceecept.music.audio.DjTransitionProcessor
import com.ceecept.music.data.DjAnalyzer
import com.ceecept.music.data.DjTrackAnalysis
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
import java.util.ArrayDeque
import java.util.Collections

private const val DJ_ANALYSIS_PRIORITY_WINDOW = 5
private const val DJ_REORDER_WINDOW = 10
private const val DJ_WARMUP_THROTTLE_MS = 3_500L

private data class DjTransitionPlan(
    val type: Int,
    val windowMs: Long,
    val mixOutMs: Long,
    val bpm: Float,
    val compatibility: Float,
    val harmonic: Boolean,
    val tempoDiff: Float
)

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
    private val engine: AudioEngine,
    private val djAnalyzer: DjAnalyzer,
    private val appScope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysingIds = Collections.synchronizedSet(mutableSetOf<Long>())
    private val analysisLock = Any()
    private val pendingAnalysis = ArrayDeque<Track>()
    private val queuedAnalysisIds = mutableSetOf<Long>()
    @Volatile private var analysisWorkerActive = false
    @Volatile private var analysisGeneration = 0
    @Volatile private var lastDjWarmupAtMs = 0L
    @Volatile private var reorderAfterAnalysis = false
    @Volatile private var reorderInFlight = false
    @Volatile private var djAutoAdvancedFromId = Long.MIN_VALUE

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

    private val _djMode = MutableStateFlow(history.djMode)
    val djMode: StateFlow<Boolean> = _djMode.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val _queueTracks = MutableStateFlow<List<Track>>(emptyList())
    val queueTracks: StateFlow<List<Track>> = _queueTracks.asStateFlow()

    private val _sleepTimerEndMs = MutableStateFlow(0L)
    val sleepTimerEndMs: StateFlow<Long> = _sleepTimerEndMs.asStateFlow()

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
            djAutoAdvancedFromId = Long.MIN_VALUE
            resolveCurrent(mediaItem)
            syncPosition()
            syncQueue(_controller.value)
            mediaItem?.mediaId?.toLongOrNull()?.let { history.recordPlay(it) }
            warmDjAnalysis()
            persistState()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val c = _controller.value
            _queueSize.value = c?.mediaItemCount ?: 0
            _currentIndex.value = c?.currentMediaItemIndex ?: 0
            syncQueue(c)
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
                    checkSleepTimer(c)
                    updateDjTransition(c)
                    // Save the resume point about once a second.
                    if (++tick % 4 == 0) persistState()
                } else {
                    engine.dj.setTransition(enabled = false, mode = DjTransitionProcessor.MODE_NONE, progress = 0f, bpm = 120f)
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

    private fun syncQueue(controller: MediaController?) {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).mapNotNull { i ->
            runCatching { c.getMediaItemAt(i).mediaId.toLongOrNull() }.getOrNull()
                ?.let { repository.findById(it) }
        }
        _queueTracks.value = items
        _queueSize.value = c.mediaItemCount
        _currentIndex.value = c.currentMediaItemIndex.coerceAtLeast(0)
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
        syncQueue(controller)
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
        syncQueue(controller)
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

    private fun currentAnalysis(): DjTrackAnalysis? =
        _currentTrack.value?.id?.let { history.djAnalysis(it) }

    private fun nextAnalysis(c: MediaController): DjTrackAnalysis? {
        val next = c.currentMediaItemIndex + 1
        if (next !in 0 until c.mediaItemCount) return null
        val id = runCatching { c.getMediaItemAt(next).mediaId.toLongOrNull() }.getOrNull()
        return id?.let { history.djAnalysis(it) }
    }

    private fun transitionPlan(current: DjTrackAnalysis?, next: DjTrackAnalysis?, durationMs: Long): DjTransitionPlan {
        val a = current
        val b = next
        val bpm = a?.bpm ?: b?.bpm ?: 120f
        val beat = (a?.beatMs ?: (60_000f / bpm.coerceIn(70f, 190f)).toLong()).coerceIn(315L, 860L)
        if (a == null || b == null || !a.mixable || !b.mixable || a.confidence < 0.42f || b.confidence < 0.42f) {
            val w = 14_000L.coerceAtMost((durationMs * 0.28f).toLong()).coerceAtLeast(7_000L)
            val cue = a?.outroStartMs?.takeIf { it > 0L } ?: (durationMs - w)
            return DjTransitionPlan(
                type = DjTransitionProcessor.TYPE_MEDIUM_BLEND,
                windowMs = w,
                mixOutMs = cue.coerceIn(0L, (durationMs - w / 2).coerceAtLeast(0L)),
                bpm = bpm,
                compatibility = 0.45f,
                harmonic = false,
                tempoDiff = 99f
            )
        }
        val diff = absTempoDiff(a.bpm, b.bpm)
        val harmonic = harmonicCompatible(a, b)
        val type = when {
            diff > 10f -> DjTransitionProcessor.TYPE_ECHO_COLD
            harmonic && diff <= 4f -> DjTransitionProcessor.TYPE_LONG_BLEND
            harmonic && diff <= 10f -> DjTransitionProcessor.TYPE_MEDIUM_BLEND
            !harmonic && diff <= 4f -> DjTransitionProcessor.TYPE_SHORT_BLEND
            else -> DjTransitionProcessor.TYPE_DROP_MIX
        }
        val bars = when (type) {
            DjTransitionProcessor.TYPE_LONG_BLEND -> if (a.energy > 0.55f && b.energy > 0.55f) 16 else 12
            DjTransitionProcessor.TYPE_MEDIUM_BLEND -> 8
            DjTransitionProcessor.TYPE_SHORT_BLEND -> 6
            DjTransitionProcessor.TYPE_DROP_MIX -> 2
            DjTransitionProcessor.TYPE_ECHO_COLD -> 2
            else -> 4
        }
        val w = when (type) {
            DjTransitionProcessor.TYPE_DROP_MIX -> (beat * 8L).coerceIn(2_400L, 7_500L)
            DjTransitionProcessor.TYPE_ECHO_COLD -> (beat * 8L).coerceIn(2_400L, 8_500L)
            else -> (beat * 4L * bars).coerceIn(7_000L, 42_000L)
        }.coerceAtMost((durationMs * 0.32f).toLong().coerceAtLeast(4_000L))
        val cue = a.outroStartMs.takeIf { it > 0L } ?: a.outroCueMs.takeIf { it > 0L }
            ?: (durationMs - w)
        val mixOut = phraseSnap(cue.coerceAtMost(durationMs - beat * 2L), beat, a.downbeatOffsetMs)
            .coerceIn(0L, (durationMs - 800L).coerceAtLeast(0L))
        return DjTransitionPlan(
            type = type,
            windowMs = w,
            mixOutMs = mixOut,
            bpm = bpm,
            compatibility = compatibility(a, b, 0.2f),
            harmonic = harmonic,
            tempoDiff = diff
        )
    }

    private fun updateDjTransition(c: MediaController) {
        if (!_djMode.value || c.mediaItemCount <= 0) {
            engine.dj.setTransition(false, DjTransitionProcessor.MODE_NONE, 0f, 120f)
            return
        }
        val duration = c.duration.takeIf { it > 0 } ?: return
        val position = c.currentPosition.coerceAtLeast(0)
        val current = currentAnalysis()
        val next = nextAnalysis(c)
        val plan = transitionPlan(current, next, duration)
        val mode: Int
        val progress: Float
        when {
            position < (current?.introEndMs?.takeIf { it > 0L } ?: plan.windowMs.coerceAtMost(12_000L)) -> {
                val introWindow = (current?.introEndMs?.takeIf { it > 0L } ?: plan.windowMs.coerceAtMost(12_000L))
                    .coerceIn(2_000L, 18_000L)
                mode = DjTransitionProcessor.MODE_INTRO
                progress = (position.toFloat() / introWindow).coerceIn(0f, 1f)
            }
            c.currentMediaItemIndex < c.mediaItemCount - 1 && position >= plan.mixOutMs -> {
                mode = DjTransitionProcessor.MODE_OUTRO
                progress = ((position - plan.mixOutMs).toFloat() / plan.windowMs).coerceIn(0f, 1f)
            }
            else -> {
                mode = DjTransitionProcessor.MODE_NONE
                progress = 0f
            }
        }
        engine.dj.setTransition(
            enabled = mode != DjTransitionProcessor.MODE_NONE,
            mode = mode,
            progress = progress,
            bpm = plan.bpm,
            type = plan.type,
            overlapIntensity = if (mode == DjTransitionProcessor.MODE_NONE) 0f else plan.compatibility.coerceIn(0.25f, 1f)
        )
        if (mode == DjTransitionProcessor.MODE_OUTRO && c.currentMediaItemIndex < c.mediaItemCount - 1 && !history.crossfadeEnabled) {
            val currentId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: Long.MIN_VALUE
            val threshold = djAdvanceThreshold(plan.type)
            if (currentId != Long.MIN_VALUE && currentId != djAutoAdvancedFromId && progress >= threshold) {
                djAutoAdvancedFromId = currentId
                // Fallback for users who disable the real dual-deck crossfade: keep the
                // old phrase/echo handoff instead of waiting for silence at file end.
                c.seekToNextMediaItem()
                c.play()
            }
        }
    }

    private fun djAdvanceThreshold(type: Int): Float = when (type) {
        DjTransitionProcessor.TYPE_LONG_BLEND -> 0.92f
        DjTransitionProcessor.TYPE_MEDIUM_BLEND -> 0.88f
        DjTransitionProcessor.TYPE_SHORT_BLEND -> 0.82f
        DjTransitionProcessor.TYPE_DROP_MIX -> 0.76f
        DjTransitionProcessor.TYPE_ECHO_COLD -> 0.78f
        else -> 0.86f
    }

    private fun clearDjAnalysisQueue() {
        synchronized(analysisLock) {
            analysisGeneration++
            pendingAnalysis.clear()
            queuedAnalysisIds.clear()
            reorderAfterAnalysis = false
        }
    }

    private fun scheduleDjWarmup(delayMs: Long = 900L) {
        mainHandler.postDelayed({
            if (_djMode.value) warmDjAnalysis(force = true)
        }, delayMs)
    }

    private fun warmDjAnalysis(force: Boolean = false) {
        if (!_djMode.value) return
        val now = System.currentTimeMillis()
        if (!force && now - lastDjWarmupAtMs < DJ_WARMUP_THROTTLE_MS) return
        lastDjWarmupAtMs = now
        val c = _controller.value
        val start = c?.currentMediaItemIndex?.coerceAtLeast(0) ?: history.queueIndex.coerceAtLeast(0)
        val idsToWarm = buildList {
            _currentTrack.value?.id?.let { add(it) }
            if (c != null) {
                val end = minOf(c.mediaItemCount, start + DJ_ANALYSIS_PRIORITY_WINDOW)
                for (i in start until end) {
                    runCatching { c.getMediaItemAt(i).mediaId.toLongOrNull() }.getOrNull()?.let { add(it) }
                }
            } else {
                val queueIds = history.queueIds
                for (i in start until minOf(queueIds.size, start + DJ_ANALYSIS_PRIORITY_WINDOW)) add(queueIds[i])
            }
        }.distinct()
        analyzeTracksAsync(idsToWarm.mapNotNull { repository.findById(it) }, reorderWhenDone = true)
    }

    private fun analyzeTracksAsync(tracks: List<Track>, reorderWhenDone: Boolean = false) {
        if (tracks.isEmpty()) return
        var shouldStartWorker = false
        var enqueuedAny = false
        synchronized(analysisLock) {
            if (reorderWhenDone) reorderAfterAnalysis = true
            for (track in tracks) {
                if (history.djAnalysis(track.id) == null && queuedAnalysisIds.add(track.id)) {
                    pendingAnalysis.addLast(track)
                    enqueuedAny = true
                }
            }
            if (!analysisWorkerActive && pendingAnalysis.isNotEmpty()) {
                analysisWorkerActive = true
                shouldStartWorker = true
            }
        }
        if (shouldStartWorker) {
            val generation = analysisGeneration
            appScope.launch(Dispatchers.IO) { runAnalysisWorker(generation) }
        } else if (reorderWhenDone && !enqueuedAny) {
            mainHandler.post { reorderUpcomingQueueFromAnalyses() }
        }
    }

    private suspend fun runAnalysisWorker(generation: Int) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        var analysedSinceReorder = 0
        while (_djMode.value && generation == analysisGeneration) {
            val track = synchronized(analysisLock) { pendingAnalysis.pollFirst() } ?: break
            analysingIds.add(track.id)
            try {
                val analysis = djAnalyzer.analyze(track) { _djMode.value && generation == analysisGeneration }
                if (_djMode.value && generation == analysisGeneration) {
                    history.saveDjAnalysis(analysis)
                    analysedSinceReorder++
                    if (analysedSinceReorder >= 2) {
                        analysedSinceReorder = 0
                        mainHandler.post { reorderUpcomingQueueFromAnalyses() }
                    }
                }
            } catch (e: Exception) {
                com.ceecept.music.CrashReporter.recordSoft(appContext, "DjAnalyzer", e)
            } finally {
                analysingIds.remove(track.id)
                synchronized(analysisLock) { queuedAnalysisIds.remove(track.id) }
            }
            if (!_djMode.value || generation != analysisGeneration) break
            // Keep playback responsive on Huawei/EMUI: yield between MediaCodec analyses
            // instead of hammering storage/CPU when AutoMix is switched on.
            delay(if (_isPlaying.value) 950L else 180L)
        }
        val shouldReorder = synchronized(analysisLock) {
            analysisWorkerActive = false
            val r = _djMode.value && generation == analysisGeneration && (reorderAfterAnalysis || analysedSinceReorder > 0)
            reorderAfterAnalysis = false
            r
        }
        if (shouldReorder) mainHandler.post { reorderUpcomingQueueFromAnalyses() }
        var restart = false
        val restartGeneration = analysisGeneration
        synchronized(analysisLock) {
            if (_djMode.value && !analysisWorkerActive && pendingAnalysis.isNotEmpty()) {
                analysisWorkerActive = true
                restart = true
            }
        }
        if (restart) appScope.launch(Dispatchers.IO) { runAnalysisWorker(restartGeneration) }
    }

    private fun reorderUpcomingQueueFromAnalyses() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reorderUpcomingQueueFromAnalyses() }
            return
        }
        if (!_djMode.value || reorderInFlight) return
        val c = _controller.value ?: return
        if (c.mediaItemCount < 3) return
        val mediaCount = c.mediaItemCount
        val index = c.currentMediaItemIndex.coerceIn(0, mediaCount - 1)
        val currentId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val syncedQueue = _queueTracks.value
        val tracks = if (syncedQueue.size == mediaCount) {
            syncedQueue
        } else {
            val ids = (0 until mediaCount).mapNotNull { i ->
                runCatching { c.getMediaItemAt(i).mediaId.toLongOrNull() }.getOrNull()
            }
            if (ids.size != mediaCount) return
            repository.findAll(ids)
        }
        if (tracks.size != mediaCount || index !in tracks.indices) return
        val current = tracks[index]
        val windowEnd = minOf(tracks.size, index + 1 + DJ_REORDER_WINDOW)
        val futureWindow = tracks.subList(index + 1, windowEnd)
        if (futureWindow.size < 2) return
        if (futureWindow.count { history.djAnalysis(it.id) != null } < 2) return

        reorderInFlight = true
        appScope.launch(Dispatchers.Default) {
            val orderedWindow = smartDjOrder(listOf(current) + futureWindow, 0).first.drop(1)
            val oldIds = futureWindow.map { it.id }
            val newIds = orderedWindow.map { it.id }
            if (oldIds == newIds) {
                mainHandler.post { reorderInFlight = false }
                return@launch
            }
            val newQueue = tracks.take(index + 1) + orderedWindow + tracks.drop(windowEnd)
            mainHandler.post {
                try {
                    val live = _controller.value ?: return@post
                    val liveCurrent = live.currentMediaItem?.mediaId?.toLongOrNull()
                    if (!_djMode.value || live.mediaItemCount != mediaCount || live.currentMediaItemIndex != index || liveCurrent != currentId) {
                        return@post
                    }
                    live.replaceMediaItems(index + 1, windowEnd, orderedWindow.map { it.toMediaItem() })
                    syncQueue(live)
                    persistQueue(newQueue, index)
                    warmDjAnalysis()
                } finally {
                    reorderInFlight = false
                }
            }
        }
    }

    private fun smartDjOrder(tracks: List<Track>, startIndex: Int): Pair<List<Track>, Int> {
        if (tracks.size < 3 || !_djMode.value) return tracks to startIndex.coerceIn(0, tracks.lastIndex)
        val remaining = tracks.toMutableList()
        val start = remaining.removeAt(startIndex.coerceIn(0, remaining.lastIndex))
        val ordered = mutableListOf(start)
        while (remaining.isNotEmpty()) {
            val prev = ordered.last()
            val prevAnalysis = history.djAnalysis(prev.id)
            val nextIndex = remaining.indices.maxByOrNull { i ->
                compatibility(prevAnalysis, history.djAnalysis(remaining[i].id), fallbackDistance(prev, remaining[i]))
            } ?: 0
            ordered += remaining.removeAt(nextIndex)
        }
        return ordered to 0
    }

    private fun compatibility(a: DjTrackAnalysis?, b: DjTrackAnalysis?, fallback: Float): Float {
        if (a == null || b == null) return fallback
        if (!a.mixable || !b.mixable) return fallback * 0.45f
        val bpmDiff = absTempoDiff(a.bpm, b.bpm)
        val bpmScore = (1f - bpmDiff / 12f).coerceIn(0f, 1f)
        val keyScore = harmonicScore(a, b)
        val energyScore = (1f - kotlin.math.abs(a.energy - b.energy) * 1.8f).coerceIn(0f, 1f)
        val loudnessScore = (1f - kotlin.math.abs(a.loudnessDb - b.loudnessDb) / 14f).coerceIn(0f, 1f)
        val conf = ((a.confidence + b.confidence + a.keyConfidence + b.keyConfidence) * 0.25f).coerceIn(0.20f, 1f)
        return (0.44f * bpmScore + 0.30f * keyScore + 0.18f * energyScore + 0.08f * loudnessScore) * conf
    }

    private fun harmonicCompatible(a: DjTrackAnalysis, b: DjTrackAnalysis): Boolean = harmonicScore(a, b) >= 0.72f

    private fun harmonicScore(a: DjTrackAnalysis, b: DjTrackAnalysis): Float {
        val sameKey = a.key == b.key
        val adjacent = circularDistance(a.key, b.key) == 1
        return when {
            sameKey && a.minor == b.minor -> 1f
            sameKey && a.minor != b.minor -> 0.86f // relative major/minor Camelot switch
            adjacent && a.minor == b.minor -> 0.78f
            circularDistance(a.key, b.key) == 2 && a.minor == b.minor -> 0.58f
            else -> 0.28f
        }
    }

    private fun phraseSnap(ms: Long, beatMs: Long, downbeatOffsetMs: Long): Long {
        val phrase = (beatMs * 16L).coerceAtLeast(1L) // 4 bars in 4/4
        val shifted = ms - downbeatOffsetMs
        return (((shifted + phrase / 2) / phrase) * phrase + downbeatOffsetMs).coerceAtLeast(0L)
    }

    private fun fallbackDistance(a: Track, b: Track): Float {
        var score = 0.25f
        if (a.artist.equals(b.artist, ignoreCase = true)) score += 0.25f
        if (a.album.equals(b.album, ignoreCase = true)) score += 0.18f
        val durA = a.durationMs.coerceAtLeast(1L).toFloat()
        val durB = b.durationMs.coerceAtLeast(1L).toFloat()
        score += (1f - kotlin.math.abs(durA - durB) / maxOf(durA, durB)).coerceIn(0f, 1f) * 0.22f
        return score
    }

    private fun absTempoDiff(a: Float, b: Float): Float {
        val direct = kotlin.math.abs(a - b)
        val half = kotlin.math.abs(a - b * 0.5f)
        val double = kotlin.math.abs(a - b * 2f)
        return minOf(direct, half, double)
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = kotlin.math.abs((a - b) % 12)
        return minOf(d, 12 - d)
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
        val queue = tracks
        val index = startIndex.coerceIn(0, queue.lastIndex)
        persistQueue(queue, index)
        history.recordPlay(queue[index].id)
        onMain { c ->
            c.setMediaItems(queue.map { it.toMediaItem() })
            c.seekToDefaultPosition(index)
            c.prepare()
            c.play()
            syncQueue(c)
            if (_djMode.value) {
                scheduleDjWarmup(delayMs = 1_200L)
                mainHandler.postDelayed({ if (_djMode.value) reorderUpcomingQueueFromAnalyses() }, 3_000L)
            }
        }
    }

    fun setDjMode(enabled: Boolean) {
        _djMode.value = enabled
        history.djMode = enabled
        if (!enabled) {
            clearDjAnalysisQueue()
            reorderInFlight = false
            engine.dj.setTransition(false, DjTransitionProcessor.MODE_NONE, 0f, 120f)
        } else {
            clearDjAnalysisQueue()
            onMain { c ->
                syncQueue(c)
                // Give playback and the UI a moment to settle before opening MediaCodec
                // for analysis. This is the key anti-jam change for EMUI devices.
                scheduleDjWarmup(delayMs = 1_400L)
                mainHandler.postDelayed({ if (_djMode.value) reorderUpcomingQueueFromAnalyses() }, 3_200L)
            }
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

    fun seekToQueueIndex(index: Int) {
        onMain { c ->
            if (index in 0 until c.mediaItemCount) {
                c.seekToDefaultPosition(index)
                c.prepare()
                c.play()
                syncQueue(c)
            }
        }
    }

    fun removeQueueItem(index: Int) {
        onMain { c ->
            if (index in 0 until c.mediaItemCount && c.mediaItemCount > 1) {
                c.removeMediaItem(index)
                syncQueue(c)
                persistQueue(_queueTracks.value, c.currentMediaItemIndex.coerceAtLeast(0))
            }
        }
    }

    fun clearQueueAfterCurrent() {
        onMain { c ->
            val current = c.currentMediaItemIndex
            if (current >= 0 && c.mediaItemCount > current + 1) {
                c.removeMediaItems(current + 1, c.mediaItemCount)
                syncQueue(c)
                persistQueue(_queueTracks.value, current)
            }
        }
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

    fun setSleepTimer(minutes: Int) {
        val clean = minutes.coerceIn(1, 240)
        _sleepTimerEndMs.value = System.currentTimeMillis() + clean * 60_000L
    }

    fun clearSleepTimer() {
        _sleepTimerEndMs.value = 0L
    }

    private fun checkSleepTimer(c: MediaController) {
        val end = _sleepTimerEndMs.value
        if (end > 0L && System.currentTimeMillis() >= end) {
            _sleepTimerEndMs.value = 0L
            c.pause()
            persistState()
        }
    }

    private fun Track.toMediaItem(): MediaItem {
        val artUri = if (albumId > 0) {
            @Suppress("DEPRECATION")
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        } else {
            uri
        }
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setSubtitle(artist)
                    .setDescription(listOf(artist, album).filter { it.isNotBlank() }.joinToString(" • "))
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(albumArtist.ifBlank { artist })
                    .setArtworkUri(artUri)
                    .setTrackNumber(trackNumber)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}
