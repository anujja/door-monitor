package com.doormonitor.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.doormonitor.core.DiskLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "RemoteImage"

/** Longest side to decode to; MyGate's visitor photos come straight off the guard's phone. */
private const val MAX_SIDE_PX = 720

private val client by lazy {
    OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
}

/**
 * Downloads [url] once per URL and returns it as an image, or null while loading or on failure.
 * Only for the occasional alert photo, so there is no cache.
 */
@Composable
fun rememberRemoteImage(url: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { load(it) }
    }
    return image
}

private suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val bytes = resp.body!!.bytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }
    }.onFailure { DiskLog.w(TAG, "could not load image: ${it.message}") }.getOrNull()
}
