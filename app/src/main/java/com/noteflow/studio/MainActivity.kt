package com.noteflow.studio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var boardWebView: WebView
    private lateinit var browserPanel: LinearLayout
    private lateinit var inAppWebView: WebView
    private lateinit var browserTitleView: TextView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val clipData = data.clipData
            if (clipData != null) {
                val uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                callback.onReceiveValue(uris)
            } else if (data.data != null) {
                callback.onReceiveValue(arrayOf(data.data!!))
            } else {
                callback.onReceiveValue(null)
            }
        } else {
            callback.onReceiveValue(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = FrameLayout(this)
        setContentView(rootLayout)
        enableImmersiveFullscreen()
        requestHighRefreshRate()

        // 1. Main NoteFlow Board WebView
        boardWebView = WebView(this)
        rootLayout.addView(
            boardWebView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        boardWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }
        boardWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        boardWebView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        boardWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    openInAppBrowser(u, "Google / Web")
                    return true
                }
                return false
            }
        }
        boardWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                filePickerLauncher.launch(Intent.createChooser(intent, "Select PDF, PPTX, Image or Video"))
                return true
            }
        }

        // 2. Integrated In-App Google & Google Drive Browser Panel (never leaves NoteFlow!)
        buildInAppBrowserPanel()
        boardWebView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browserPanel.visibility == View.VISIBLE) {
                    if (inAppWebView.canGoBack()) inAppWebView.goBack()
                    else browserPanel.visibility = View.GONE
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildInAppBrowserPanel() {
        browserPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            visibility = View.GONE
            elevation = 24f
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 10, 16, 10)
        }

        fun makeBtn(label: String, bgHex: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(bgHex))
                setPadding(22, 8, 22, 8)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = 10
                layoutParams = lp
                setOnClickListener { onClick() }
            }
        }

        val btnBack = makeBtn("← Back", "#334155") {
            if (inAppWebView.canGoBack()) inAppWebView.goBack()
        }
        val btnGoogle = makeBtn("Google", "#2563EB") {
            inAppWebView.loadUrl("https://www.google.com")
        }
        val btnDrive = makeBtn("Drive", "#059669") { inAppWebView.loadUrl("https://drive.google.com") }
        val btnDocs = makeBtn("Docs", "#2563EB") { inAppWebView.loadUrl("https://docs.google.com") }
        val btnSlides = makeBtn("Slides", "#D97706") { inAppWebView.loadUrl("https://docs.google.com/presentation") }
        val btnPhotos = makeBtn("Photos", "#0284C7") { inAppWebView.loadUrl("https://photos.google.com") }
        val btnYt = makeBtn("YouTube", "#DC2626") { inAppWebView.loadUrl("https://m.youtube.com") }
        browserTitleView = TextView(this).apply {
            text = "In-App Google & Drive (Long-press image or Download file to import)"
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSnap = makeBtn("+ Snap to Board", "#7C3AED") {
            try {
                val bmp = android.graphics.Bitmap.createBitmap(
                    inAppWebView.width.coerceAtLeast(1),
                    inAppWebView.height.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                inAppWebView.draw(canvas)
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                deliverBase64ToBoard("web_snap.png", "image/png", b64)
            } catch (e: Exception) {
                Toast.makeText(this, "Snap error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        val btnClose = makeBtn("✕ Close", "#E11D48") {
            browserPanel.visibility = View.GONE
        }

        topBar.addView(btnBack)
        topBar.addView(btnGoogle)
        topBar.addView(btnDrive)
        topBar.addView(btnDocs)
        topBar.addView(btnSlides)
        topBar.addView(btnPhotos)
        topBar.addView(btnYt)
        topBar.addView(browserTitleView)
        topBar.addView(btnSnap)
        topBar.addView(btnClose)
        browserPanel.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        inAppWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Remove '; wv' so Google Sign-In & Google Drive work cleanly inside the app!
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(inAppWebView, true)

        inAppWebView.settings.setSupportMultipleWindows(false)
        inAppWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    return false
                }
                if (u.startsWith("intent://")) {
                    val m = Regex("S\\.browser_fallback_url=([^;]+)").find(u)
                    if (m != null) {
                        val fallback = Uri.decode(m.groupValues[1])
                        view?.loadUrl(fallback)
                    }
                    return true
                }
                return true
            }
        }

        // Long-press any image on Google Images / Web -> 1-tap import straight to board!
        inAppWebView.setOnLongClickListener {
            val hit = inAppWebView.hitTestResult
            val imgUrl = hit.extra
            if ((hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && !imgUrl.isNullOrBlank()) {
                Toast.makeText(this, "Importing image to NoteFlow Board...", Toast.LENGTH_SHORT).show()
                downloadAndSendToBoard(imgUrl, "google_image.png", "image/png")
                true
            } else {
                false
            }
        }

        // Intercept any PDF, PPTX, Image, or Video download from Google Search / Google Drive!
        inAppWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val guessed = URLUtil.guessFileName(url, contentDisposition, mimetype)
            Toast.makeText(this, "Importing $guessed into NoteFlow...", Toast.LENGTH_SHORT).show()
            downloadAndSendToBoard(url, guessed, mimetype ?: "application/octet-stream")
        }

        browserPanel.addView(inAppWebView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.setMargins(24, 24, 24, 84)
        rootLayout.addView(browserPanel, lp)
    }

    private fun downloadAndSendToBoard(fileUrl: String, fileName: String, mimeType: String) {
        thread {
            try {
                if (fileUrl.startsWith("data:")) {
                    val comma = fileUrl.indexOf(',')
                    if (comma > 0) {
                        val b64 = fileUrl.substring(comma + 1)
                        runOnUiThread { deliverBase64ToBoard(fileName, mimeType, b64) }
                        return@thread
                    }
                }
                val cookies = CookieManager.getInstance().getCookie(fileUrl)
                val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20000
                    readTimeout = 30000
                    if (!cookies.isNullOrBlank()) setRequestProperty("Cookie", cookies)
                    setRequestProperty("User-Agent", inAppWebView.settings.userAgentString)
                }
                val ct = (conn.contentType ?: mimeType).lowercase()
                val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val isVideo = ct.startsWith("video/") || safeName.endsWith(".mp4", true) || safeName.endsWith(".webm", true) || safeName.endsWith(".mkv", true)

                if (isVideo) {
                    val outFile = File(filesDir, "imported_${System.currentTimeMillis()}_$safeName")
                    conn.inputStream.use { inp ->
                        FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                    }
                    val localUri = "file://${outFile.absolutePath}"
                    runOnUiThread {
                        browserPanel.visibility = View.GONE
                        val js = "window.receiveAndroidVideoFile && window.receiveAndroidVideoFile(${JSONObject.quote(safeName)}, ${JSONObject.quote(localUri)});"
                        boardWebView.evaluateJavascript(js, null)
                    }
                } else {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    runOnUiThread { deliverBase64ToBoard(safeName, ct, b64) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun deliverBase64ToBoard(fileName: String, mimeType: String, b64: String) {
        browserPanel.visibility = View.GONE
        val js = "window.receiveAndroidBase64File && window.receiveAndroidBase64File(${JSONObject.quote(fileName)}, ${JSONObject.quote(mimeType)}, ${JSONObject.quote(b64)});"
        boardWebView.evaluateJavascript(js, null)
    }

    private fun openInAppBrowser(url: String, title: String) {
        runOnUiThread {
            browserTitleView.text = "$title — Long-press any image or tap Download to import"
            browserPanel.visibility = View.VISIBLE
            inAppWebView.loadUrl(url)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun openInAppUrl(url: String, title: String) {
            openInAppBrowser(url, title)
        }

        @JavascriptInterface
        fun closeInAppBrowser() {
            runOnUiThread { browserPanel.visibility = View.GONE }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val modes = display?.supportedModes ?: return
                val best = modes.maxByOrNull { it.refreshRate } ?: return
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            } catch (_: Exception) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }
}
