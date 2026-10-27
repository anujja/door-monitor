package com.doormonitor.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import com.doormonitor.R

/**
 * An audible alarm on demand (MQTT `command/alarm`, HTTP `/alarm`): loops the system alarm tone
 * on the alarm stream, at whatever alarm volume is set on the tablet, then stops by itself. Used for heads-ups that must be heard across the flat, e.g. the school bus passing a
 * landmark a few minutes before the stop.
 */
class AlarmSounder(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val stopRunnable = Runnable { stop() }

    /** Sound the alarm for [seconds]; a repeat call restarts the countdown. */
    fun start(seconds: Int = DEFAULT_SECONDS) {
        main.post {
            main.removeCallbacks(stopRunnable)
            if (player == null) play()
            main.postDelayed(stopRunnable, seconds.coerceIn(1, MAX_SECONDS) * 1000L)
        }
    }

    fun stop() {
        main.post {
            main.removeCallbacks(stopRunnable)
            player?.let { p -> runCatching { p.stop(); p.release() } }
            player = null
        }
    }

    private fun play() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching {
            val tone = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                if (tone != null) {
                    setDataSource(appContext, tone)
                } else {
                    val afd = appContext.resources.openRawResourceFd(R.raw.mygate_chime)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { DiskLog.e(TAG, "alarm failed", it) }
    }

    companion object {
        private const val TAG = "AlarmSounder"
        const val DEFAULT_SECONDS = 20
        const val MAX_SECONDS = 300
    }
}
