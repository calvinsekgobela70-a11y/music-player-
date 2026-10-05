package com.ceecept.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ceecept.music.CeeceptApp
import com.ceecept.music.R
import com.ceecept.music.audio.CeeceptRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Background playback service. Owns the ExoPlayer instance wired with the
 * Ceecept DSP chain, exposes it through a MediaSession (notification,
 * headset / Bluetooth / Android Auto controls).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    private companion object {
        const val NOTIFICATION_ID = 7100
        const val NOTIFICATION_CHANNEL_ID = "ceecept_playback"
        const val NOTIFICATION_CHANNEL_NAME = "Ceecept playback"
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceHandler = Handler(Looper.getMainLooper())
    private var deckPlayer: ExoPlayer? = null
    private var deckCurrentMediaId: String? = null
    private var deckTargetIndex: Int = -1
    private var mixCurrentMediaId: String? = null
    private var mixStartPositionMs: Long = 0L
    private var mixWindowMs: Long = 0L
    private var mixFinalizing = false
    private val crossfadeTicker = object : Runnable {
        override fun run() {
            runCatching { tickCrossfadeDeck() }.onFailure {
                com.ceecept.music.CrashReporter.recordSoft(this@PlayerService, "PlayerService.crossfade", it)
                cancelCrossfadeDeck(restorePrimaryVolume = true)
            }
            serviceHandler.postDelayed(this, 90L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            configureNotificationProvider()
            startEngine()
        } catch (e: Exception) {
            // Never kill the whole app if the playback engine fails to start;
            // record it so the crash-report dialog can show the cause.
            com.ceecept.music.CrashReporter.recordSoft(this, "PlayerService.onCreate", e)
            stopSelf()
        }
    }

    private fun configureNotificationProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Playback controls for Ceecept"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.notif_channel_name)
            .build()
        provider.setSmallIcon(R.drawable.ic_stat_ceecept)
        setMediaNotificationProvider(provider)
    }

    private fun startEngine() {
        val app = applicationContext as CeeceptApp
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ceecept:PlaybackDSP").apply {
            setReferenceCounted(false)
        }
        val renderers = CeeceptRenderersFactory(this, app.engine)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (app.history.gaplessPreload) 32_000 else 12_000,
                if (app.history.gaplessPreload) 90_000 else 45_000,
                1_200,
                2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val exo = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.setForegroundMode(app.history.keepNotification)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                enrichNotificationArtwork(app, exo, mediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackWakeLock(exo)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlaybackWakeLock(exo)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlaybackWakeLock(exo)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Surface playback failures in the in-app crash report so a
                // silent stop is diagnosable from the device.
                com.ceecept.music.CrashReporter.recordSoft(
                    this@PlayerService, "ExoPlayer.error(${error.errorCode})", error
                )
            }
        })
        player = exo
        serviceHandler.removeCallbacks(crossfadeTicker)
        serviceHandler.post(crossfadeTicker)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.ceecept.music.MainActivity::class.java)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .setShowPlayButtonIfPlaybackIsSuppressed(true)
            .setMediaButtonPreferences(notificationButtonPreferences())
            .build()
        addSession(session!!)
    }

    private fun notificationButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()
    )

    private fun enrichNotificationArtwork(app: CeeceptApp, exo: ExoPlayer, item: MediaItem?) {
        val mediaItem = item ?: return
        if (mediaItem.mediaMetadata.artworkData != null) return
        val id = mediaItem.mediaId.toLongOrNull() ?: return
        val track = app.repository.findById(id) ?: return
        app.applicationScope.launch(Dispatchers.IO) {
            val bitmap = app.repository.artwork(track, 512) ?: return@launch
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.toByteArray()
            }
            withContext(Dispatchers.Main) {
                val liveIndex = exo.currentMediaItemIndex
                val liveItem = exo.currentMediaItem ?: return@withContext
                if (liveItem.mediaId != mediaItem.mediaId || liveItem.mediaMetadata.artworkData != null) return@withContext
                val metadata = liveItem.mediaMetadata.buildUpon()
                    .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
                exo.replaceMediaItem(liveIndex, liveItem.buildUpon().setMediaMetadata(metadata).build())
            }
        }
    }

    private fun updatePlaybackWakeLock(exo: ExoPlayer) {
        val app = applicationContext as CeeceptApp
        val shouldHold = exo.playWhenReady &&
            exo.playbackState != Player.STATE_IDLE &&
            exo.playbackState != Player.STATE_ENDED
        exo.setForegroundMode(shouldHold || app.history.keepNotification)
        val lock = wakeLock ?: return
        if (shouldHold) {
            if (!lock.isHeld) lock.acquire()
        } else if (lock.isHeld) {
            lock.release()
        }
    }

    /**
     * True overlap crossfade deck.
     *
     * Media3/ExoPlayer's normal playlist handoff is gapless, but not overlapping.
     * For the audible DJ/Poweramp-style fade the service keeps a tiny second ExoPlayer
     * prepared for the next item, fades it in with an equal-power curve, fades the
     * session player down, then seeks the session player to the same next-track
     * position and releases the deck. The MediaSession remains the official player,
     * so notification/headset controls stay intact.
     */
    private fun tickCrossfadeDeck() {
        val exo = player ?: return
        val app = applicationContext as CeeceptApp
        val enabled = app.history.crossfadeEnabled || app.history.djMode
        if (!enabled || exo.mediaItemCount <= 1 || !exo.isPlaying) {
            if (!exo.isPlaying) deckPlayer?.pause()
            if (!enabled) cancelCrossfadeDeck(restorePrimaryVolume = true)
            return
        }
        val currentIndex = exo.currentMediaItemIndex
        val nextIndex = currentIndex + 1
        if (currentIndex < 0 || nextIndex >= exo.mediaItemCount) {
            cancelCrossfadeDeck(restorePrimaryVolume = true)
            return
        }
        val currentId = exo.currentMediaItem?.mediaId ?: return
        val nextItem = exo.getMediaItemAt(nextIndex)
        val nextId = nextItem.mediaId
        if (!mixFinalizing && deckPlayer != null &&
            (mixCurrentMediaId != currentId || deckCurrentMediaId != nextId || deckTargetIndex != nextIndex)
        ) {
            cancelCrossfadeDeck(restorePrimaryVolume = true)
        }
        val duration = exo.duration.takeIf { it > 0L } ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val window = crossfadeWindowMs(app, currentId, nextId, duration)
        if (window <= 0L || duration < window + 4_000L) return
        val startAt = crossfadeStartMs(app, currentId, duration, window)

        if (mixCurrentMediaId == currentId && deckCurrentMediaId == nextId && deckTargetIndex == nextIndex) {
            updateCrossfadeProgress(exo, window)
            return
        }

        if (position >= startAt && duration - position <= window + 1_000L) {
            startCrossfadeDeck(app, exo, nextItem, currentId, nextId, nextIndex, position, window)
        }
    }

    private fun startCrossfadeDeck(
        app: CeeceptApp,
        exo: ExoPlayer,
        nextItem: MediaItem,
        currentId: String,
        nextId: String,
        nextIndex: Int,
        position: Long,
        window: Long
    ) {
        if (mixFinalizing) return
        cancelCrossfadeDeck(restorePrimaryVolume = false)
        val deck = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        deck.volume = 0f
        deck.setMediaItem(nextItem)
        deck.prepare()
        deck.play()
        deckPlayer = deck
        deckCurrentMediaId = nextId
        deckTargetIndex = nextIndex
        mixCurrentMediaId = currentId
        mixStartPositionMs = position
        mixWindowMs = window
        mixFinalizing = false
        app.engine.dj.setTransition(
            enabled = true,
            mode = com.ceecept.music.audio.DjTransitionProcessor.MODE_OUTRO,
            progress = 0f,
            bpm = app.history.djAnalysis(currentId.toLongOrNull() ?: -1L)?.bpm ?: 120f,
            type = com.ceecept.music.audio.DjTransitionProcessor.TYPE_LONG_BLEND,
            overlapIntensity = 1f
        )
        updateCrossfadeProgress(exo, window)
    }

    private fun updateCrossfadeProgress(exo: ExoPlayer, window: Long) {
        val deck = deckPlayer ?: return
        if (!deck.isPlaying) deck.play()
        val elapsed = (exo.currentPosition - mixStartPositionMs).coerceAtLeast(0L)
        val p = (elapsed.toFloat() / window.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val e = p * p * (3f - 2f * p)
        exo.volume = cos(e * PI / 2.0).toFloat().coerceIn(0.02f, 1f)
        deck.volume = sin(e * PI / 2.0).toFloat().coerceIn(0f, 1f)
        val app = applicationContext as CeeceptApp
        app.engine.dj.setTransition(
            enabled = true,
            mode = com.ceecept.music.audio.DjTransitionProcessor.MODE_OUTRO,
            progress = p,
            bpm = app.history.djAnalysis(mixCurrentMediaId?.toLongOrNull() ?: -1L)?.bpm ?: 120f,
            type = com.ceecept.music.audio.DjTransitionProcessor.TYPE_LONG_BLEND,
            overlapIntensity = 1f
        )
        if (!mixFinalizing && (p >= 0.985f || exo.duration - exo.currentPosition <= 180L)) {
            finishCrossfadeDeck(exo, deck)
        }
    }

    private fun finishCrossfadeDeck(exo: ExoPlayer, deck: ExoPlayer) {
        val targetIndex = deckTargetIndex
        if (targetIndex !in 0 until exo.mediaItemCount) {
            cancelCrossfadeDeck(restorePrimaryVolume = true)
            return
        }
        mixFinalizing = true
        val deckPosition = deck.currentPosition.coerceAtLeast(0L)
        exo.volume = 0f
        exo.seekTo(targetIndex, deckPosition)
        exo.play()
        serviceHandler.postDelayed({
            val live = player ?: return@postDelayed
            live.volume = 1f
            cancelCrossfadeDeck(restorePrimaryVolume = false)
            val app = applicationContext as CeeceptApp
            app.engine.dj.setTransition(
                enabled = false,
                mode = com.ceecept.music.audio.DjTransitionProcessor.MODE_NONE,
                progress = 0f,
                bpm = 120f
            )
        }, 320L)
    }

    private fun cancelCrossfadeDeck(restorePrimaryVolume: Boolean) {
        deckPlayer?.runCatchingRelease()
        deckPlayer = null
        deckCurrentMediaId = null
        deckTargetIndex = -1
        mixCurrentMediaId = null
        mixStartPositionMs = 0L
        mixWindowMs = 0L
        mixFinalizing = false
        if (restorePrimaryVolume) player?.volume = 1f
    }

    private fun ExoPlayer.runCatchingRelease() {
        try {
            stop()
        } catch (e: Exception) {
        }
        try {
            release()
        } catch (e: Exception) {
        }
    }

    private fun crossfadeWindowMs(app: CeeceptApp, currentId: String, nextId: String, durationMs: Long): Long {
        val base = (app.history.crossfadeSeconds * 1000L).coerceIn(3_000L, 24_000L)
        val current = currentId.toLongOrNull()?.let { app.history.djAnalysis(it) }
        val next = nextId.toLongOrNull()?.let { app.history.djAnalysis(it) }
        val djWindow = if (app.history.djMode && current != null && next != null && current.mixable && next.mixable) {
            val diff = absTempoDiff(current.bpm, next.bpm)
            val beat = current.beatMs.takeIf { it > 0L }
                ?: (60_000f / current.bpm.coerceIn(70f, 190f)).toLong()
            val harmonic = harmonicScore(current.key, current.minor, next.key, next.minor) >= 0.72f
            val bars = when {
                harmonic && diff <= 4f -> 16L
                harmonic && diff <= 8f -> 12L
                diff <= 5f -> 8L
                else -> 4L
            }
            (beat * 4L * bars).coerceIn(6_000L, 28_000L)
        } else {
            base
        }
        return minOf(maxOf(base, djWindow), (durationMs * 0.30f).toLong().coerceAtLeast(3_000L))
    }

    private fun crossfadeStartMs(app: CeeceptApp, currentId: String, durationMs: Long, windowMs: Long): Long {
        val analysisStart = currentId.toLongOrNull()
            ?.let { app.history.djAnalysis(it) }
            ?.outroStartMs
            ?.takeIf { it > 0L && it < durationMs - 1_500L }
        val defaultStart = (durationMs - windowMs).coerceAtLeast(0L)
        return (analysisStart ?: defaultStart).coerceIn(0L, (durationMs - 1_500L).coerceAtLeast(0L))
    }

    private fun absTempoDiff(a: Float, b: Float): Float {
        val direct = kotlin.math.abs(a - b)
        val half = kotlin.math.abs(a - b * 0.5f)
        val double = kotlin.math.abs(a - b * 2f)
        return minOf(direct, half, double)
    }

    private fun harmonicScore(aKey: Int, aMinor: Boolean, bKey: Int, bMinor: Boolean): Float {
        val raw = kotlin.math.abs((aKey - bKey) % 12)
        val dist = minOf(raw, 12 - raw)
        return when {
            aKey == bKey && aMinor == bMinor -> 1f
            aKey == bKey && aMinor != bMinor -> 0.86f
            dist == 1 && aMinor == bMinor -> 0.78f
            dist == 2 && aMinor == bMinor -> 0.58f
            else -> 0.28f
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Play audio files opened / shared into Ceecept.
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { playExternalUri(it) }
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = applicationContext as CeeceptApp
        val exo = player
        if (exo != null && (exo.isPlaying || app.history.keepNotification)) {
            // Keep the MediaSession/notification alive when the recents card is swiped away.
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun playExternalUri(uri: Uri) {
        val exo = player ?: return
        var title = "Audio file"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) title = it.getString(0) ?: title
            }
        } catch (e: Exception) {
        }
        val item = MediaItem.Builder()
            .setMediaId("ext:${uri}")
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist("External file").build()
            )
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.play()
    }

    override fun onDestroy() {
        serviceHandler.removeCallbacks(crossfadeTicker)
        cancelCrossfadeDeck(restorePrimaryVolume = false)
        session?.run {
            player.release()
            release()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        session = null
        player = null
        super.onDestroy()
    }
}
