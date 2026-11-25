// Path: android/app/src/main/java/app/stash/android/BackupActivity.kt
package app.stash.android

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Native settings screen: server address, the photo/video auto backup and the WhatsApp chat backup. */
class BackupActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var db: BackupDb
    private lateinit var serverText: TextView
    private lateinit var statusText: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var foldersButton: android.widget.Button
    private lateinit var chatSwitch: Switch
    private lateinit var chatKeyField: EditText
    private lateinit var chatFolderButton: android.widget.Button
    private lateinit var chatStatusText: TextView
    private var updating = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Perms.hasMedia(this)) {
            enable()
        } else {
            toast("Photo access is needed to back up your photos and videos")
            refresh()
        }
    }

    /** Only asks for the notification permission (progress and problem alerts). Chat backup works without it. */
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** System folder picker: gives read access to one folder tree without any storage permission. */
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.chatTree = uri.toString()
                prefs.chatDone = "" // a new folder: look at its backup file again
            } catch (e: SecurityException) {
                toast("Could not keep access to that folder: ${e.message}")
            }
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = BackupDb(this)

        serverText = Ui.body(this)
        statusText = Ui.body(this)
        enableSwitch = Ui.toggle(this, "Back up this phone to Stash", prefs.backupEnabled) { on ->
            if (!updating) if (on) turnOn() else turnOff()
        }
        foldersButton = Ui.button(this, "") { chooseFolders() }

        // Same layout idea as the website: grey page, one rounded card per group.
        val serverCard = Ui.card(
            this,
            serverText,
            Ui.button(this, "Change server") { startActivity(Intent(this, SetupActivity::class.java)) },
            Ui.gap(this, 4),
        )
        val scheduleCard = Ui.card(
            this,
            enableSwitch,
            Ui.toggle(this, "Wi-Fi only", prefs.wifiOnly) { prefs.wifiOnly = it; BackupScheduler.apply(this); BackupScheduler.applyChat(this) },
            Ui.toggle(this, "Only while charging", prefs.chargingOnly) { prefs.chargingOnly = it; BackupScheduler.apply(this); BackupScheduler.applyChat(this) },
        )
        val contentCard = Ui.card(
            this,
            Ui.toggle(this, "Back up photos", prefs.includePhotos) { prefs.includePhotos = it },
            Ui.toggle(this, "Back up videos", prefs.includeVideos) { prefs.includeVideos = it },
            Ui.toggle(this, "Include files already on this phone", prefs.includeExisting) { prefs.includeExisting = it },
            foldersButton,
            Ui.gap(this, 4),
        )
        val statusCard = Ui.card(
            this,
            statusText,
            Ui.button(this, "Back up now", Ui.Kind.PRIMARY) { backupNow() },
            Ui.gap(this, 4),
        )
        val note = Ui.muted(
            this,
            "Backup is one-way: nothing is ever deleted from your phone. Files the server already has (same content) are skipped. " +
                "It runs about once an hour in the background. If it says the key was rejected, switch backup off and on again while logged in.",
        )

        // ---- WhatsApp chats (first version, plain on purpose: the screen will be restyled later) ----
        chatSwitch = Ui.toggle(this, "Back up WhatsApp chats", prefs.chatEnabled) { on ->
            if (!updating) if (on) turnOnChat() else turnOffChat()
        }
        chatKeyField = Ui.field(this, "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        chatFolderButton = Ui.button(this, "") { folderPicker.launch(WaSource.pickerStart()) }
        chatStatusText = Ui.body(this)
        val chatCard = Ui.card(
            this,
            Ui.body(this, "WhatsApp chats").apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
            Ui.muted(
                this,
                "1. In WhatsApp: Settings > Chats > Chat backup > End-to-end encrypted backup, switch it on with a 64-digit key and tap Back up.\n" +
                    "2. Paste that key below and save it. It is decrypted on this phone; the server never gets the key.\n" +
                    "3. Choose the WhatsApp folder (Android/media/com.whatsapp/WhatsApp, or WhatsApp on older phones).",
            ),
            chatKeyField,
            Ui.button(this, "Save key") { saveChatKey() },
            chatFolderButton,
            chatSwitch,
            chatStatusText,
            Ui.button(this, "Back up chats now", Ui.Kind.PRIMARY) { chatNow() },
            Ui.button(this, "Open chats") {
                startActivity(
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PATH, "/chats")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            },
            Ui.gap(this, 4),
        )

        setContentView(
            SwipeBackLayout(this, Ui.page(
                this,
                Ui.header(this, "Phone backup", "Photos, videos and WhatsApp chats to your Stash"),
                serverCard, scheduleCard, contentCard, statusCard, note, chatCard,
            ))
        )
    }

    override fun onStart() {
        super.onStart()
        prefs.listen(prefListener)
        watchNetwork()
        refresh()
    }

    override fun onStop() {
        prefs.unlisten(prefListener)
        unwatchNetwork()
        super.onStop()
    }

    /** Refreshes the screen when the phone gains or loses a network, so "Waiting for Wi-Fi" appears and clears on its own. */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                runOnUiThread { if (!isFinishing) refresh() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { if (!isFinishing) refresh() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: RuntimeException) {
            // no network watching: the screen still updates whenever it is opened or a setting changes
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (e: RuntimeException) {
        }
    }

    /** Shows the short toast when a queued run will not start because "Wi-Fi only" is on and there is no Wi-Fi. */
    private fun queued(what: String) {
        toast(
            if (BackupScheduler.waitingForWifi(this)) "$what queued. It starts when Wi-Fi connects (Wi-Fi only is on)"
            else "$what queued"
        )
    }

    private fun refresh() {
        BackupScheduler.noteWifiWait(this) // only touches an empty / idle / already-"Waiting for Wi-Fi" status
        updating = true
        enableSwitch.isChecked = prefs.backupEnabled
        chatSwitch.isChecked = prefs.chatEnabled
        updating = false
        serverText.text = "Server: ${prefs.serverUrl}"
        foldersButton.text = "Folders: " + if (prefs.buckets.isEmpty()) "all" else prefs.buckets.sorted().joinToString(", ")
        statusText.text = buildString {
            append("Status: ").append(prefs.status.ifEmpty { if (prefs.backupEnabled) "Waiting for the next run" else "Off" })
            if (prefs.lastRun > 0) append("\nLast finished: ").append(DateFormat.getDateTimeInstance().format(Date(prefs.lastRun)))
            append("\nFiles backed up so far: ").append(db.count())
        }
        chatKeyField.hint = if (Crypt15.parseKey(prefs.chatKey) != null) "Key saved (paste a new one to replace it)" else "Paste the 64-digit key"
        chatFolderButton.text = if (prefs.chatTree.isEmpty()) "Choose the WhatsApp folder" else "WhatsApp folder: " + folderLabel()
        chatStatusText.text = buildString {
            append("Status: ").append(prefs.chatStatus.ifEmpty { if (prefs.chatEnabled) "Waiting for the next run" else "Off" })
            if (prefs.chatLastRun > 0) append("\nLast finished: ").append(DateFormat.getDateTimeInstance().format(Date(prefs.chatLastRun)))
        }
    }

    /** The last part of the picked folder's name, e.g. "WhatsApp", for the button text. */
    private fun folderLabel(): String =
        android.net.Uri.decode(prefs.chatTree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "chosen" }

    private fun turnOff() {
        prefs.backupEnabled = false
        BackupScheduler.apply(this)
        prefs.status = "Off"
        refresh()
    }

    private fun turnOn() {
        if (Perms.hasMedia(this)) enable() else permissions.launch(Perms.requestList())
    }

    /**
     * Checks the server (api 2 or newer) and, when it has a password, makes sure [Prefs.apiKey] is set by asking for it
     * with the website login cookie. Blocking: call from a background thread. Throws IOException with a readable message.
     */
    private fun connect() {
        val base = prefs.serverUrl
        val session = StashApi.session(base)
        if (session.api < 2) throw IOException("This server is too old: replace server.py with the updated version")
        if (session.authRequired) {
            val cookie = CookieManager.getInstance().getCookie("$base/")
            if (!cookie.isNullOrEmpty() && cookie.contains("stash_session=")) {
                prefs.apiKey = StashApi.fetchAppKey(base, cookie)
            } else if (prefs.apiKey.isEmpty()) {
                throw IOException("Log in to Stash in the app first, then come back here")
            }
        }
    }

    /** Connects to the server (fetching the API key through the website login) and starts the schedule. */
    private fun enable() {
        prefs.status = "Connecting…"
        Thread {
            try {
                connect()
                runOnUiThread {
                    prefs.enabledSince = System.currentTimeMillis()
                    prefs.backupEnabled = true
                    prefs.status = "Starting…"
                    BackupScheduler.apply(this)
                    BackupScheduler.runNow(this)
                    refresh()
                    if (BackupScheduler.waitingForWifi(this)) queued("Backup")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    prefs.backupEnabled = false
                    prefs.status = "Off"
                    toast(e.message ?: "Could not connect")
                    refresh()
                }
            }
        }.start()
    }

    private fun backupNow() {
        if (!prefs.backupEnabled) {
            toast("Switch backup on first")
            return
        }
        BackupScheduler.runNow(this)
        queued("Backup")
    }

    // ---- WhatsApp chats ----

    private fun saveChatKey() {
        val text = chatKeyField.text.toString()
        if (text.isBlank()) {
            toast("Paste the 64-digit key first")
            return
        }
        if (Crypt15.parseKey(text) == null) {
            toast("That is not a 64-digit hex key (0-9 and a-f, spaces are fine)")
            return
        }
        prefs.chatKey = text.trim()
        prefs.chatDone = "" // a new key: decrypt again
        chatKeyField.setText("")
        toast("Key saved")
        refresh()
    }

    private fun turnOnChat() {
        if (Crypt15.parseKey(prefs.chatKey) == null) {
            toast("Save the 64-digit WhatsApp key first")
            refresh()
            return
        }
        if (prefs.chatTree.isEmpty()) {
            toast("Choose the WhatsApp folder first")
            refresh()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !Perms.canNotify(this)) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        prefs.chatStatus = "Connecting…"
        Thread {
            try {
                connect()
                runOnUiThread {
                    prefs.chatEnabled = true
                    prefs.chatStatus = "Starting…"
                    BackupScheduler.applyChat(this)
                    BackupScheduler.runChatNow(this, true)
                    refresh()
                    if (BackupScheduler.waitingForWifi(this)) queued("Chat backup")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    prefs.chatEnabled = false
                    prefs.chatStatus = "Off"
                    toast(e.message ?: "Could not connect")
                    refresh()
                }
            }
        }.start()
    }

    private fun turnOffChat() {
        prefs.chatEnabled = false
        BackupScheduler.applyChat(this)
        prefs.chatStatus = "Off"
        refresh()
    }

    private fun chatNow() {
        if (!prefs.chatEnabled) {
            toast("Switch chat backup on first")
            return
        }
        BackupScheduler.runChatNow(this, true)
        queued("Chat backup")
    }

    private fun chooseFolders() {
        if (!Perms.hasMedia(this)) {
            toast("Switch backup on first so the app can see your photo folders")
            return
        }
        Thread {
            val names = MediaSource.buckets(this).toList()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (names.isEmpty()) {
                    toast("No photo or video folders found")
                    return@runOnUiThread
                }
                val checked = BooleanArray(names.size) { names[it] in prefs.buckets }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Folders to back up")
                    .setMultiChoiceItems(names.toTypedArray(), checked) { _, index, isChecked -> checked[index] = isChecked }
                    .setPositiveButton("Save") { _, _ ->
                        prefs.buckets = names.filterIndexed { i, _ -> checked[i] }.toSet()
                        refresh()
                    }
                    .setNeutralButton("All folders") { _, _ ->
                        prefs.buckets = emptySet()
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                Ui.styleDialog(dialog)
            }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
