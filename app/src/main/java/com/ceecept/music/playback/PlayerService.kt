package com.ceecept.music.playback

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
        val renderers = CeeceptRenderersFactory(this, app.engine)
        val exo = ExoPlayer.Builder(this, renderers)
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
        player = exo
        session = MediaSession.Builder(this, exo).build()
        addSession(session!!)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Play audio files opened / shared into Ceecept.
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { playExternalUri(it) }
        }
        return super.onStartCommand(intent, flags, startId)
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
        session = null
        player = null
        super.onDestroy()
    }
}
