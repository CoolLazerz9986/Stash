// Path: android/app/src/main/java/app/stash/android/WhatsAppBackup.kt
package app.stash.android

import android.content.Context
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A WhatsApp backup file found through the folder the user picked. */
data class WaFile(val uri: Uri, val name: String, val size: Long, val modified: Long)

/** One entry (file or folder) of the picked folder tree. */
data class WaEntry(val id: String, val uri: Uri, val name: String, val mime: String, val dir: Boolean, val size: Long, val modified: Long)

/**
 * Finds WhatsApp's encrypted backup (msgstore*.crypt15) and its Media folder inside the folder the user picked with the
 * system folder picker (Storage Access Framework, so no storage permission is needed). The user may pick WhatsApp,
 * WhatsApp/Backups, com.whatsapp or Android/media: only folders that can hold the backup are searched.
 */
object WaSource {
    private val DIRS = setOf("backups", "databases", "whatsapp", "com.whatsapp", "com.whatsapp.w4b", "whatsapp business")
    private val FILE = Regex("^msgstore.*\\.crypt15$", RegexOption.IGNORE_CASE)
    private val COLS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /** Where the folder picker should start (a hint only; the user can go anywhere). */
    fun pickerStart(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        if (android.os.Build.VERSION.SDK_INT >= 30) "primary:Android/media/com.whatsapp/WhatsApp" else "primary:WhatsApp",
    )

