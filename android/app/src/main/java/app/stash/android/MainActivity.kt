// Path: android/app/src/main/java/app/stash/android/MainActivity.kt
package app.stash.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException

/** Hosts the Stash website (served by your own server) in a WebView and adds what a browser tab can't do. */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private var base = ""
    private var hasError = false
    private var loadToken = 0
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.serverUrl.isEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // close the website's open dialog (file viewer / confirm box) first, otherwise leave the app
                web.evaluateJavascript(
                    "(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 1}return 0})()"
                ) { result -> if (result != "1") moveTaskToBack(true) }
            }
        })
        load(intent.getStringExtra(EXTRA_PATH) ?: "/")
    }

    /** Started again with a page to show (the "Open chats" button on the backup screen). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null && ::web.isInitialized) load(path)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true // the site remembers theme / view / sort in localStorage
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(Bridge(), "StashApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.toString()
                if (s == base || s.startsWith("$base/") || s.startsWith("$base?")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url)) // anything off-server opens in the real browser
                } catch (e: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hasError = false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    hasError = true
                    showError("Can't reach $base\n\n${error.description}\n\nIs the server running and is this phone on the same network?")
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!hasError) errorBox.visibility = View.GONE
                CookieManager.getInstance().flush()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // the site's "Upload" button / file input
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = try {
                    params.createIntent()
                } catch (e: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    chooser.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        // the site's single-file "Download" links
        web.setDownloadListener { url, _, contentDisposition, _, _ ->
            if (url.startsWith(base)) {
                Downloader.start(
                    this, url, "GET", null, CookieManager.getInstance().getCookie(url),
                    Downloader.fileName(contentDisposition) ?: "download",
                )
            }
        }

        errorText = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Ui.themeBackground(this@MainActivity))
            visibility = View.GONE
            addView(errorText)
            addView(Ui.gap(this@MainActivity, 16))
            addView(Ui.button(this@MainActivity, "Try again") { load() })
            addView(Ui.button(this@MainActivity, "Change server") {
                startActivity(Intent(this@MainActivity, SetupActivity::class.java))
            })
        }

        setContentView(FrameLayout(this).apply {
            addView(web)
            addView(errorBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }

    private fun showError(message: String) {
        errorText.text = message
        errorBox.visibility = View.VISIBLE
    }

    /** Same wording as the setup screen, so an unreachable server reads the same everywhere. */
    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "Can't find that address."
        is ConnectException -> "The server refused the connection."
        is SocketTimeoutException -> "Timed out."
        else -> e.message ?: "Could not connect"
    }

    /**
     * [path] is a page on your server: "/" (files) or "/chats". Only paths starting with "/" are accepted.
     * The server is checked first with the app's own 8-second connect timeout (StashApi.session): if it does not
     * answer, the error screen shows at once instead of after the WebView's own (very long) give-up time.
     */
    private fun load(path: String = "/") {
        base = prefs.serverUrl
        hasError = false
        errorBox.visibility = View.GONE
        val server = base
        val target = server + (if (path.startsWith("/")) path else "/")
        val token = ++loadToken
        Thread {
            val problem: String? = try {
                StashApi.session(server)
                null
            } catch (e: Exception) {
                friendly(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || token != loadToken) return@runOnUiThread // a newer load took over
                if (problem == null) {
                    web.loadUrl(target)
                } else {
                    hasError = true
                    web.stopLoading()
                    showError("Can't reach $server\n\n$problem\n\nIs the server running and is this phone on the same network?")
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (!::web.isInitialized) return
        val url = prefs.serverUrl
        if (url.isEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        } else if (url != base || hasError) {
            // address changed in settings, or we come back (e.g. from "Change server" with the same address) while the
            // "Can't reach" screen is still showing: check again instead of leaving the old error on screen
            load()
        }
    }

    override fun onPause() {
        if (::web.isInitialized) CookieManager.getInstance().flush() // keep the 30-day login across restarts
        super.onPause()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    /** Exposed to the website as window.StashApp (only exists inside this app). */
    private inner class Bridge {
        /** The zip download is a POST, which the WebView can't hand to a downloader, so the page asks us to do it. */
        @JavascriptInterface
        fun downloadZip(ids: String) {
            if (!Regex("^(all|[0-9a-f]{32}(,[0-9a-f]{32})*)$").matches(ids)) return
            Downloader.start(
                this@MainActivity, "$base/api/zip", "POST", "ids=" + URLEncoder.encode(ids, "UTF-8"),
                CookieManager.getInstance().getCookie("$base/"), "stash.zip",
            )
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { startActivity(Intent(this@MainActivity, BackupActivity::class.java)) }
        }
    }

    companion object {
        /** Intent extra: which page of the server to open ("/chats"). */
        const val EXTRA_PATH = "path"
    }
}
