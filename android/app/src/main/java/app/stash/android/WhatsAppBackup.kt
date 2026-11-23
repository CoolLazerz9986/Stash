// Path: android/app/src/main/java/app/stash/android/WhatsAppBackup.kt
package app.stash.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A WhatsApp backup file found through the folder the user picked. */
data class WaFile(val uri: Uri, val name: String, val size: Long, val modified: Long)

/**
 * Finds WhatsApp's encrypted backup (msgstore*.crypt15) inside the folder the user picked with the system folder
 * picker (Storage Access Framework, so no storage permission is needed). The user may pick WhatsApp, WhatsApp/Backups,
 * com.whatsapp or Android/media: only folders that can hold the backup are searched, never the huge Media folder.
 */
object WaSource {
    private val DIRS = setOf("backups", "databases", "whatsapp", "com.whatsapp", "com.whatsapp.w4b", "whatsapp business")
    private val FILE = Regex("^msgstore.*\\.crypt15$", RegexOption.IGNORE_CASE)

    /** Where the folder picker should start (a hint only; the user can go anywhere). */
    fun pickerStart(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        if (android.os.Build.VERSION.SDK_INT >= 30) "primary:Android/media/com.whatsapp/WhatsApp" else "primary:WhatsApp",
    )

    /** The newest matching file (by modified time), or null. Throws SecurityException if the folder permission is gone. */
    fun findNewest(ctx: Context, tree: Uri): WaFile? {
        var best: WaFile? = null
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 4 && name.lowercase() in DIRS) walk(id, depth + 1)
                    } else if (FILE.matches(name) && c.getLong(3) > 0) {
                        val f = WaFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), c.getLong(4))
                        val b = best
                        if (b == null || f.modified > b.modified) best = f
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return best
    }
}

/**
 * One chat backup pass: read WhatsApp's newest encrypted backup, decrypt it here on the phone with the user's
 * 64-digit key (the server never sees the key), and upload the plain message database to POST /api/chats/import.
 *
 * Steps: ask the server what it holds -> find the newest backup file -> skip if it is the file we already sent
 * -> decrypt into a temp file in the app cache (hashing as it goes) -> skip the upload if the server already holds
 * identical bytes -> upload with X-Sha256 -> delete the temp file. One way: nothing on the phone is changed or deleted
 * (the WhatsApp folder is only read).
 */
class ChatBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val prefs = Prefs(applicationContext)
    private var lastTick = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!RUNNING.compareAndSet(false, true)) return@withContext Result.success()
        val tmp = File(applicationContext.cacheDir, "wa-decrypted.db")
        try {
            run(tmp, inputData.getBoolean("force", false))
        } finally {
            tmp.delete()
            RUNNING.set(false)
        }
    }

    private suspend fun run(tmp: File, force: Boolean): Result {
        if (!prefs.chatEnabled || prefs.serverUrl.isEmpty()) return Result.success()
        val rootKey = Crypt15.parseKey(prefs.chatKey) ?: return stop("The WhatsApp key is missing or is not 64 hex digits", alert = true)
        if (prefs.chatTree.isEmpty()) return stop("Choose the WhatsApp folder first", alert = true)

        val base = prefs.serverUrl
        val session = try {
            StashApi.session(base)
        } catch (e: IOException) {
            prefs.chatStatus = "Can't reach the server (${e.message}). Will retry."
            return Result.retry()
        }
        if (session.api < 2) return stop("The server needs the updated server.py")
        if (session.authRequired && prefs.apiKey.isEmpty()) {
            return stop("Not connected: switch chat backup off and on again while logged in", alert = true)
        }
        val server = try {
            StashApi.chatStatus(base, prefs.apiKey)
        } catch (e: StashApi.ApiException) {
            return when (e.status) {
                401, 403 -> stop("The server rejected the key: switch chat backup off and on again", alert = true)
                429 -> { prefs.chatStatus = "The server is rate limiting this phone. Will retry."; Result.retry() }
                else -> stop(e.message ?: "The server refused the request")
            }
        } catch (e: IOException) {
            prefs.chatStatus = "Can't reach the server (${e.message}). Will retry."
            return Result.retry()
        }

        prefs.chatStatus = "Looking for the WhatsApp backup…"
        val found = try {
            WaSource.findNewest(applicationContext, Uri.parse(prefs.chatTree))
        } catch (e: SecurityException) {
            return stop("Access to the WhatsApp folder was lost. Choose the folder again.", alert = true)
        } catch (e: Exception) {
            return stop("Can't read the WhatsApp folder: ${e.message}")
        }
        if (found == null) {
            return stop("No WhatsApp backup (msgstore….crypt15) in that folder. In WhatsApp: Settings > Chats > Chat backup > Back up.")
        }

        val stamp = "$base|${found.name}|${found.size}|${found.modified}"
        if (!force && stamp == prefs.chatDone) {
            prefs.chatStatus = "Up to date: the WhatsApp backup has not changed since the last run"
            return Result.success()
        }

        // ---- decrypt (hashing the plain database while it is written) ----
        val md = MessageDigest.getInstance("SHA-256")
        progress("Decrypting the WhatsApp backup…")
        try {
            val input = applicationContext.contentResolver.openInputStream(found.uri)
                ?: return stop("Could not open the WhatsApp backup file")
            DigestOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024), md).use { out ->
                input.use {
                    Crypt15.decrypt(it, found.size, rootKey, out, { isStopped }) { done ->
                        tick("Decrypting the WhatsApp backup… ${if (found.size > 0) done * 100 / found.size else 0}%")
                    }
                }
            }
        } catch (e: UploadCancelled) {
            return Result.retry()
        } catch (e: WrongKeyException) {
            return stop("Could not decrypt the backup: ${e.message}. Check the 64-digit key.", alert = true)
        } catch (e: SecurityException) {
            return stop("Access to the WhatsApp folder was lost. Choose the folder again.", alert = true)
        } catch (e: IOException) {
            return stop("Could not decrypt (is the phone out of space?): ${e.message}")
        }

        val head = ByteArray(16)
        val headRead = try { FileInputStream(tmp).use { it.read(head) } } catch (e: IOException) { -1 }
        if (headRead != 16 || String(head, Charsets.ISO_8859_1) != "SQLite format 3\u0000") {
            return stop("The decrypted data is not a database: wrong key?", alert = true)
        }
        val plainSize = tmp.length()
        val sha = md.digest().joinToString("") { "%02x".format(it) }

        if (sha == server.sha256) { // the server already holds exactly this database
            done(stamp, "Chats are up to date on the server")
            return Result.success()
        }
        if (server.maxBytes > 0 && plainSize > server.maxBytes) {
            return stop(
                "The chat database (${plainSize / 1048576} MB) is bigger than the server limit (${server.maxBytes / 1048576} MB). Raise STASH_CHATS_MAX_MB on the server.",
                alert = true,
            )
        }

        // ---- upload ----
        progress("Uploading chats…")
        try {
            StashApi.upload(
                base, prefs.apiKey, "msgstore.db", "application/vnd.sqlite3", plainSize, sha,
                open = { FileInputStream(tmp) },
                onProgress = { sent -> tick("Uploading chats… ${if (plainSize > 0) sent * 100 / plainSize else 0}%") },
                cancelled = { isStopped },
                path = "/api/chats/import",
                readTimeoutMs = 900_000, // the server indexes the database before it answers
            )
        } catch (e: UploadCancelled) {
            return Result.retry()
        } catch (e: SourceException) {
            return stop("Could not read the decrypted copy: ${e.message}")
        } catch (e: StashApi.ApiException) {
            return when (e.status) {
                401, 403 -> stop("The server rejected the key: switch chat backup off and on again", alert = true)
                409, 429 -> { prefs.chatStatus = "The server is busy. Will retry."; Result.retry() }
                413 -> stop("The server says the chat database is too big. Raise STASH_CHATS_MAX_MB on the server.", alert = true)
                507 -> stop("The server's disk is full", alert = true)
                else -> stop("The server did not accept the chats: ${e.message}")
            }
        } catch (e: IOException) {
            val reachable = try { StashApi.session(base); true } catch (x: IOException) { false }
            prefs.chatStatus = if (reachable) "The server closed the connection during the upload. Will retry." else "Connection lost (${e.message}). Will retry."
            return Result.retry()
        }
        done(stamp, "Chats backed up (${plainSize / 1048576} MB database)")
        return Result.success()
    }

    private fun done(stamp: String, message: String) {
        prefs.chatDone = stamp
        prefs.chatLastRun = System.currentTimeMillis()
        prefs.chatStatus = message
        applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
    }

    private fun stop(message: String, alert: Boolean = false): Result {
        prefs.chatStatus = message
        if (alert) alert(message)
        return Result.failure()
    }

    // ---- status + notifications (same channels as BackupWorker) ----

    private suspend fun progress(text: String) {
        prefs.chatStatus = text
        try {
            setForeground(foregroundInfo(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android refuses to start a foreground service from some background states; carry on without it.
        }
    }

    private fun tick(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastTick < 1000) return
        lastTick = now
        prefs.chatStatus = text
    }

    private fun channel(id: String, name: String, importance: Int) {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, name, importance))
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        channel(CH_PROGRESS, "Backup progress", NotificationManager.IMPORTANCE_LOW)
        val n = Notification.Builder(applicationContext, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up WhatsApp chats")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
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
            .setContentTitle("Stash chat backup needs attention")
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
        private const val NOTIF_PROGRESS = 43
        private const val NOTIF_ALERT = 44
    }
}