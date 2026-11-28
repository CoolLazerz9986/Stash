package app.stash.android

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Asks for the server address on first launch (and when you choose "Change server"). */
class SetupActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var connectBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        input = Ui.field(this, "http://192.168.1.50:8080").apply {
            setText(prefs.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> connect(); true }
        }
        status = Ui.muted(this).apply { visibility = View.GONE }
        connectBtn = Ui.button(this, "Connect", Ui.Kind.PRIMARY) { connect() }

        val intro = Ui.body(
            this,
            "Type the address of your Stash server. It's printed when the server starts, for example http://192.168.1.50:8080. " +
                "Your phone has to be on the same network (or have a route to it).",
        )
        setContentView(
            Ui.page(
                this,
                Ui.header(this, "Connect to your Stash", "Your own file vault"),
                Ui.card(this, intro, input, status),
                connectBtn,
            )
        )
    }

    private fun showStatus(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) Ui.pal(this).danger else Ui.pal(this).muted)
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun connect() {
        val base = normalizeServerUrl(input.text.toString())
        if (base == null) {
            showStatus("That doesn't look like an address. Example: http://192.168.1.50:8080", error = true)
            return
        }
        connectBtn.isEnabled = false
        showStatus("Checking $base …")
        Thread {
            val error: String? = try {
                StashApi.session(base)
                null
            } catch (e: Exception) {
                friendly(e)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                connectBtn.isEnabled = true
                if (error != null) showStatus(error, error = true) else save(base)
            }
        }.start()
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "Can't find that address. Check the spelling."
        is ConnectException -> "The server refused the connection. Is it running, and is the port right?"
        is SocketTimeoutException -> "Timed out. Check the address and that the phone is on the same network."
        else -> e.message ?: "Could not connect"
    }

    private fun save(base: String) {
        val old = prefs.serverUrl
        if (old == base) { // nothing changed (opened from settings)
            if (old.isNotEmpty()) { finish(); return }
        } else if (old.isNotEmpty()) {
            // A different server knows nothing about what was backed up to the old one: start clean, ask to reconnect.
            prefs.backupHours = 0
            prefs.apiKey = ""
            BackupDb(this).clear()
            BackupScheduler.cancelRun(this)
        }
        prefs.serverUrl = base
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
