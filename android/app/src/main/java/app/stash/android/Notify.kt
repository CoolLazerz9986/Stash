// Path: android/app/src/main/java/app/stash/android/Notify.kt
package app.stash.android

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * Every phone notification of the app, in one place (the workers and the Downloader used to build their own).
 * Channels keep their old ids, so a user's per-channel settings survive the update.
 * Everything here is a no-op when the user has switched notifications off for the app: nothing crashes, the callers
 * keep their toasts / in-app status instead. ([running] and [downloadProgress] only BUILD a notification, because a
 * foreground service needs one even then.)
 */
object Notify {
    /** Pass as the percent when the progress is not known: the bar moves without a number. */
    const val INDETERMINATE = -1

    private const val CH_PROGRESS = "backup_progress"
    private const val CH_ALERT = "backup_alerts"
    private const val CH_DL_PROGRESS = "download_progress"
    private const val CH_DL_DONE = "download_done"
    private val made = HashSet<String>()

    /** False when notifications are blocked: the Android 13+ permission was refused, or they are off in system settings. */
    fun enabled(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun manager(ctx: Context): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    @Synchronized
    private fun channel(ctx: Context, id: String, name: String, importance: Int) {
        if (!made.add(id)) return
        manager(ctx).createNotificationChannel(NotificationChannel(id, name, importance))
    }

    /** Tapping a backup notification opens the Phone backup screen. */
    private fun openBackup(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, BackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---- backup ------------------------------------------------------------------------------------------

    /**
     * The ongoing "backup is running" notification (the one WorkManager shows as a foreground service).
     * [pct] 0..100 = a real bar; anything else ([INDETERMINATE]) = a moving bar with no percent.
     */
    fun running(ctx: Context, title: String, text: String, pct: Int): Notification {
        channel(ctx, CH_PROGRESS, "Backup progress", NotificationManager.IMPORTANCE_LOW)
        val known = pct in 0..100
        return Notification.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openBackup(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (known) pct else 0, !known)
            .build()
    }

    /** Refreshes a notification that already shows as a foreground service (same [id]). Call only after setForeground worked. */
    fun update(ctx: Context, id: Int, n: Notification) {
        if (enabled(ctx)) manager(ctx).notify(id, n)
    }

    /** A warning: something needs attention. Posting the same [id] again replaces the text instead of piling up. */
    fun alert(ctx: Context, id: Int, title: String, text: String) {
        if (!enabled(ctx)) return
        channel(ctx, CH_ALERT, "Backup problems", NotificationManager.IMPORTANCE_DEFAULT)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openBackup(ctx))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager(ctx).notify(id, n)
    }

    fun clear(ctx: Context, id: Int) = manager(ctx).cancel(id)

    // ---- downloads ---------------------------------------------------------------------------------------

    /**
     * Builds the "download in progress" notification (also the one a foreground service must show). [total] <= 0 = size
     * not known yet (a zip is built by the server before the first byte). [cancel] adds a Cancel button.
     */
    fun downloadProgress(ctx: Context, name: String, text: String, done: Long, total: Long, cancel: PendingIntent?): Notification {
        channel(ctx, CH_DL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
        val known = total > 0
        val b = Notification.Builder(ctx, CH_DL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(1000, if (known) (done * 1000 / total).toInt().coerceIn(0, 1000) else 0, !known)
        if (cancel != null) {
            b.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), "Cancel", cancel,
                ).build()
            )
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /** Refreshes the progress notification of [id] (already showing as a foreground service). */
    fun downloading(ctx: Context, id: Int, name: String, text: String, done: Long, total: Long, cancel: PendingIntent? = null) {
        if (!enabled(ctx)) return
        manager(ctx).notify(id, downloadProgress(ctx, name, text, done, total, cancel))
    }

    private fun viewable(mime: String) =
        mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime.startsWith("text/") || mime == "application/pdf"

    /** Posts the result of [id]. Tap = open the file, or the system Downloads list for zips and the like. */
    fun downloaded(ctx: Context, id: Int, name: String, uri: Uri, mime: String) {
        if (!enabled(ctx)) return
        channel(ctx, CH_DL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        val open = if (viewable(mime)) {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH_DL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(name)
            .setContentText("Saved to Downloads/Stash")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        manager(ctx).notify(id, n)
    }

    fun downloadFailed(ctx: Context, id: Int, name: String, reason: String) {
        if (!enabled(ctx)) return
        channel(ctx, CH_DL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        val n = Notification.Builder(ctx, CH_DL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed: $name")
            .setContentText(reason)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        manager(ctx).notify(id, n)
    }
}
