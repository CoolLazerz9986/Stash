// Path: android/app/src/main/java/app/stash/android/Downloader.kt
package app.stash.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves a file from the server into Downloads/Stash. We do the HTTP ourselves (instead of the system
 * DownloadManager) because the zip download is a POST, and so the website's login cookie and cleartext
 * rules are applied exactly like they are in the WebView. [start] queues the work in [DownloadService].
 */
object Downloader {
    class Job(val url: String, val method: String, val form: String?, val cookie: String?, val fallbackName: String) {
        @Volatile var cancelled = false
            private set
        @Volatile var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            val c = connection ?: return
            Thread { c.disconnect() }.start() // unblocks a pending read; kept off the main thread
        }
    }

    class Saved(val name: String, val uri: Uri, val mime: String)

    class Cancelled : IOException("cancelled")

    fun start(ctx: Context, url: String, method: String, form: String?, cookie: String?, fallbackName: String) {
        val app = ctx.applicationContext
        val i = Intent(app, DownloadService::class.java)
            .putExtra(DownloadService.X_URL, url)
            .putExtra(DownloadService.X_METHOD, method)
            .putExtra(DownloadService.X_FORM, form)
            .putExtra(DownloadService.X_COOKIE, cookie)
            .putExtra(DownloadService.X_NAME, fallbackName)
        try {
            app.startForegroundService(i)
        } catch (e: Exception) {
            Events.post(Events.Msg("download", Events.Kind.ERROR, "Download could not start: ${e.message}"))
        }
    }

    /** Blocking: call from a worker thread. [onProgress] gets (bytes done, total or -1 if unknown). */
    fun run(ctx: Context, job: Job, onName: (String) -> Unit, onProgress: (Long, Long) -> Unit): Saved {
        val c = URL(job.url).openConnection() as HttpURLConnection
        job.connection = c
        try {
            if (job.cancelled) throw Cancelled()
            c.requestMethod = job.method
            c.connectTimeout = 10_000
            c.readTimeout = 600_000 // a big zip is built by the server before the first byte is sent
            c.instanceFollowRedirects = false
            if (!job.cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", job.cookie)
            if (job.form != null) {
                val bytes = job.form.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            if (code != 200) throw IOException(if (code == 401) "please log in again" else "HTTP $code")
            val name = fileName(c.getHeaderField("Content-Disposition")) ?: job.fallbackName
            val mime = (c.contentType ?: "application/octet-stream").substringBefore(';').trim()
            onName(name)
            val uri = save(ctx, c.inputStream, name, mime, job, c.contentLengthLong, onProgress)
            return Saved(name, uri, mime)
        } finally {
            job.connection = null
            c.disconnect()
        }
    }

    private fun save(
        ctx: Context, input: InputStream, name: String, mime: String,
        job: Job, total: Long, onProgress: (Long, Long) -> Unit,
    ): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Stash")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create the file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("could not open the file for writing")
            out.use { o ->
                input.use { src ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (job.cancelled) throw Cancelled()
                        val n = src.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    if (job.cancelled) throw Cancelled()
                    if (total >= 0 && done != total) throw IOException("the download ended early")
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Parses `attachment; filename="x"; filename*=UTF-8''x%20y` (what server.py sends). */
    fun fileName(contentDisposition: String?): String? {
        if (contentDisposition == null) return null
        val raw =
            Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)
                ?.let { Uri.decode(it.groupValues[1].trim()) }
                ?: Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentDisposition)
                    ?.groupValues?.get(1)?.trim()
                ?: return null
        return raw.replace(Regex("[\\\\/]"), "_").ifEmpty { null }
    }
}
