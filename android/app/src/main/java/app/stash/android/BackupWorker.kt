// Path: android/app/src/main/java/app/stash/android/BackupWorker.kt
package app.stash.android

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One backup pass: find phone photos/videos that are not on the server yet and upload them (one way, nothing is
 * ever deleted from the phone). Safe to stop at any moment: every finished file is recorded in [BackupDb], so the
 * next run carries on with what is left. A file that was interrupted mid-upload starts again from its beginning
 * (the server has no resumable uploads yet).
 *
 * Per file:  hash on the phone -> ask the server if it already has that hash -> if not, upload with X-Sha256
 * so the server verifies the bytes it received.
 *
 * Files bigger than the server's upload limit are counted ("too big") but never recorded in [BackupDb], so they are
 * picked up automatically once the limit is raised. They are detected from the MediaStore size before hashing,
 * so an hourly run does not re-read them.
 *
 * Notifications (progress with a real percentage, problems) are built by [Notify].
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val prefs = Prefs(applicationContext)
    private val db = BackupDb(applicationContext)
    private var lastTick = 0L
    private var foreground = false // setForeground worked, so the notification may be refreshed

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // the periodic job and a one-off run must never overlap (they would upload the same file twice)
        if (!RUNNING.compareAndSet(false, true)) return@withContext Result.success()
        try {
            backup()
        } finally {
            RUNNING.set(false)
        }
    }

    private suspend fun backup(): Result {
        if (prefs.serverUrl.isEmpty()) return Result.success()
        // a scheduled run needs the schedule on and not cancelled for today; "Back up now" needs neither
        if (!inputData.getBoolean(MANUAL, false) &&
            (!prefs.backupEnabled || System.currentTimeMillis() < prefs.pausedUntil)
        ) return Result.success()
        if (prefs.fileTrees.isEmpty()) return stop("Choose at least one folder to back up")

        val base = prefs.serverUrl
        val session = try {
            StashApi.session(base)
        } catch (e: IOException) {
            prefs.status = "Can't reach the server (${e.message}). Will retry."
            return Result.retry()
        }
        if (session.api < 2) return stop("The server needs the updated server.py")
        if (session.authRequired && prefs.apiKey.isEmpty()) {
            return stop("Not connected: tap Back up now while logged in", alert = true)
        }
        val limit = session.maxBytes

        prefs.status = "Looking for new files…"
        val todo = try {
            FileSource.list(applicationContext, prefs) { isStopped }.filter { !db.isDone(it.uri.toString(), it.size, it.modified) }
        } catch (e: UploadCancelled) {
            return Result.retry()
        } catch (e: SecurityException) {
            return stop("Access to a folder was lost. Remove it and add it again.", alert = true)
        } catch (e: Exception) {
            return stop("Can't read a backup folder: ${e.message}")
        }
        if (todo.isEmpty()) return finish(0, 0, 0, 0, 0, limit, "")

        var uploaded = 0
        var present = 0
        var skipped = 0
        var failed = 0
        var tooBig = 0
        var lastProblem = ""
        for ((i, item) in todo.withIndex()) {
            if (isStopped) return Result.retry()
            // over the server limit: count it, do not hash it, do not record it (retried after the limit is raised)
            if (limit > 0 && item.size > limit) { tooBig++; continue }
            progress("Backing up ${i + 1} of ${todo.size}: ${item.name}", i * 100 / todo.size)
            val key = item.uri.toString()
            try {
                val (sha, bytes) = hash(item.uri)
                if (bytes == 0L) {
                    db.mark(key, item.size, item.modified, sha, "skipped"); skipped++; continue
                }
                if (limit in 1 until bytes) { // the file grew after the folder reported its size
                    tooBig++; continue
                }
                if (StashApi.exists(base, prefs.apiKey, sha)) {
                    db.mark(key, item.size, item.modified, sha, "present"); present++; continue
                }
                StashApi.upload(
                    base, prefs.apiKey, item.name, item.mime, bytes, sha,
                    open = { open(item.uri) },
                    onProgress = { sent -> tick(item, sent, bytes, i, todo.size) },
                    cancelled = { isStopped },
                )
                preview(base, item.uri, item.mime, sha)
                db.mark(key, item.size, item.modified, sha, "uploaded"); uploaded++
            } catch (e: UploadCancelled) {
                return Result.retry()
            } catch (e: SourceException) {
                failed++; lastProblem = "${item.name}: ${e.message}"
            } catch (e: StashApi.ApiException) {
                when (e.status) {
                    401, 403 -> return stop("The server rejected the key: tap Back up now while logged in", alert = true)
                    429 -> { prefs.status = "The server is rate limiting this phone. Will retry."; return Result.retry() }
                    507 -> return stop("The server's disk is full", alert = true)
                    413 -> tooBig++ // not recorded: tried again next run, in case the limit was raised meanwhile
                    else -> { failed++; lastProblem = "${item.name}: ${e.message}" }
                }
            } catch (e: IOException) {
                // Either the connection dropped, or the server refused this upload and hung up (too big, disk full...).
                // Only give up on the whole run if the server really is unreachable; otherwise blame this one file.
                val reachable = try { StashApi.session(base); true } catch (x: IOException) { false }
                if (!reachable) {
                    prefs.status = "Connection lost (${e.message}). Will retry."
                    return Result.retry()
                }
                failed++
                lastProblem = "${item.name}: the server closed the connection during the upload"
            }
        }
        return finish(uploaded, present, skipped, failed, tooBig, limit, lastProblem)
    }

    private fun finish(uploaded: Int, present: Int, skipped: Int, failed: Int, tooBig: Int, limit: Long, lastProblem: String): Result {
        val parts = ArrayList<String>()
        if (uploaded > 0) parts.add("$uploaded uploaded")
        if (present > 0) parts.add("$present already on the server")
        if (skipped > 0) parts.add("$skipped skipped")
        if (tooBig > 0) parts.add("$tooBig too big for the server limit (${limit / 1048576} MB)")
        if (failed > 0) parts.add("$failed failed (will retry next time)")
        prefs.lastRun = System.currentTimeMillis()
        prefs.status = (if (parts.isEmpty()) "Everything is backed up" else parts.joinToString(", ")) +
            (if (lastProblem.isNotEmpty()) ". Last problem: $lastProblem" else "")
        Notify.clear(applicationContext, NOTIF_ALERT)
        return Result.success()
    }

    private fun stop(message: String, alert: Boolean = false): Result {
        prefs.status = message
        if (alert) Notify.alert(applicationContext, NOTIF_ALERT, "Stash backup needs attention", message)
        return Result.failure()
    }

    // ---- local file access -------------------------------------------------------------------------------

    /** Sends a small preview of a file that was just uploaded, so the website's grid does not load the original. Best effort. */
    private fun preview(base: String, uri: Uri, mime: String, sha: String) {
        try {
            Thumbs.make(applicationContext, uri, mime)?.let { StashApi.putThumb(base, prefs.apiKey, sha, it) }
        } catch (e: Exception) {
            // a missing preview only means the website loads the original once and makes its own
        }
    }

    private fun open(uri: Uri): InputStream = try {
        applicationContext.contentResolver.openInputStream(uri) ?: throw SourceException("could not open the file")
    } catch (e: SourceException) {
        throw e
    } catch (e: Exception) {
        throw SourceException(e.message ?: "could not open the file")
    }

    /** SHA-256 and exact byte count of what we will upload. */
    private fun hash(uri: Uri): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open(uri).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (isStopped) throw UploadCancelled()
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    throw SourceException("could not read the file: ${e.message}")
                }
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        return Pair(md.digest().joinToString("") { "%02x".format(it) }, total)
    }

    // ---- status + notifications --------------------------------------------------------------------------

    private fun info(text: String, pct: Int) = ForegroundInfo(
        NOTIF_PROGRESS, Notify.running(applicationContext, "Backing up to Stash", text, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /** [pct] = overall progress of the run, 0..100. */
    private suspend fun progress(text: String, pct: Int) {
        if (isStopped) return // a cancelled run must not overwrite the "Cancelled" status
        prefs.status = text
        try {
            setForeground(info(text, pct))
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android refuses to start a foreground service from some background states; just carry on without it.
        }
    }

    private fun tick(item: BackupFile, sent: Long, total: Long, index: Int, count: Int) {
        if (isStopped) return
        val now = System.currentTimeMillis()
        if (now - lastTick < 1000) return
        lastTick = now
        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
        val text = "Uploading ${index + 1} of $count: ${item.name} ($pct%)"
        prefs.status = text
        if (foreground) {
            val overall = ((index * 100L + pct) / count).toInt().coerceIn(0, 100)
            Notify.update(applicationContext, NOTIF_PROGRESS, Notify.running(applicationContext, "Backing up to Stash", text, overall))
        }
    }

    companion object {
        const val MANUAL = "manual"
        private val RUNNING = AtomicBoolean(false)
        private const val NOTIF_PROGRESS = 41
        private const val NOTIF_ALERT = 42
    }
}