    /** The direct children of one folder. Throws SecurityException if the folder permission is gone. */
    fun children(ctx: Context, tree: Uri, docId: String): List<WaEntry> {
        val out = ArrayList<WaEntry>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        ctx.contentResolver.query(uri, COLS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                out.add(
                    WaEntry(
                        id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4),
                    )
                )
            }
        }
        return out
    }

    /** The newest matching file (by modified time), or null. Throws SecurityException if the folder permission is gone. */
    fun findNewest(ctx: Context, tree: Uri): WaFile? {
        var best: WaFile? = null
        fun walk(docId: String, depth: Int) {
            for (e in children(ctx, tree, docId)) {
                if (e.dir) {
                    if (depth < 4 && e.name.lowercase() in DIRS) walk(e.id, depth + 1)
                } else if (FILE.matches(e.name) && e.size > 0) {
                    val f = WaFile(e.uri, e.name, e.size, e.modified)
                    val b = best
                    if (b == null || f.modified > b.modified) best = f
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return best
    }

    /** Document id of WhatsApp's "Media" folder (holds "WhatsApp Images" and so on), or null. Same folders as [findNewest]. */
    fun findMediaRoot(ctx: Context, tree: Uri): String? {
        fun walk(docId: String, depth: Int): String? {
            val kids = children(ctx, tree, docId)
            kids.firstOrNull { it.dir && it.name.equals("media", ignoreCase = true) }?.let { return it.id }
            if (depth < 4) for (k in kids) if (k.dir && k.name.lowercase() in DIRS) walk(k.id, depth + 1)?.let { return it }
            return null
        }
        return walk(DocumentsContract.getTreeDocumentId(tree), 0)
    }
}

/**
 * Finds a media file from the path WhatsApp's database stores for it (e.g. "Media/WhatsApp Images/IMG-1.jpg"), walking the
 * picked folder one level at a time. Each folder is listed once and remembered (big folders hold thousands of files).
 */
class MediaIndex(private val ctx: Context, private val tree: Uri, private val rootId: String) {
    private val cache = HashMap<String, Map<String, WaEntry>>()

    private fun list(docId: String): Map<String, WaEntry> {
        cache[docId]?.let { return it }
        if (cache.size > 40) cache.clear()
        val m = HashMap<String, WaEntry>()
        for (e in WaSource.children(ctx, tree, docId)) m[e.name.lowercase()] = e
        cache[docId] = m
        return m
    }

    /** The file at [path], or null if any part is missing (WhatsApp deleted it, or it was never downloaded). */
    fun resolve(path: String): WaEntry? {
        val parts = path.replace("\\", "/").split("/").filter { it.isNotEmpty() && it != "." && it != ".." }.toMutableList()
        if (parts.isNotEmpty() && parts[0].equals("media", ignoreCase = true)) parts.removeAt(0)
        if (parts.isEmpty()) return null
        var cur = rootId
        for ((i, p) in parts.withIndex()) {
            val e = list(cur)[p.lowercase()] ?: return null
            if (i == parts.size - 1) return if (e.dir) null else e
            if (!e.dir) return null
            cur = e.id
        }
        return null
    }
}

/**
 * One chat backup pass: read WhatsApp's newest encrypted backup, decrypt it here on the phone with the user's
 * 64-digit key (the server never sees the key), and upload the plain message database to POST /api/chats/import.
 * Then the media pass: for every media row of the decrypted database that belongs to a real chat message, find that file
 * in WhatsApp's Media folder, check that its SHA-256 equals the hash WhatsApp recorded for the message, and upload it
 * with the tag "whatsapp". The server links it back to the message by that same hash, so only the right file loads.
 *
 * Steps: ask the server what it holds -> find the newest backup file -> skip if it is the file we already sent
 * -> decrypt into a temp file in the app cache (hashing as it goes) -> upload the database unless the server holds
 * identical bytes -> media pass -> delete the temp file. One way: nothing on the phone is changed or deleted
 * (the WhatsApp folder is only read).
 *
 * Notifications: the ongoing one shows a real percentage while decrypting and uploading; problems are posted by [Notify].
 */
class ChatBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val prefs = Prefs(applicationContext)
    private val backupDb = BackupDb(applicationContext) // remembers media files already handled (same table as photos)
    private var lastTick = 0L
    private var foreground = false // setForeground worked, so the notification may be refreshed

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
        progress("Decrypting the WhatsApp backup…", 0)
        try {
            val input = applicationContext.contentResolver.openInputStream(found.uri)
                ?: return stop("Could not open the WhatsApp backup file")
            DigestOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024), md).use { out ->
                input.use {
                    Crypt15.decrypt(it, found.size, rootKey, out, { isStopped }) { done ->
                        val pct = if (found.size > 0) (done * 100 / found.size).toInt().coerceIn(0, 100) else 0
                        tick("Decrypting the WhatsApp backup… $pct%", pct)
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

        // ---- upload the database (unless the server already holds exactly these bytes) ----
        val dbMessage: String
        if (sha == server.sha256) {
            dbMessage = "Chats are up to date on the server"
        } else {
            if (server.maxBytes > 0 && plainSize > server.maxBytes) {
                return stop(
                    "The chat database (${plainSize / 1048576} MB) is bigger than the server limit (${server.maxBytes / 1048576} MB). Raise STASH_CHATS_MAX_MB on the server.",
                    alert = true,
                )
            }
            progress("Uploading chats…", 0)
            try {
                StashApi.upload(
                    base, prefs.apiKey, "msgstore.db", "application/vnd.sqlite3", plainSize, sha,
                    open = { FileInputStream(tmp) },
                    onProgress = { sent ->
                        val pct = if (plainSize > 0) (sent * 100 / plainSize).toInt().coerceIn(0, 100) else 0
                        // after the last byte the server indexes the database before it answers: keep the text honest
                        tick("Uploading chats… $pct%", if (pct >= 100) Notify.INDETERMINATE else pct)
                    },
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
            dbMessage = "Chats backed up (${plainSize / 1048576} MB database)"
        }

        // ---- media pass: only files that belong to a message in the database just decrypted ----
        val st = MediaStats()
        st.days = inputData.getInt("mediaDays", DEFAULT_MEDIA_DAYS).coerceIn(1, 365) // default: only the last day
        val abort = backupMedia(base, session.maxBytes, tmp, st)
        if (abort != null) return abort
        // a run with failed files is not marked done, so the next run tries those files again (finished ones are skipped)
        done(if (st.failed == 0) stamp else null, dbMessage + st.summary())
        return Result.success()
    }

    // ---- media ------------------------------------------------------------------------------------------

    private class MediaStats {
        var total = 0
        var known = 0
        var uploaded = 0
        var present = 0
        var missing = 0
        var tooBig = 0
        var mismatch = 0
        var failed = 0
        var note = ""
        var last = ""
        var days = DEFAULT_MEDIA_DAYS

        fun summary(): String {
            val win = if (days == 1) "the last day" else "the last $days days"
            if (note.isNotEmpty()) return ". Media: $note"
            if (total == 0) return ". Media: none linked to messages from $win"
            val p = ArrayList<String>()
            if (uploaded > 0) p.add("$uploaded uploaded")
            if (present > 0) p.add("$present already on the server")
            if (known > 0) p.add("$known done before")
            if (missing > 0) p.add("$missing not on this phone")
            if (tooBig > 0) p.add("$tooBig too big for the server limit")
            if (mismatch > 0) p.add("$mismatch differ from what WhatsApp recorded (skipped)")
            if (failed > 0) p.add("$failed failed (will retry)")
            return ". Media from $win: " + (if (p.isEmpty()) "nothing new" else p.joinToString(", ")) + (if (last.isNotEmpty()) ". Last problem: $last" else "")
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { while (it.moveToNext()) out.add(it.getString(1)) }
        return out
    }

    /** WhatsApp's recorded SHA-256 of a media file (base64 text, or raw bytes in some versions) as 64 hex chars, or null. */
    private fun expectedHash(c: Cursor, col: Int): String? {
        val raw: ByteArray = try {
            if (c.getType(col) == Cursor.FIELD_TYPE_BLOB) c.getBlob(col)
            else java.util.Base64.getDecoder().decode((c.getString(col) ?: "").trim())
        } catch (e: Exception) {
            return null
        }
        return if (raw.size == 32) raw.joinToString("") { "%02x".format(it) } else null
    }

    /** Sends a small preview of a file that was just uploaded, so the website's grid does not load the original. Best effort. */
    private fun preview(base: String, uri: Uri, mime: String, sha: String) {
        try {
            Thumbs.make(applicationContext, uri, mime)?.let { StashApi.putThumb(base, prefs.apiKey, sha, it) }
        } catch (e: Exception) {
            // a missing preview only means the website loads the original once and makes its own
        }
    }

    private fun openIn(uri: Uri): InputStream =
        try {
            applicationContext.contentResolver.openInputStream(uri) ?: throw SourceException("could not open the file")
        } catch (e: SourceException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(e.message ?: "could not open the file")
        }

    private fun hashOf(uri: Uri): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        openIn(uri).use { input ->
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

    /** Returns null to carry on (counts are in [st]), or the Result the whole run must end with. */
    private suspend fun backupMedia(base: String, limit: Long, dbFile: File, st: MediaStats): Result? {
        val tree = Uri.parse(prefs.chatTree)
        val rootId = try {
            WaSource.findMediaRoot(applicationContext, tree)
        } catch (e: SecurityException) {
            return stop("Access to the WhatsApp folder was lost. Choose the folder again.", alert = true)
        } catch (e: Exception) {
            st.note = "can't read the Media folder (${e.message})"
            return null
        }
        if (rootId == null) {
            st.note = "the chosen folder has no Media folder (choose the WhatsApp folder that contains Media)"
            return null
        }
        val index = MediaIndex(applicationContext, tree, rootId)
        val wa = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            st.note = "can't read the media list (${e.message})"
            return null
        }
        try {
            try { wa.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() } } catch (e: Exception) { }
            val cols = columns(wa, "message_media")
            if (!cols.containsAll(listOf("message_row_id", "file_path", "file_hash"))) {
                st.note = "this backup has no media list"
                return null
            }
            val sizeCol = if ("file_size" in cols) "mm.file_size" else "0"
            progress("Checking WhatsApp media…")
            // only media of real chat messages (status updates are not chats), sent within the chosen window (message.timestamp is in ms)
            val cutoff = (System.currentTimeMillis() - st.days * 86_400_000L).toString()
            val sql = "SELECT DISTINCT mm.file_path, mm.file_hash, $sizeCol FROM message_media mm " +
                "JOIN message m ON m._id = mm.message_row_id JOIN chat c ON c._id = m.chat_row_id " +
                "JOIN jid j ON j._id = c.jid_row_id " +
                "WHERE mm.file_path IS NOT NULL AND mm.file_hash IS NOT NULL AND j.raw_string NOT LIKE 'status@%' AND m.timestamp >= CAST(? AS INTEGER)"
            wa.rawQuery(sql, arrayOf(cutoff)).use { cur ->
                while (cur.moveToNext()) {
                    if (isStopped) return Result.retry()
                    st.total++
                    val path = cur.getString(0) ?: continue
                    val want = expectedHash(cur, 1) ?: continue
                    val declared = cur.getLong(2)
                    if (limit > 0 && declared > limit) { st.tooBig++; continue }
                    val f = try {
                        index.resolve(path)
                    } catch (e: SecurityException) {
                        return stop("Access to the WhatsApp folder was lost. Choose the folder again.", alert = true)
                    }
                    if (f == null) { st.missing++; continue }
                    if (limit > 0 && f.size > limit) { st.tooBig++; continue }
                    val key = f.uri.toString()
                    if (backupDb.isDone(key, f.size, f.modified)) { st.known++; continue }
                    tick("WhatsApp media: ${st.total} checked, ${st.uploaded} uploaded")
                    try {
                        val (sha, bytes) = hashOf(f.uri)
                        if (sha != want) { // not the file this message refers to (edited, or another file with that name)
                            backupDb.mark(key, f.size, f.modified, sha, "mismatch"); st.mismatch++; continue
                        }
                        if (limit in 1 until bytes) { st.tooBig++; continue }
                        if (StashApi.exists(base, prefs.apiKey, sha)) {
                            backupDb.mark(key, f.size, f.modified, sha, "present"); st.present++; continue
                        }
                        StashApi.upload(
                            base, prefs.apiKey, f.name, f.mime.ifEmpty { "application/octet-stream" }, bytes, sha,
                            open = { openIn(f.uri) },
                            onProgress = { },
                            cancelled = { isStopped },
                            source = "whatsapp",
                        )
                        preview(base, f.uri, f.mime, sha)
                        backupDb.mark(key, f.size, f.modified, sha, "uploaded"); st.uploaded++
                    } catch (e: UploadCancelled) {
                        return Result.retry()
                    } catch (e: SecurityException) {
                        return stop("Access to the WhatsApp folder was lost. Choose the folder again.", alert = true)
                    } catch (e: SourceException) {
                        st.failed++; st.last = "${f.name}: ${e.message}"
                    } catch (e: StashApi.ApiException) {
                        when (e.status) {
                            401, 403 -> return stop("The server rejected the key: switch chat backup off and on again", alert = true)
                            429 -> { prefs.chatStatus = "The server is rate limiting this phone. Will retry."; return Result.retry() }
                            507 -> return stop("The server's disk is full", alert = true)
                            413 -> st.tooBig++
                            else -> { st.failed++; st.last = "${f.name}: ${e.message}" }
                        }
                    } catch (e: IOException) {
                        val reachable = try { StashApi.session(base); true } catch (x: IOException) { false }
                        if (!reachable) {
                            prefs.chatStatus = "Connection lost (${e.message}). Will retry."
                            return Result.retry()
                        }
                        st.failed++; st.last = "${f.name}: the server closed the connection during the upload"
                    }
                }
            }
        } finally {
            try { wa.close() } catch (e: Exception) { }
        }
        return null
    }

    /** [stamp] = null: the media pass had failures, so this backup file is not marked as finished (the next run retries). */
    private fun done(stamp: String?, message: String) {
        if (stamp != null) prefs.chatDone = stamp
        prefs.chatLastRun = System.currentTimeMillis()
        prefs.chatStatus = message
        Notify.clear(applicationContext, NOTIF_ALERT)
    }

    private fun stop(message: String, alert: Boolean = false): Result {
        prefs.chatStatus = message
        if (alert) Notify.alert(applicationContext, NOTIF_ALERT, "Stash chat backup needs attention", message)
        return Result.failure()
    }

    // ---- status + notifications ----

    private fun info(text: String, pct: Int) = ForegroundInfo(
        NOTIF_PROGRESS, Notify.running(applicationContext, "Backing up WhatsApp chats", text, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /** [pct] 0..100 = real bar, [Notify.INDETERMINATE] = moving bar. */
    private suspend fun progress(text: String, pct: Int = Notify.INDETERMINATE) {
        if (isStopped) return
        prefs.chatStatus = text
        try {
            setForeground(info(text, pct))
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android refuses to start a foreground service from some background states; carry on without it.
        }
    }

    private fun tick(text: String, pct: Int = Notify.INDETERMINATE) {
        if (isStopped) return // a stopped run must not overwrite the "Stopped" status
        val now = System.currentTimeMillis()
        if (now - lastTick < 1000) return
        lastTick = now
        prefs.chatStatus = text
        if (foreground) Notify.update(applicationContext, NOTIF_PROGRESS, Notify.running(applicationContext, "Backing up WhatsApp chats", text, pct))
    }

    companion object {
        private val RUNNING = AtomicBoolean(false)
        /** Media of chat messages from the last this many days is backed up unless a run asks for more (max 365). */
        const val DEFAULT_MEDIA_DAYS = 1
        private const val NOTIF_PROGRESS = 43
        private const val NOTIF_ALERT = 44
    }
}
