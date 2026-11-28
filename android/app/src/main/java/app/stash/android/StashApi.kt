// Path: android/app/src/main/java/app/stash/android/StashApi.kt
package app.stash.android

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The local file could not be read (deleted, permission revoked, changed while uploading). Not a network problem. */
class SourceException(message: String) : Exception(message)

/** The work was stopped by the system or the user while a file was being read/uploaded. */
class UploadCancelled : Exception("cancelled")

/** Thin client for the Stash server's HTTP API (see API.md). Blocking: call it from a background thread. */
object StashApi {
    class ApiException(val status: Int, message: String) : IOException(message)

    data class Session(val authRequired: Boolean, val authed: Boolean, val maxBytes: Long, val api: Int)

    /** [sha256] = SHA-256 of the newest chat database the server holds ("" if none), [maxBytes] = its chat size limit. */
    data class ChatStatus(val sha256: String, val maxBytes: Long)

    private fun conn(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 30_000,
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8_000
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = false
        c.useCaches = false
        for ((k, v) in headers) c.setRequestProperty(k, v)
        return c
    }

    private fun bodyOf(c: HttpURLConnection): String {
        val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun errorText(body: String, fallback: String): String =
        try {
            JSONObject(body).optString("error", fallback).ifEmpty { fallback }
        } catch (e: JSONException) {
            fallback
        }

    private fun auth(key: String): Map<String, String> =
        if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $key")

    /** Also the "is this really a Stash server?" check used by the setup screen. */
    fun session(base: String): Session {
        val c = conn("$base/api/session", "GET")
        try {
            val code = c.responseCode
            val body = bodyOf(c)
            if (code != 200) throw ApiException(code, "That doesn't look like a Stash server (HTTP $code)")
            val j = try { JSONObject(body) } catch (e: JSONException) { throw ApiException(0, "That doesn't look like a Stash server") }
            if (!j.has("authRequired")) throw ApiException(0, "That doesn't look like a Stash server")
            return Session(
                authRequired = j.getBoolean("authRequired"),
                authed = j.optBoolean("authed"),
                maxBytes = j.optLong("maxBytes"),
                api = j.optInt("api", 1),
            )
        } finally {
            c.disconnect()
        }
    }

    /** Needs a logged-in website session (the WebView's cookie). Returns the API key for background use. */
    fun fetchAppKey(base: String, cookie: String): String {
        val c = conn("$base/api/app-key", "GET", mapOf("Cookie" to cookie))
        try {
            val code = c.responseCode
            val body = bodyOf(c)
            when (code) {
                200 -> return try { JSONObject(body).optString("key") } catch (e: JSONException) { throw ApiException(0, "Bad response from server") }
                401 -> throw ApiException(401, "Log in to Stash in the app first, then try again")
                404 -> throw ApiException(404, "This server is too old: replace server.py with the updated version")
                else -> throw ApiException(code, errorText(body, "HTTP $code"))
            }
        } finally {
            c.disconnect()
        }
    }

    /** Does the server already hold a file with this SHA-256? */
    /**
     * PUT /api/files/by-hash/<sha256>/thumb: stores a small JPEG preview for the file with that SHA-256. Best effort: the caller
     * ignores every failure, because a missing preview only means the website loads the original once.
     */
    fun putThumb(base: String, key: String, sha256: String, jpeg: ByteArray) {
        val c = conn("$base/api/files/by-hash/$sha256/thumb", "PUT", auth(key) + ("Content-Type" to "image/jpeg"))
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(jpeg.size)
            c.outputStream.use { it.write(jpeg) }
            val code = c.responseCode
            if (code != 201) throw ApiException(code, errorText(bodyOf(c), "HTTP $code"))
        } finally {
            c.disconnect()
        }
    }

    fun exists(base: String, key: String, sha256: String): Boolean {
        val c = conn("$base/api/files/by-hash/$sha256", "GET", auth(key))
        try {
            val code = c.responseCode
            val body = bodyOf(c)
            if (code != 200) throw ApiException(code, errorText(body, "HTTP $code"))
            return try { JSONObject(body).optBoolean("exists", false) } catch (e: JSONException) { throw ApiException(0, "Bad response from server") }
        } finally {
            c.disconnect()
        }
    }

    /** GET /api/chats/status: what chat database the server holds now, and its size limit. 404 = server too old for chats. */
    fun chatStatus(base: String, key: String): ChatStatus {
        val c = conn("$base/api/chats/status", "GET", auth(key))
        try {
            val code = c.responseCode
            val body = bodyOf(c)
            if (code == 404) throw ApiException(404, "This server is too old for chat backup: update server.py, chats.py and chats.html")
            if (code != 200) throw ApiException(code, errorText(body, "HTTP $code"))
            val j = try { JSONObject(body) } catch (e: JSONException) { throw ApiException(0, "Bad response from server") }
            return ChatStatus(j.optJSONObject("snapshot")?.optString("sha256") ?: "", j.optLong("maxBytes"))
        } finally {
            c.disconnect()
        }
    }

    /**
     * Streams one file to POST [path] (default /api/upload; the chat backup uses /api/chats/import).
     * [length] and [sha256] must describe exactly the bytes [open] yields; the server re-hashes the upload
     * and rejects it (422) if they differ, so a corrupted backup can't be stored.
     * [readTimeoutMs]: how long to wait for the server's answer after the last byte (the chat import indexes first).
     * [source]: optional X-Source tag stored with the file ("whatsapp" for chat media); empty = untagged.
     */
    fun upload(
        base: String,
        key: String,
        name: String,
        mime: String,
        length: Long,
        sha256: String,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
        path: String = "/api/upload",
        readTimeoutMs: Int = 120_000,
        source: String = "",
    ) {
        val headers = HashMap<String, String>(auth(key))
        headers["X-Filename"] = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        headers["Content-Type"] = mime.ifEmpty { "application/octet-stream" }
        headers["X-Sha256"] = sha256
        if (source.isNotEmpty()) headers["X-Source"] = source
        val c = conn("$base$path", "POST", headers, readTimeoutMs = readTimeoutMs)
        c.doOutput = true
        c.setFixedLengthStreamingMode(length)
        try {
            try {
                c.outputStream.use { out ->
                    open().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            if (cancelled()) throw UploadCancelled()
                            val n = try {
                                input.read(buf)
                            } catch (e: IOException) {
                                throw SourceException("could not read the file: ${e.message}")
                            }
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            onProgress(sent)
                        }
                        if (sent != length) throw SourceException("the file changed while uploading")
                    }
                }
            } catch (e: IOException) {
                // The server may have answered early (413 too big, 507 disk full) and closed the socket.
                val code = try { c.responseCode } catch (x: Exception) { -1 }
                if (code >= 400) throw ApiException(code, errorText(bodyOf(c), "HTTP $code"))
                throw e
            }
            val code = c.responseCode
            val body = bodyOf(c)
            if (code != 201) throw ApiException(code, errorText(body, "HTTP $code"))
        } finally {
            c.disconnect()
        }
    }
}
