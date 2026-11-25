// Path: android/app/src/main/java/app/stash/android/MainActivity.kt
package app.stash.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlin.math.abs

/**
 * Hosts the Stash website (served by your own server) in a WebView and adds what a browser tab can't do.
 *
 * Pages: the files page lives in [web] and stays alive for the whole session. The Chats page is a second WebView
 * ([chatsView]) stacked on top of it. Sliding between them is done here, natively (View.translationX on the render
 * thread, like any Android app), instead of with CSS inside one WebView that is also loading and rendering a page.
 * The same goes for the swipe from the left edge that leaves Chats.
 */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var stack: Stack
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private var chatsView: WebView? = null
    @Volatile private var paneOpen = false // a chat is open over the chat list (set by the chats page): its own swipe closes it
    private var moving = false // a page slide is running
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
                if (moving) return // a slide is running; the next press counts
                // Back, one step at a time: 1) close the website's open dialog (file viewer / confirm box),
                // 2) let the page handle it if it can (the chats page closes an open chat, or leaves to the files page),
                // 3) on the files page, leave the app.
                val top = chatsView ?: web
                top.evaluateJavascript(
                    "(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 1}" +
                        "if(window.stashBack&&window.stashBack())return 1;return 0})()"
                ) { result ->
                    if (result != "1") {
                        if (chatsView != null) closeChats() else moveTaskToBack(true)
                    }
                }
            }
        })
        load("/", true)
        if (intent.getStringExtra(EXTRA_PATH) == "/chats") openChats(false)
    }

    /** Started again with a page to show (the "Open chats" button on the backup screen). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == "/chats" && ::web.isInitialized) openChats(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(isChats: Boolean): WebView {
        val w = WebView(this)
        w.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.setBackgroundColor(Ui.themeBackground(this)) // no white flash while a page loads or slides in
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true // the site remembers theme / view / sort in localStorage
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        w.addJavascriptInterface(Bridge(), "StashApp")

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.toString()
                if (s == base || s.startsWith("$base/") || s.startsWith("$base?")) {
                    // pages of this app slide natively instead of loading inside the page that links to them
                    val path = request.url.path ?: "/"
                    if (!isChats && path == "/chats") { openChats(); return true }
                    if (isChats && (path == "/" || path == "/index.html")) { closeChats(); return true }
                    return false
                }
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

        w.webChromeClient = object : WebChromeClient() {
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
                startAtStorageRoot(intent)
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
        w.setDownloadListener { url, _, contentDisposition, _, _ ->
            if (url.startsWith(base)) {
                Downloader.start(
                    this, url, "GET", null, CookieManager.getInstance().getCookie(url),
                    Downloader.fileName(contentDisposition) ?: "download",
                )
            }
        }
        return w
    }

    private fun buildUi() {
        web = newWebView(false)

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

        stack = Stack(this).apply {
            setBackgroundColor(Ui.themeBackground(this@MainActivity))
            addView(web)
            addView(errorBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(stack)
    }

    // ---------------------------------------------------------------- page slides

    /** The files page moves a little the other way while a page slides over it (same 24% as the website's own slide). */
    private val parallax = 0.24f

    /** Chats slides in from the right over the files page. [animate] = false just puts it there (app start, "Open chats"). */
    private fun openChats(animate: Boolean = true) {
        if (chatsView != null || moving || !::web.isInitialized) return
        paneOpen = false
        val c = newWebView(true)
        chatsView = c
        stack.addView(c, stack.indexOfChild(errorBox)) // above the files page, below the error screen
        c.loadUrl("$base/chats")
        val w = stack.width.toFloat()
        if (!animate || w <= 0f) {
            web.translationX = -w * parallax
            return
        }
        moving = true
        c.translationX = w
        c.post { // after the new view has been laid out, so the first frame of the slide is not spent on building it
            val ms = 280L
            val ease = DecelerateInterpolator(1.6f)
            web.animate().translationX(-w * parallax).setDuration(ms).setInterpolator(ease).withLayer().start()
            c.animate().translationX(0f).setDuration(ms).setInterpolator(ease).withLayer()
                .withEndAction { moving = false }.start()
        }
    }

    /** Chats slides out to the right and is thrown away (it is built fresh, with fresh data, next time it opens). */
    private fun closeChats() {
        val c = chatsView ?: return
        if (moving) return
        slideChats(c, false, 260L)
    }

    /** Animate from wherever the drag left it: [open] = back to fully open, otherwise out to the right and gone. */
    private fun slideChats(c: WebView, open: Boolean, baseMs: Long) {
        val w = stack.width.toFloat().coerceAtLeast(1f)
        val remaining = (if (open) c.translationX else w - c.translationX) / w // 0..1 of the screen still to travel
        val ms = (baseMs * remaining.coerceIn(0.35f, 1f)).toLong()
        val ease = DecelerateInterpolator(1.4f)
        moving = true
        web.animate().translationX(if (open) -w * parallax else 0f).setDuration(ms).setInterpolator(ease).withLayer().start()
        c.animate().translationX(if (open) 0f else w).setDuration(ms).setInterpolator(ease).withLayer()
            .withEndAction {
                moving = false
                if (!open) removeChats()
            }.start()
    }

    private fun removeChats() {
        val c = chatsView ?: return
        chatsView = null
        paneOpen = false
        web.translationX = 0f
        stack.removeView(c)
        c.stopLoading()
        c.destroy()
    }

    /** Both pages follow the finger: Chats by [dx], the files page by a fraction of it (parallax). */
    private fun drag(c: WebView, dx: Float) {
        val w = stack.width.toFloat().coerceAtLeast(1f)
        val x = dx.coerceIn(0f, w)
        c.translationX = x
        web.translationX = -w * parallax * (1f - x / w)
    }

    /**
     * The layout that holds the pages. It takes over a swipe that starts at the left edge and goes to the right while
     * Chats is showing (and no chat is open inside it), so the finger drags the page natively.
     */
    private inner class Stack(ctx: Context) : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val edge = ctx.dp(40)
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var dragging = false
        private var vt: VelocityTracker? = null

        private fun end() {
            tracking = false
            dragging = false
            vt?.recycle()
            vt = null
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    end()
                    tracking = chatsView != null && !moving && !paneOpen && e.x <= edge
                    if (tracking) {
                        downX = e.x
                        downY = e.y
                        vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (tracking && !dragging) {
                    vt?.addMovement(e)
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dy) > slop && abs(dy) > abs(dx)) tracking = false // it is a scroll
                    else if (dx > slop) { // to the right: this layout takes the gesture, the page gets a cancel
                        dragging = true
                        moving = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val c = chatsView
            if (!dragging || c == null) return false
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> drag(c, e.x - downX)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.x - downX
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    val leave = e.actionMasked == MotionEvent.ACTION_UP &&
                        (dx > width * 0.35f || (dx > context.dp(24) && vx > context.dp(400)))
                    end()
                    moving = false
                    slideChats(c, !leave, 220L)
                }
            }
            return true
        }
    }

    // ----------------------------------------------------------------

    /**
     * Android's file picker remembers the last folder an app browsed. Once the user has picked the WhatsApp folder for
     * the chat backup (BackupActivity / WaSource.pickerStart), every later "Upload" would open there. So the website's
     * chooser names its own start folder (the phone's main storage). Only the chat folder picker starts in WhatsApp.
     * It is an OPEN_DOCUMENT request because that is the action EXTRA_INITIAL_URI is documented for; the type,
     * "multiple" flag and OPENABLE category from the WebView's own intent are kept.
     */
    private fun startAtStorageRoot(intent: Intent) {
        intent.action = Intent.ACTION_OPEN_DOCUMENT
        intent.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:"),
        )
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
     * Loads the files page ([path] "/"). The Chats page is not loaded here: it is opened by [openChats]. A reload
     * ("Try again", a changed address) closes Chats first; the first load at app start ([keepChats]) leaves one that was just opened.
     * The server is checked first with the app's own 8-second connect timeout (StashApi.session): if it does not
     * answer, the error screen shows at once instead of after the WebView's own (very long) give-up time.
     */
    private fun load(path: String = "/", keepChats: Boolean = false) {
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
                    if (!keepChats) { removeChats(); moving = false } // a fresh load starts from the files page
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
        chatsView?.destroy()
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

        /** The files page asks for Chats: slide it in. */
        @JavascriptInterface
        fun openChats() {
            runOnUiThread { this@MainActivity.openChats() }
        }

        /** The chats page asks to leave: slide it out. */
        @JavascriptInterface
        fun closeChats() {
            runOnUiThread { this@MainActivity.closeChats() }
        }

        /** The chats page says whether a chat is open over the chat list (then the edge swipe closes that chat, not the page). */
        @JavascriptInterface
        fun setPane(open: Boolean) {
            paneOpen = open
        }
    }

    companion object {
        /** Intent extra: which page of the server to open ("/chats"). */
        const val EXTRA_PATH = "path"
    }
}
