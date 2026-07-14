package com.doormonitor.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent, rotating on-disk log.
 *
 * logcat is useless for diagnosing this app's real failure mode: the device's ring buffer is a
 * few hundred KiB (hours, not days), it is wiped by a reboot, and the interesting failures are
 * *hangs* in a live process — nothing dies, so no crash log is ever produced. By the time anyone
 * notices the kiosk is stuck and force-closes it, every trace of the run-up is long gone.
 *
 * This writes to the app's private storage instead, so the evidence survives a force-close, an
 * app restart and a reboot. Two 512 KiB files are kept (~1 MiB total, days of heartbeats).
 *
 * Retrieve with:
 *   adb shell run-as com.doormonitor.debug cat files/logs/door-monitor.log
 */
object DiskLog {

    private const val TAG = "DiskLog"
    private const val MAX_BYTES = 512 * 1024L
    private const val FILE_NAME = "door-monitor.log"
    private const val PREV_NAME = "door-monitor.1.log"

    // Single writer thread: keeps file I/O off the main/render threads and serialises appends
    // without a lock on the caller's side.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiskLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var dir: File? = null

    /** Call once from Application.onCreate. */
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "logs")
        runCatching { d.mkdirs() }
        dir = d
        i(TAG, "--- log opened (pid=${android.os.Process.myPid()}) ---")
    }

    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String) = write('W', tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    private fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        // Mirror to logcat so live `adb logcat` still works while debugging.
        when (level) {
            'W' -> Log.w(tag, msg)
            'E' -> Log.e(tag, msg, t)
            else -> Log.i(tag, msg)
        }
        val now = Date()
        val trace = t?.stackTraceToString()
        io.execute {
            val d = dir ?: return@execute
            runCatching {
                val f = File(d, FILE_NAME)
                if (f.length() > MAX_BYTES) rotate(d, f)
                val line = buildString {
                    append(synchronized(stamp) { stamp.format(now) })
                    append(' ').append(level).append('/').append(tag).append(": ").append(msg)
                    append('\n')
                    if (trace != null) append(trace).append('\n')
                }
                f.appendText(line)
            }
        }
    }

    private fun rotate(d: File, current: File) {
        runCatching {
            val prev = File(d, PREV_NAME)
            if (prev.exists()) prev.delete()
            current.renameTo(prev)
        }
    }
}
