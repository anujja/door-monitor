package com.doormonitor.http

import android.util.Log
import com.doormonitor.core.CommandHandler
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Fully-Kiosk-style local HTTP control API. Runs inside the foreground service.
 *
 * Endpoints (all return JSON):
 *   GET /              human-friendly index of routes
 *   GET /status        device + battery status
 *   GET /screenOn      wake the screen (honors keepScreenOn/timeout)
 *   GET /screenOff     sleep the screen
 *   GET /wake          permanent wake
 *   GET /wake?seconds=30   temporary wake
 *   GET /sleep         alias for /screenOff
 *   GET /brightness?value=0..255   set brightness
 *   GET /reload        reload the dashboard
 *   GET /camera?id=front_door      open a camera full-screen
 *   GET /event?type=motion|doorbell  motion/doorbell wake (used by HA webhook)
 *
 * Optional password: when [passwordProvider] returns non-empty, requests must include it as
 * `?password=...` or an `Authorization: Bearer <password>` header.
 */
class LocalHttpServer(
    port: Int,
    private val handler: CommandHandler,
    private val passwordProvider: () -> String
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            val params = session.parameters // Map<String, List<String>>

            if (!isAuthorized(session, params)) {
                return json(Response.Status.UNAUTHORIZED, error("unauthorized"))
            }

            when (uri) {
                "/", "/index" -> json(Response.Status.OK, indexJson())
                "/status" -> json(Response.Status.OK, handler.statusJson())
                "/screenOn" -> { handler.screenOn(); ok("screenOn") }
                "/screenOff", "/sleep" -> { handler.screenOff(); ok("screenOff") }
                "/wake" -> {
                    val secs = params["seconds"]?.firstOrNull()?.toIntOrNull()
                    if (secs != null) handler.wakeFor(secs) else handler.wake()
                    ok("wake")
                }
                "/brightness" -> {
                    val v = params["value"]?.firstOrNull()?.toIntOrNull()
                        ?: return json(Response.Status.BAD_REQUEST, error("missing value"))
                    handler.setBrightness(v)
                    ok("brightness", JSONObject().put("value", v.coerceIn(0, 255)))
                }
                "/reload" -> { handler.reloadDashboard(); ok("reload") }
                "/camera" -> {
                    val id = params["id"]?.firstOrNull()
                        ?: return json(Response.Status.BAD_REQUEST, error("missing id"))
                    handler.showCamera(id)
                    ok("camera", JSONObject().put("id", id))
                }
                "/event" -> {
                    when (params["type"]?.firstOrNull()?.lowercase()) {
                        "doorbell" -> handler.doorbellWake()
                        else -> handler.motionWake()
                    }
                    ok("event")
                }
                else -> json(Response.Status.NOT_FOUND, error("unknown endpoint: $uri"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "request failed", t)
            json(Response.Status.INTERNAL_ERROR, error(t.message ?: "internal error"))
        }
    }

    private fun isAuthorized(session: IHTTPSession, params: Map<String, List<String>>): Boolean {
        val required = passwordProvider()
        if (required.isEmpty()) return true
        val fromQuery = params["password"]?.firstOrNull()
        val fromHeader = session.headers["authorization"]
            ?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        return required == fromQuery || required == fromHeader
    }

    private fun indexJson() = JSONObject().apply {
        put("app", "Door Monitor")
        put("endpoints", org.json.JSONArray(
            listOf(
                "/status", "/screenOn", "/screenOff", "/wake", "/wake?seconds=30",
                "/sleep", "/brightness?value=20", "/reload", "/camera?id=<id>",
                "/event?type=motion"
            )
        ))
    }

    private fun ok(action: String, extra: JSONObject? = null): Response {
        val body = JSONObject().put("status", "ok").put("action", action)
        extra?.keys()?.forEach { body.put(it, extra.get(it)) }
        return json(Response.Status.OK, body)
    }

    private fun error(message: String) =
        JSONObject().put("status", "error").put("message", message)

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-store")
        }

    fun startSafely() {
        runCatching { start(SOCKET_READ_TIMEOUT, false) }
            .onFailure { Log.e(TAG, "HTTP server failed to start on port $listeningPort", it) }
    }

    companion object {
        private const val TAG = "LocalHttpServer"
    }
}
