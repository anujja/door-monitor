package com.doormonitor.ha

import android.util.Log
import com.doormonitor.data.AppSettings
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin Home Assistant outbound client.
 *
 * Authentication reuse: the dashboard WebView keeps the HA login *cookie* session for the
 * UI, while this client uses the configured long-lived access token for server-to-server
 * calls (firing events, posting to webhooks, reading state). One sign-in, no extra login.
 *
 * Used for:
 *  - fireEvent: let HA automations react to tablet events (e.g. battery warning).
 *  - callWebhook: trigger HA webhook automations directly.
 */
class HomeAssistantClient(
    private val settingsProvider: () -> AppSettings
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** POST an arbitrary JSON body to an HA webhook id (no auth needed for webhooks). */
    fun callWebhook(webhookId: String, jsonBody: String = "{}") {
        val base = settingsProvider().haBaseUrl.trimEnd('/')
        if (base.isEmpty()) return
        val req = Request.Builder()
            .url("$base/api/webhook/$webhookId")
            .post(jsonBody.toRequestBody(jsonMedia))
            .build()
        execute(req, "webhook:$webhookId")
    }

    /** Fire a custom event into HA's event bus using the long-lived token. */
    fun fireEvent(eventType: String, jsonBody: String = "{}") {
        val s = settingsProvider()
        val base = s.haBaseUrl.trimEnd('/')
        if (base.isEmpty() || s.haLongLivedToken.isEmpty()) return
        val req = Request.Builder()
            .url("$base/api/events/$eventType")
            .header("Authorization", "Bearer ${s.haLongLivedToken}")
            .post(jsonBody.toRequestBody(jsonMedia))
            .build()
        execute(req, "event:$eventType")
    }

    private fun execute(req: Request, tag: String) {
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "HA call failed ($tag): ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) Log.w(TAG, "HA call ($tag) -> HTTP ${it.code}")
                }
            }
        })
    }

    companion object {
        private const val TAG = "HomeAssistantClient"
    }
}
