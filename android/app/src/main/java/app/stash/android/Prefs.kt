// Path: android/app/src/main/java/app/stash/android/Prefs.kt
package app.stash.android

import android.content.Context
import android.content.SharedPreferences

/** All app settings and the backup status, in one SharedPreferences file. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("stash", Context.MODE_PRIVATE)

    /** e.g. http://192.168.1.50:8080 (no trailing slash). Empty = not set up yet. */
    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) = sp.edit().putString("serverUrl", v).apply()

    /** Key the server hands to a logged-in session (GET /api/app-key). Empty if the server has no password. */
    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var backupEnabled: Boolean
        get() = sp.getBoolean("backupEnabled", false)
        set(v) = sp.edit().putBoolean("backupEnabled", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", true)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()

    var chargingOnly: Boolean
        get() = sp.getBoolean("chargingOnly", false)
        set(v) = sp.edit().putBoolean("chargingOnly", v).apply()

    var includePhotos: Boolean
        get() = sp.getBoolean("includePhotos", true)
        set(v) = sp.edit().putBoolean("includePhotos", v).apply()

    var includeVideos: Boolean
        get() = sp.getBoolean("includeVideos", true)
        set(v) = sp.edit().putBoolean("includeVideos", v).apply()

    /** false = only back up files added after backup was switched on. */
    var includeExisting: Boolean
        get() = sp.getBoolean("includeExisting", true)
        set(v) = sp.edit().putBoolean("includeExisting", v).apply()

    /** Folder (MediaStore bucket) names to back up. Empty = all folders. */
    var buckets: Set<String>
        get() = sp.getStringSet("buckets", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("buckets", v.toSet()).apply()

    var enabledSince: Long
        get() = sp.getLong("enabledSince", 0L)
        set(v) = sp.edit().putLong("enabledSince", v).apply()

    var lastRun: Long
        get() = sp.getLong("lastRun", 0L)
        set(v) = sp.edit().putLong("lastRun", v).apply()

    /** Human readable status written by the backup worker and shown in the settings screen. */
    var status: String
        get() = sp.getString("status", "") ?: ""
        set(v) = sp.edit().putString("status", v).apply()

    // ---- WhatsApp chat backup (ChatBackupWorker). Own switch, own status; shares serverUrl and apiKey. ----

    var chatEnabled: Boolean
        get() = sp.getBoolean("chatEnabled", false)
        set(v) = sp.edit().putBoolean("chatEnabled", v).apply()

    /** The 64-digit WhatsApp backup key as the user typed it. Stays in the app's private storage (allowBackup=false). */
    var chatKey: String
        get() = sp.getString("chatKey", "") ?: ""
        set(v) = sp.edit().putString("chatKey", v).apply()

    /** Folder the user picked with the system folder picker (a persisted content:// tree URI). Empty = not chosen. */
    var chatTree: String
        get() = sp.getString("chatTree", "") ?: ""
        set(v) = sp.edit().putString("chatTree", v).apply()

    /** "server|file name|size|modified" of the last WhatsApp backup file that reached the server. */
    var chatDone: String
        get() = sp.getString("chatDone", "") ?: ""
        set(v) = sp.edit().putString("chatDone", v).apply()

    var chatLastRun: Long
        get() = sp.getLong("chatLastRun", 0L)
        set(v) = sp.edit().putLong("chatLastRun", v).apply()

    var chatStatus: String
        get() = sp.getString("chatStatus", "") ?: ""
        set(v) = sp.edit().putString("chatStatus", v).apply()

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)
}

/** "192.168.1.50:8080" -> "http://192.168.1.50:8080". Returns null if it can't be an address. */
fun normalizeServerUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "http://$s"
    s = s.trimEnd('/')
    val uri = try { java.net.URI(s) } catch (e: Exception) { return null }
    if (uri.host.isNullOrEmpty()) return null
    return s
}
