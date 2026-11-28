// Path: android/app/src/main/java/app/stash/android/DownloadService.kt
package app.stash.android

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs downloads one after another as a foreground service, so they carry on (with a progress bar and a Cancel button in
 * the notification shade) when the user leaves the app. Started by [Downloader.start]; stops itself when the queue is empty.
 * While the app is on screen the same progress and the result go to the in-app banner ([Events]) instead of a result
 * notification. All notifications are built by [Notify].
 */
class DownloadService : Service() {
    private val exec = Executors.newSingleThreadExecutor()
    private val jobs = CopyOnWriteArrayList<Downloader.Job>()
    private val pending = AtomicInteger(0) // running + waiting
    private val lastStart = AtomicInteger(0)
    private val seq = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart.set(startId)
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) { // the Cancel button: the running download and everything waiting
            for (j in jobs) j.cancel()
            if (pending.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        val job = Downloader.Job(
            intent.getStringExtra(X_URL).orEmpty(), intent.getStringExtra(X_METHOD) ?: "GET",
            intent.getStringExtra(X_FORM), intent.getStringExtra(X_COOKIE), intent.getStringExtra(X_NAME) ?: "download",
        )
        try {
            startForeground(
                NOTIF_PROGRESS, Notify.downloadProgress(this, job.fallbackName, "Starting…", 0, 0, cancelIntent()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            Events.post(Events.Msg("download", Events.Kind.ERROR, "Download could not start: ${e.message}"))
            if (pending.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        jobs.add(job)
        pending.incrementAndGet()
        exec.execute { runJob(job) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        for (j in jobs) j.cancel()
        exec.shutdownNow()
        super.onDestroy()
    }

    // ---- one download (worker thread) ----

    private fun runJob(job: Downloader.Job) {
        var name = job.fallbackName
        var lastAt = 0L
        try {
            if (job.cancelled) throw Downloader.Cancelled()
            update(name, "Preparing…", 0, 0) // a zip is built by the server first: no progress yet
            val saved = Downloader.run(this, job, { n -> name = n; update(n, "Starting…", 0, 0) }) { done, total ->
                val now = SystemClock.uptimeMillis()
                if (now - lastAt >= 500 || done == total) { // at most twice a second
                    lastAt = now
                    val text = if (total > 0) "${done * 100 / total}% · ${Formatter.formatShortFileSize(this, done)} of ${Formatter.formatShortFileSize(this, total)}"
                    else Formatter.formatShortFileSize(this, done)
                    update(name, text, done, total)
                }
            }
            finishOk(saved)
        } catch (e: Exception) {
            if (job.cancelled || e is Downloader.Cancelled) Events.post(Events.Msg("download", Events.Kind.INFO, "Download cancelled"))
            else finishFail(name, e.message ?: e.javaClass.simpleName)
        } finally {
            jobs.remove(job)
            if (pending.decrementAndGet() == 0) {
                main.post {
                    if (pending.get() == 0) { // nothing new arrived meanwhile
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStart.get())
                    }
                }
            }
        }
    }

    private fun update(title: String, text: String, done: Long, total: Long) {
        val queue = pending.get() - 1
        Notify.downloading(this, NOTIF_PROGRESS, title, if (queue > 0) "$text  (+$queue waiting)" else text, done, total, cancelIntent())
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
        Events.post(
            Events.Msg(
                "download", Events.Kind.PROGRESS,
                if (pct >= 0) "Downloading $title: $pct%" else "Downloading $title…", pct, ttlMs = 600_000,
            )
        )
    }

    private fun finishOk(s: Downloader.Saved) {
        Events.post(Events.Msg("download", Events.Kind.SUCCESS, "Saved to Downloads/Stash: ${s.name}"))
        if (Events.hasListener()) return // the app is on screen: the banner says it
        Notify.downloaded(this, resultId(), s.name, s.uri, s.mime)
    }

    private fun finishFail(name: String, why: String) {
        Events.post(Events.Msg("download", Events.Kind.ERROR, "Download failed: $why"))
        if (Events.hasListener()) return
        Notify.downloadFailed(this, resultId(), name, why)
    }

    private fun resultId() = NOTIF_RESULT + seq.incrementAndGet() % 50

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val X_URL = "url"
        const val X_METHOD = "method"
        const val X_FORM = "form"
        const val X_COOKIE = "cookie"
        const val X_NAME = "name"
        const val ACTION_CANCEL = "app.stash.android.CANCEL_DOWNLOAD"
        private const val NOTIF_PROGRESS = 51
        private const val NOTIF_RESULT = 100
    }
}
