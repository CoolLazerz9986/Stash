// Path: android/app/src/main/java/app/stash/android/BackupScheduler.kt
package app.stash.android

import android.content.Context
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

    private fun constraints(p: Prefs, charging: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(charging)
        .build()

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
    }

    /** "Back up now": runs as soon as the network rule is met (ignores the charging rule, you asked for it). */
    fun runNow(ctx: Context) {
        val p = Prefs(ctx)
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(p, false))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * WhatsApp chats: every 6 hours. A run that finds the same WhatsApp backup file as last time ends after one
     * folder listing and one tiny request, so this is cheap. Uses the same Wi-Fi / charging rules as the photo backup.
     */
    fun applyChat(ctx: Context) {
        val p = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!p.chatEnabled) {
            wm.cancelUniqueWork(CHAT_PERIODIC)
            wm.cancelUniqueWork(CHAT_NOW)
            return
        }
        val request = PeriodicWorkRequestBuilder<ChatBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints(p, p.chargingOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(CHAT_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** "Back up chats now". [force] = decrypt and compare again even if the WhatsApp file looks unchanged. */
    fun runChatNow(ctx: Context, force: Boolean) {
        val p = Prefs(ctx)
        val request = OneTimeWorkRequestBuilder<ChatBackupWorker>()
            .setConstraints(constraints(p, false))
            .setInputData(Data.Builder().putBoolean("force", force).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(CHAT_NOW, ExistingWorkPolicy.KEEP, request)
    }
}
