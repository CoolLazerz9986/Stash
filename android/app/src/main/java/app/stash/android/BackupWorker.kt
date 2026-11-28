package app.stash.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
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
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val prefs = Prefs(applicationContext)
    private val db = BackupDb(applicationContext)
    private var lastTick = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // the hourly job and "Back up now" must never run side by side (they would upload the same file twice)
        if (!RUNNING.compareAndSet(false, true)) return@withContext Result.success()
        try {
            backup()
        } finally {
            RUNNING.set(false)
        }
    }

    private suspend fun backup(): Result {
        if (!prefs.backupEnabled || prefs.serverUrl.isEmpty()) return Result.success()
        if (!Perms.hasMedia(applicationContext)) return stop("Photo access was not granted", alert = true)

        val base = prefs.serverUrl
        val session = try {
            StashApi.session(base)
        } catch (e: IOException) {
            prefs.status = "Can't reach the server (${e.message}). Will retry."
            return Result.retry()
        }
        if (session.api < 2) return stop("The server needs the updated server.py")
        if (session.authRequired && prefs.apiKey.isEmpty()) {
            return stop("Not connected: switch backup off and on again while logged in", alert = true)
        }
        val limit = session.maxBytes

        prefs.status = "Looking for new photos and videos…"
        val todo = MediaSource.list(applicationContext, prefs).filter { !db.isDone(it.uri.toString(), it.size, it.modified) }
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
            progress("Backing up ${i + 1} of ${todo.size}: ${item.name}", i, todo.size)
            val key = item.uri.toString()
            try {
                val (sha, bytes) = hash(item.uri)
                if (bytes == 0L) {
                    db.mark(key, item.size, item.modified, sha, "skipped"); skipped++; continue
                }
                if (limit in 1 until bytes) { // the file grew after MediaStore reported its size
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
                    401, 403 -> return stop("The server rejected the key: switch backup off and on again", alert = true)
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
        applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
        return Result.success()
    }

    private fun stop(message: String, alert: Boolean = false): Result {
        prefs.status = message
        if (alert) alert(message)
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

    private fun open(uri: Uri): InputStream {
        try {
            // ask for the original bytes (with GPS data), otherwise Android hands us a redacted copy
            val u = if (Perms.hasMediaLocation(applicationContext)) MediaStore.setRequireOriginal(uri) else uri
            return applicationContext.contentResolver.openInputStream(u) ?: throw SourceException("could not open the file")
        } catch (e: SourceException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(e.message ?: "could not open the file")
        }
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

    private suspend fun progress(text: String, done: Int, total: Int) {
        prefs.status = text
        try {
            setForeground(foregroundInfo(text, done, total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android refuses to start a foreground service from some background states; just carry on without it.
        }
    }

    private fun tick(item: MediaItem, sent: Long, total: Long, index: Int, count: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTick < 1000) return
        lastTick = now
        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
        prefs.status = "Uploading ${index + 1} of $count: ${item.name} ($pct%)"
    }

    private fun channel(id: String, name: String, importance: Int) {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, name, importance))
    }

    private fun foregroundInfo(text: String, done: Int, total: Int): ForegroundInfo {
        channel(CH_PROGRESS, "Backup progress", NotificationManager.IMPORTANCE_LOW)
        val n = Notification.Builder(applicationContext, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up to Stash")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()
        return ForegroundInfo(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun alert(text: String) {
        if (!Perms.canNotify(applicationContext)) return
        channel(CH_ALERT, "Backup problems", NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, BackupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(applicationContext, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Stash backup needs attention")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    companion object {
        private val RUNNING = AtomicBoolean(false)
        private const val CH_PROGRESS = "backup_progress"
        private const val CH_ALERT = "backup_alerts"
        private const val NOTIF_PROGRESS = 41
        private const val NOTIF_ALERT = 42
    }
}
