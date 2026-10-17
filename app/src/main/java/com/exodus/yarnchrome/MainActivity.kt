package com.exodus.yarnchrome

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var topBar: View
    private lateinit var contentFrame: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var addressBar: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var floatPill: View

    private var fullscreen = false
    private var firstResume = true
    private var lastBlockedToastAt = 0L

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenBeforeVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        topBar = findViewById(R.id.topBar)
        contentFrame = findViewById(R.id.contentFrame)
        progress = findViewById(R.id.progress)
        addressBar = findViewById(R.id.addressBar)
        btnBack = findViewById(R.id.btnBack)
        floatPill = findViewById(R.id.floatPill)

        setupWebView()
        setupButtons()
        setupBackHandling()

        if (Whitelist.home(this) == null) {
            Toast.makeText(this, R.string.empty_whitelist, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            loadHome()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // 从设置页回来后，白名单可能已变化：当前页不再合法就回首页
        val current = webView.url
        val home = Whitelist.home(this)
        if (current.isNullOrBlank() || current == "about:blank") {
            if (home != null) webView.loadUrl(home)
        } else if (!Whitelist.isAllowed(this, current)) {
            webView.loadUrl(home ?: "about:blank")
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        contentFrame.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后悬浮按钮回到默认角落，避免跑出屏幕
        floatPill.translationX = 0f
        floatPill.translationY = 0f
    }

    // ---- 快捷键：F11 / Ctrl+Shift+F 全屏，F5 / Ctrl+R 刷新，Esc 退出全屏 ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when {
                event.keyCode == KeyEvent.KEYCODE_F11 ||
                    (event.keyCode == KeyEvent.KEYCODE_F && event.isCtrlPressed && event.isShiftPressed) -> {
                    setFullscreen(!fullscreen)
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_F5 ||
                    (event.keyCode == KeyEvent.KEYCODE_R && event.isCtrlPressed) -> {
                    reloadPage()
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_ESCAPE && fullscreen && customView == null -> {
                    setFullscreen(false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- 页面加载与白名单 ----

    private fun loadHome() {
        val home = Whitelist.home(this)
        if (home == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            webView.loadUrl(home)
        }
    }

    private fun reloadPage() {
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank") loadHome() else webView.reload()
    }

    /** 主框架导航是否放行；about:blank 仅作为空白页放行。 */
    private fun permit(url: String): Boolean {
        if (url == "about:blank") return true
        if (Whitelist.isAllowed(this, url)) return true
        notifyBlocked(url)
        return false
    }

    private fun notifyBlocked(url: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedToastAt < 1500) return
        lastBlockedToastAt = now
        val host = Uri.parse(url).host ?: url.take(40)
        Toast.makeText(this, getString(R.string.blocked, host), Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.setDownloadListener(DownloadListener { _, _, _, _, _ -> })

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!request.isForMainFrame) {
                    // 子框架只拒绝非 http(s) 协议（intent:、market: 等），不按白名单拦截内嵌内容
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "http" && scheme != "https" && scheme != "about"
                }
                return !permit(url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // 兜底：个别导航不经过 shouldOverrideUrlLoading
                if (!permit(url)) {
                    view.stopLoading()
                    return
                }
                progress.visibility = View.VISIBLE
                if (!addressBar.hasFocus()) addressBar.setText(url)
                updateBackState()
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                updateBackState()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                updateBackState()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // 无用户手势的弹窗一律不创建；有手势的先放进临时 WebView，拿到目标地址后再决定
                if (!isUserGesture) return false
                val temp = WebView(this@MainActivity)
                var handled = false
                fun resolve(url: String?) {
                    if (handled || url.isNullOrBlank() || url == "about:blank") return
                    handled = true
                    if (permit(url)) webView.loadUrl(url)
                    temp.stopLoading()
                    temp.post { temp.destroy() }
                }
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        resolve(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        resolve(url)
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fullscreenBeforeVideo = fullscreen
                setFullscreen(true)
                contentFrame.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }

            override fun onHideCustomView() {
                val view = customView ?: return
                contentFrame.removeView(view)
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                setFullscreen(fullscreenBeforeVideo)
            }
        }
    }

    // ---- 界面按钮 ----

    private fun setupButtons() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { reloadPage() }
        findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener { setFullscreen(true) }
        findViewById<ImageButton>(R.id.btnWhitelist).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        makeDraggable(findViewById(R.id.floatRefresh), floatPill) { reloadPage() }
        makeDraggable(findViewById(R.id.floatExit), floatPill) { setFullscreen(false) }

        addressBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            val raw = v.text.toString().trim()
            if (raw.isNotEmpty()) {
                val url = if (raw.contains("://")) raw else "https://$raw"
                if (permit(url)) {
                    webView.loadUrl(url)
                    v.clearFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
            true
        }
    }

    private fun updateBackState() {
        btnBack.alpha = if (webView.canGoBack()) 1f else 0.35f
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        customView != null -> webView.webChromeClient?.onHideCustomView()
                        fullscreen -> setFullscreen(false)
                        webView.canGoBack() -> webView.goBack()
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
    }

    // ---- 全屏 ----

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = if (on) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = attrs
        }
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        floatPill.visibility = if (on && customView == null) View.VISIBLE else View.GONE
    }

    /** 点击触发 onClick，拖动则移动 target，并限制在父容器内。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, target: View, onClick: () -> Unit) {
        val slop = resources.displayMetrics.density * 8
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startTx = target.translationX
                    startTy = target.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        target.translationX = startTx + dx
                        target.translationY = startTy + dy
                        clampInParent(target)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun clampInParent(target: View) {
        val parent = target.parent as? View ?: return
        val minX = -target.left.toFloat()
        val maxX = (parent.width - target.width - target.left).toFloat()
        val minY = -target.top.toFloat()
        val maxY = (parent.height - target.height - target.top).toFloat()
        target.translationX = target.translationX.coerceIn(minX, maxOf(minX, maxX))
        target.translationY = target.translationY.coerceIn(minY, maxOf(minY, maxY))
    }
}
