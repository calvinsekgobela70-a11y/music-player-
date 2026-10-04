package com.ceecept.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
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
