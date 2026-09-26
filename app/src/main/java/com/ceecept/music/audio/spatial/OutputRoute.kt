package com.ceecept.music.audio.spatial

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * §11.2 `detect_playback_capabilities()` for Android.
 *
 * Inspects the real output routing so the renderer can pick a strategy: headphones get
 * the binauralised 7.1.4 virtual rig, an HDMI/USB multichannel sink gets discrete
 * surround, and the phone's own speaker falls back to the enhanced stereo path.
 */
class OutputRouteDetector(context: Context) {

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var callback: AudioDeviceCallback? = null

    fun detect(): PlaybackCapabilities {
        val am = audioManager ?: return PlaybackCapabilities()
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return PlaybackCapabilities()

        var headphones = false
        var maxChannels = 2
        var multichannelSink = false
        var sampleRate = 48000

        for (device in devices) {
            if (isHeadphone(device)) headphones = true
            val channels = device.channelCounts
            if (channels != null) {
                for (c in channels) if (c > maxChannels) maxChannels = c
            }
            val rates = device.sampleRates
            if (rates != null) {
                for (r in rates) if (r in 44100..192000 && r > sampleRate) sampleRate = r
            }
            if (isMultichannelSink(device)) multichannelSink = true
        }

        val speakerCount = if (headphones) 2 else if (multichannelSink) maxChannels else 2
        return PlaybackCapabilities(
            sampleRate = sampleRate,
            numChannels = maxChannels,
            speakerCount = speakerCount,
            hasSubwoofer = speakerCount >= 6,
            hasHeightSpeakers = speakerCount >= 8,
            isHeadphones = headphones
        )
    }

    /** Re-detect whenever a device is plugged in or removed. */
    fun observe(onChange: (PlaybackCapabilities) -> Unit) {
        val am = audioManager ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                onChange(detect())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                onChange(detect())
            }
        }
        callback = cb
        runCatching { am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
        onChange(detect())
    }

    fun stop() {
        val am = audioManager ?: return
        callback?.let { runCatching { am.unregisterAudioDeviceCallback(it) } }
        callback = null
    }

    private fun isHeadphone(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID -> true

        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun isMultichannelSink(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_DOCK -> true

        else -> false
    }
}
