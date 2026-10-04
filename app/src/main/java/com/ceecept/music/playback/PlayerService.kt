package com.ceecept.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ceecept.music.CeeceptApp
import com.ceecept.music.audio.CeeceptRenderersFactory

/**
 * Background playback service. Owns the ExoPlayer instance wired with the
 * Ceecept DSP chain, exposes it through a MediaSession (notification,
 * headset / Bluetooth / Android Auto controls).
 */
class PlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startEngine()
        } catch (e: Exception) {
            // Never kill the whole app if the playback engine fails to start;
            // record it so the crash-report dialog can show the cause.
            com.ceecept.music.CrashReporter.recordSoft(this, "PlayerService.onCreate", e)
            stopSelf()
        }
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
            .build()
        addSession(session!!)
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
