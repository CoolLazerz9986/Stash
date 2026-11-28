// Path: android/app/src/main/java/app/stash/android/BackupScheduler.kt
package app.stash.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Turns the backup settings into WorkManager jobs. Call [apply] / [applyChat] again whenever a setting changes. */
object BackupScheduler {
    private const val PERIODIC = "stash-backup-periodic"
    private const val NOW = "stash-backup-now"
    private const val CHAT_PERIODIC = "stash-chat-periodic"
    private const val CHAT_NOW = "stash-chat-now"

    /** Status text shown while a job is held back because "Wi-Fi only" is on and the phone is not on Wi-Fi. */
    const val WIFI_WAIT = "Waiting for Wi-Fi (Wi-Fi only is on)"

    private fun constraints(p: Prefs, charging: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(charging)
        .build()

    /**
     * True when "Wi-Fi only" is on and the phone is not on an unmetered network (the same rule WorkManager uses for
     * [NetworkType.UNMETERED]). In that case Android never starts the job, so the job cannot report why nothing happens.
     */
    fun waitingForWifi(ctx: Context): Boolean {
        if (!Prefs(ctx).wifiOnly) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Writes (or clears) the "Waiting for Wi-Fi" status of the photo and chat backups. Call it right after scheduling,
     * and from a screen refresh if you like: it only touches a status that is empty or already says WIFI_WAIT, so it
     * never overwrites a message written by a worker.
     */
    fun noteWifiWait(ctx: Context, photos: Boolean = true, chats: Boolean = true, force: Boolean = false) {
        val p = Prefs(ctx)
        val waiting = waitingForWifi(ctx)
        fun idle(s: String) = s.isEmpty() || s == WIFI_WAIT || s == "Starting…" || s == "Waiting for the next run"
        if (photos && p.backupEnabled) {
            if (waiting && (force || idle(p.status))) p.status = WIFI_WAIT
            else if (!waiting && p.status == WIFI_WAIT) p.status = ""
        }
        if (chats && p.chatEnabled) {
            if (waiting && (force || idle(p.chatStatus))) p.chatStatus = WIFI_WAIT
            else if (!waiting && p.chatStatus == WIFI_WAIT) p.chatStatus = ""
        }
    }

    /** (Re)schedules the hourly job, or cancels everything when backup is switched off. */
    fun apply(ctx: Context) {
        val p = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!p.backupEnabled) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(NOW)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints(p, p.chargingOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        noteWifiWait(ctx)
    }

    /** "Back up now": runs as soon as the network rule is met (ignores the charging rule, you asked for it). */
    fun runNow(ctx: Context) {
        val p = Prefs(ctx)
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(p, false))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        noteWifiWait(ctx, chats = false, force = true)
    }

    /**
     * WhatsApp chats: every 6 hours. A run that finds the same WhatsApp backup file as last time ends after one
     * folder listing and one tiny request, so this is cheap. Uses the same Wi-Fi / charging rules as the photo backup.
     */
    fun applyChat(ctx: Context, delayFirstRun: Boolean = false) {
        val p = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!p.chatEnabled) {
            wm.cancelUniqueWork(CHAT_PERIODIC)
            wm.cancelUniqueWork(CHAT_NOW)
            return
        }
        val request = PeriodicWorkRequestBuilder<ChatBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints(p, p.chargingOnly))
            .apply { if (delayFirstRun) setInitialDelay(6, TimeUnit.HOURS) } // after a stop: not again straight away
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(CHAT_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        noteWifiWait(ctx)
    }

    /** "Back up chats now". [force] = decrypt and compare again even if the WhatsApp file looks unchanged. */
    fun runChatNow(ctx: Context, force: Boolean, mediaDays: Int = ChatBackupWorker.DEFAULT_MEDIA_DAYS) {
        val p = Prefs(ctx)
        val request = OneTimeWorkRequestBuilder<ChatBackupWorker>()
            .setConstraints(constraints(p, false))
            .setInputData(Data.Builder().putBoolean("force", force).putInt("mediaDays", mediaDays).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(CHAT_NOW, ExistingWorkPolicy.KEEP, request)
        noteWifiWait(ctx, photos = false, force = true)
    }

    /**
     * Stops a chat backup that is running or queued, and only that: files already uploaded stay on the server and nothing
     * is deleted anywhere. Chat backup stays switched on; the 6-hourly job comes back with its first run 6 hours later.
     */
    fun stopChat(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(CHAT_NOW)
        wm.cancelUniqueWork(CHAT_PERIODIC)
        applyChat(ctx, delayFirstRun = true)
    }
}
