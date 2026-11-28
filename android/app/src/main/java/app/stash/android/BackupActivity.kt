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
    private lateinit var scheduleButton: android.widget.Button
    private lateinit var foldersButton: android.widget.Button
    private lateinit var typesButton: android.widget.Button
    private lateinit var excludeButton: android.widget.Button
    private lateinit var cancelButton: android.widget.Button
    private lateinit var chatSwitch: Switch
    private lateinit var chatKeyField: EditText
    private lateinit var chatFolderButton: android.widget.Button
    private lateinit var chatStatusText: TextView
    private var updating = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }

    /** Only asks for the notification permission (progress and problem alerts). Chat backup works without it. */
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Adds a folder to phone backup. */
    private val backupFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.fileTrees = prefs.fileTrees + uri.toString()
                if (prefs.backupEnabled) BackupScheduler.runNow(this)
            } catch (e: SecurityException) {
                toast("Could not keep access to that folder: ${e.message}")
            }
            refresh()
        }
    }

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
        scheduleButton = Ui.button(this, "") { chooseSchedule() }
        foldersButton = Ui.button(this, "") { chooseFolders() }
        typesButton = Ui.button(this, "") { chooseTypes() }
        excludeButton = Ui.button(this, "") { chooseExclusions() }
        cancelButton = Ui.button(this, "Cancel current backup", Ui.Kind.DANGER) { cancelBackup() }

        // Same layout idea as the website: grey page, one rounded card per group.
        val serverCard = Ui.card(
            this,
            serverText,
            Ui.button(this, "Change server") { startActivity(Intent(this, SetupActivity::class.java)) },
            Ui.gap(this, 4),
        )
        val backupCard = Ui.card(
            this,
            heading("Backup settings"),
            scheduleButton,
            foldersButton,
            typesButton,
            excludeButton,
            Ui.toggle(this, "Include files already in these folders", prefs.includeExisting) { prefs.includeExisting = it },
            Ui.toggle(this, "Wi-Fi only", prefs.wifiOnly) { prefs.wifiOnly = it; applyRules() },
            Ui.toggle(this, "Only while charging", prefs.chargingOnly) { prefs.chargingOnly = it; applyRules() },
            Ui.muted(this, "Wi-Fi and charging rules also apply to chat backup."),
            statusText,
            cancelButton,
            Ui.gap(this, 4),
        )
        val note = Ui.muted(
            this,
            "Backup is one-way: nothing is ever deleted from your phone. Files the server already has (same content) are skipped. " +
                "Hidden files are left out. Times are approximate: Android may delay a run.",
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
            Ui.button(this, "Back up older media…") { olderMedia() },
            Ui.button(this, "Stop") { stopChat() },
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
                Ui.header(this, "Phone backup", "Files and WhatsApp chats to your Stash"),
                serverCard, backupCard, note, chatCard,
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
        chatSwitch.isChecked = prefs.chatEnabled
        updating = false
        serverText.text = "Server: ${prefs.serverUrl}"
        scheduleButton.text = "Backup schedule: " + scheduleLabel(prefs.backupHours)
        val folders = prefs.fileTrees
        foldersButton.text = "Folders: " + if (folders.isEmpty()) "none chosen" else folders.map { treeName(it) }.sorted().joinToString(", ")
        val off = FileFilter.Type.values().count { it.name in prefs.offTypes }
        typesButton.text = "File types: " + if (off == 0) "all" else "${FileFilter.Type.values().size - off} of ${FileFilter.Type.values().size}"
        excludeButton.text = "Excluded extensions: " +
            FileFilter.parseExtensions(prefs.excludedExtensions).joinToString(", ").ifEmpty { "none" }
        cancelButton.isEnabled = prefs.backupEnabled
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

    private fun heading(text: String) = Ui.body(this, text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }

    private fun applyRules() {
        BackupScheduler.apply(this)
        BackupScheduler.applyChat(this)
    }

    private fun scheduleLabel(hours: Int) = when (hours) {
        0 -> "Off"
        1 -> "Every hour"
        else -> "Every $hours hours"
    }

    private fun chooseSchedule() {
        val hours = intArrayOf(0, 1, 6, 12, 24)
        val labels = Array(hours.size) { scheduleLabel(hours[it]) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Backup schedule")
            .setSingleChoiceItems(labels, hours.indexOf(prefs.backupHours)) { d, which ->
                d.dismiss()
                setSchedule(hours[which])
            }
            .setNegativeButton("Close", null)
            .show()
        Ui.styleDialog(dialog)
    }

    private fun setSchedule(hours: Int) {
        if (hours == 0) {
            prefs.backupHours = 0
            BackupScheduler.apply(this)
            prefs.status = "Off"
            refresh()
        } else if (prefs.backupEnabled) {
            prefs.backupHours = hours
            BackupScheduler.apply(this)
            refresh()
        } else {
            enable(hours)
        }
    }

    private fun cancelBackup() {
        BackupScheduler.cancelRun(this)
        prefs.status = "Cancelled. The schedule stays on; uploaded files are kept."
        refresh()
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
    private fun enable(hours: Int) {
        if (prefs.fileTrees.isEmpty()) {
            toast("Choose at least one folder first")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !Perms.canNotify(this)) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        prefs.status = "Connecting…"
        Thread {
            try {
                connect()
                runOnUiThread {
                    prefs.enabledSince = System.currentTimeMillis()
                    prefs.backupHours = hours
                    prefs.status = "Starting…"
                    BackupScheduler.apply(this)
                    BackupScheduler.runNow(this)
                    refresh()
                    if (BackupScheduler.waitingForWifi(this)) queued("Backup")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    prefs.backupHours = 0
                    prefs.status = "Off"
                    toast(e.message ?: "Could not connect")
                    refresh()
                }
            }
        }.start()
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

    /** Chat backup normally takes the media of the last day only. This takes the media of older messages too. */
    private fun olderMedia() {
        if (!prefs.chatEnabled) {
            toast("Switch chat backup on first")
            return
        }
        val labels = arrayOf("Last week", "Last month", "Last 3 months", "Last 6 months", "Last year")
        val days = intArrayOf(7, 30, 90, 180, 365)
        var pick = 0
        val dialog = AlertDialog.Builder(this)
            .setTitle("Back up media from")
            .setSingleChoiceItems(labels, 0) { _, which -> pick = which }
            .setPositiveButton("Back up") { _, _ ->
                BackupScheduler.runChatNow(this, true, days[pick])
                queued("Chat backup")
            }
            .setNegativeButton("Cancel", null)
            .show()
        Ui.styleDialog(dialog)
    }

    /** Just stops. Whatever was already uploaded stays on the server; deleting is done from the file list. */
    private fun stopChat() {
        BackupScheduler.stopChat(this)
        prefs.chatStatus = "Stopped. Files already uploaded are kept."
        refresh()
        toast("Stopped. Nothing was deleted.")
    }

    /** "primary:Documents/Work" -> "Work" */
    private fun treeName(tree: String): String =
        android.net.Uri.decode(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Storage root" }

    /** Lists the chosen folders: tap one to remove it, or add another. */
    private fun chooseFolders() {
        val trees = prefs.fileTrees.sortedBy { treeName(it) }
        val labels = Array(trees.size) { "✕  " + android.net.Uri.decode(trees[it]).substringAfterLast("/tree/") }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (trees.isEmpty()) "No folders chosen yet" else "Folders (tap to remove)")
            .setPositiveButton("Add folder") { _, _ -> backupFolderPicker.launch(null) }
            .setNegativeButton("Close", null)
        if (trees.isNotEmpty()) builder.setItems(labels) { _, which -> removeFolder(trees[which]) }
        Ui.styleDialog(builder.show())
    }

    private fun removeFolder(tree: String) {
        prefs.fileTrees = prefs.fileTrees - tree
        if (tree != prefs.chatTree) { // chat backup may be using the same folder
            try {
                contentResolver.releasePersistableUriPermission(android.net.Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // the access was already gone
            }
        }
        refresh()
    }

    private fun chooseTypes() {
        val types = FileFilter.Type.values()
        val checked = BooleanArray(types.size) { types[it].name !in prefs.offTypes }
        val dialog = AlertDialog.Builder(this)
            .setTitle("File types to include")
            .setMultiChoiceItems(Array(types.size) { types[it].label }, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                prefs.offTypes = types.filterIndexed { i, _ -> !checked[i] }.map { it.name }.toSet()
                refresh()
            }
            .setNegativeButton("Close", null)
            .show()
        Ui.styleDialog(dialog)
    }

    private fun chooseExclusions() {
        val field = Ui.field(this, "e.g. tmp, log, iso").apply {
            setText(prefs.excludedExtensions)
            setSingleLine(true)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Exclude extensions")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.excludedExtensions = field.text.toString().trim()
                refresh()
            }
            .setNegativeButton("Close", null)
            .show()
        Ui.styleDialog(dialog)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
