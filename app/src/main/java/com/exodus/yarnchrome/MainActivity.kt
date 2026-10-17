package com.exodus.yarnchrome

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var topBar: View
    private lateinit var contentFrame: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var addressBar: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var floatPill: View
    private lateinit var btnStar: ImageButton
    private lateinit var quickAccess: View
    private lateinit var tileContainer: LinearLayout
    private lateinit var homeInput: EditText

    private val bookmarkLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra(BookmarksActivity.EXTRA_URL)?.let { openUrl(it) }
        }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = Runnable { onRefreshTick() }
    private var autoReloading = false

    // 页面健康检测：定时往页面主线程投一条极短脚本，回调的延迟就是主线程的响应延迟
    private val watchdogTick = Runnable { onWatchdogTick() }
    private var probeSentAt = 0L
    private var slowProbes = 0
    private var lastRecoverAt = 0L
    private var pageLoading = false
    private var probeGen = 0
    private var quietUntil = 0L
    private var loadStartedAt = 0L
    private var lastGoneAt = 0L
    private var resumed = false
    private var currentUrl: String? = null

    // 诊断模式与按延迟追帧：诊断只在本次运行有效，追帧阈值和分辨率上限来自设置
    private lateinit var diagOverlay: TextView
    private var diagOn = false
    private var stats: DiagStats? = null
    private var statsKey = ""
    private var pageScripts: PageScripts.Installed? = null
    private var pageScriptsConfig = ""
    private var reloadReason: String? = null
    private var lastCatchUpAt = 0L
    private var noTimestampWarned = false
    private var unsupportedWarned = false
    private val diagListener = WebViewCompat.WebMessageListener { view, message, _, _, _ ->
        if (view === webView) onDiagMessage(message.data)
    }

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
        btnStar = findViewById(R.id.btnStar)
        quickAccess = findViewById(R.id.quickAccess)
        tileContainer = findViewById(R.id.tileContainer)
        homeInput = findViewById(R.id.homeInput)

        setupDiagOverlay()
        setupWebView()
        applyPageScripts(reload = false)
        setupButtons()
        setupBackHandling()

        // 每次启动先显示首页（快捷方式），不自动打开任何网站
        showQuickAccess(true)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (AppPrefs.isKeepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        scheduleAutoRefresh()
        resumed = true
        // 回到前台立刻体检一次，不等下一个周期
        scheduleWatchdog(RESUME_PROBE_DELAY_MS)
        if (firstResume) {
            firstResume = false
            return
        }
        // 设置页里可能改了追帧阈值或分辨率上限
        applyPageScripts(reload = true)
        // 从设置页回来后，白名单可能已变化：当前页不再合法就回首页
        val current = webView.url
        if (current.isNullOrBlank() || current == "about:blank") {
            refreshQuickAccess()
        } else if (!Whitelist.isAllowed(this, current)) {
            webView.loadUrl("about:blank")
        }
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTick)
        stopWatchdog()
        resumed = false
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshTick)
        stopWatchdog()
        stats?.close()
        contentFrame.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后悬浮按钮回到默认角落，避免跑出屏幕
        floatPill.translationX = 0f
        floatPill.translationY = 0f
        if (quickAccess.visibility == View.VISIBLE) refreshQuickAccess()
    }

    // ---- 定时刷新 ----

    /** 按当前设置重新排期；间隔为 0 则只取消。应用在后台时不排期（onPause 已取消）。 */
    private fun scheduleAutoRefresh() {
        refreshHandler.removeCallbacks(refreshTick)
        val seconds = AppPrefs.refreshIntervalSec(this)
        if (seconds > 0) refreshHandler.postDelayed(refreshTick, seconds * 1000L)
    }

    private fun onRefreshTick() {
        val url = webView.url
        val onPage = !url.isNullOrBlank() && url != "about:blank"
        // 首页和视频全屏播放时跳过本轮；上次加载卡住时，重新加载正好起到重试作用
        if (onPage && customView == null) {
            autoReloading = true
            reloadReason = "定时刷新"
            webView.reload()
        }
        scheduleAutoRefresh()
    }

    // ---- 页面健康检测与恢复 ----

    private fun scheduleWatchdog(delayMs: Long) {
        refreshHandler.removeCallbacks(watchdogTick)
        refreshHandler.postDelayed(watchdogTick, delayMs)
    }

    private fun stopWatchdog() {
        refreshHandler.removeCallbacks(watchdogTick)
        probeGen++            // 作废还在路上的探测回调
        probeSentAt = 0L
        slowProbes = 0
    }

    /**
     * 每轮往页面主线程投一条空脚本，回调回来的耗时就是页面主线程的响应延迟。
     * 连续几次过慢，或一条探测长时间不回，说明页面已卡死或被拖垮，自动恢复。
     * 页面加载中、视频全屏、空白页、刚加载完的宽限期内不检测。
     */
    private fun onWatchdogTick() {
        scheduleWatchdog(WATCHDOG_INTERVAL_MS)
        val now = SystemClock.elapsedRealtime()
        // 加载超过 LOAD_GRACE_MS 仍未结束（长连接、流式页面）也要检测，否则这类页面永远测不到
        val loading = pageLoading && now - loadStartedAt < LOAD_GRACE_MS
        if (!resumed || loading || customView != null || currentUrl == null || now < quietUntil) {
            probeSentAt = 0L
            slowProbes = 0
            return
        }
        if (probeSentAt != 0L) {
            // 上一条探测还没回来：超过上限就当作渲染进程挂死
            if (now - probeSentAt >= HUNG_MS) recoverPage(rebuild = true)
            return
        }
        val gen = probeGen
        val sentAt = now
        probeSentAt = sentAt
        webView.evaluateJavascript("1") {
            if (gen != probeGen) return@evaluateJavascript
            probeSentAt = 0L
            val latency = SystemClock.elapsedRealtime() - sentAt
            if (latency >= SLOW_MS) {
                slowProbes++
                if (slowProbes >= SLOW_STREAK) recoverPage(rebuild = false)
            } else {
                slowProbes = 0
            }
        }
    }

    /** 恢复当前页面。rebuild=true 时连 WebView 一起重建，用于渲染进程挂死或被杀。 */
    private fun recoverPage(rebuild: Boolean) {
        probeGen++
        probeSentAt = 0L
        slowProbes = 0
        val now = SystemClock.elapsedRealtime()
        // 冷却：恢复后页面仍然很重时，不要反复重载把页面彻底拖死
        if (now - lastRecoverAt < RECOVER_COOLDOWN_MS) return
        val url = currentUrl ?: return
        lastRecoverAt = now
        autoReloading = true
        if (rebuild) {
            reloadReason = "卡死恢复（重建 WebView）"
            rebuildWebView(url)
        } else {
            reloadReason = "卡死恢复（响应慢）"
            webView.stopLoading()
            webView.reload()
        }
    }

    /**
     * 丢弃旧 WebView，换一个全新的。Cookie 由进程级 CookieManager 保存，登录状态不丢。
     * restoreUrl 为空则回到首页方格。
     */
    private fun rebuildWebView(restoreUrl: String?) {
        stopWatchdog()
        val old = webView
        val index = contentFrame.indexOfChild(old).coerceAtLeast(0)
        contentFrame.removeView(old)
        // 旧 WebView 上的注入随它一起销毁，新 WebView 按当前配置重新注入
        pageScripts = null
        pageScriptsConfig = ""
        old.destroy()

        val fresh = WebView(this)
        fresh.id = R.id.webView
        contentFrame.addView(
            fresh,
            index,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        webView = fresh
        setupWebView()
        applyPageScripts(reload = false)
        pageLoading = false
        currentUrl = null
        if (!resumed) fresh.onPause()
        if (restoreUrl != null && Whitelist.isAllowed(this, restoreUrl)) {
            fresh.loadUrl(restoreUrl)
        } else {
            showQuickAccess(true)
        }
        if (resumed) scheduleWatchdog(WATCHDOG_INTERVAL_MS)
    }

    // ---- 诊断模式与按延迟追帧 ----

    private fun setupDiagOverlay() {
        val density = resources.displayMetrics.density
        diagOverlay = TextView(this).apply {
            setBackgroundColor(0xCC202124.toInt())
            setTextColor(0xFFE8EAED.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            // 不可点击，触摸照常落到下面的网页
            isClickable = false
            isFocusable = false
        }
        val margin = (8 * density).toInt()
        contentFrame.addView(
            diagOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(margin, margin, margin, margin) },
        )
    }

    /**
     * 按诊断开关、追帧阈值、分辨率上限决定注入哪些脚本。配置没变不动；变了就整体重装，
     * reload 为 true 时重新加载当前页面让新脚本生效。返回是否已触发重新加载。
     */
    private fun applyPageScripts(reload: Boolean): Boolean {
        val catchUp = AppPrefs.catchUpSec(this)
        val ratioCap = AppPrefs.pixelRatioCap(this)
        val needStats = diagOn || catchUp > 0

        val key = when {
            !needStats -> ""
            diagOn -> "log"
            else -> "nolog"
        }
        if (key != statsKey) {
            stats?.close()
            stats = when (key) {
                "" -> null
                "log" -> DiagStats(getExternalFilesDir("diag"))
                else -> DiagStats(null)
            }
            statsKey = key
        }

        if ((needStats && !PageScripts.diagSupported()) || (ratioCap > 0f && !PageScripts.ratioSupported())) {
            if (!unsupportedWarned) {
                unsupportedWarned = true
                Toast.makeText(this, R.string.page_scripts_unsupported, Toast.LENGTH_LONG).show()
            }
        }

        val config = "$needStats|$ratioCap"
        if (config == pageScriptsConfig) return false
        pageScripts?.remove()
        pageScripts = PageScripts.install(this, webView, needStats, ratioCap, diagListener)
        pageScriptsConfig = config
        if (!reload || currentUrl == null) return false
        reloadReason = "页面脚本变更"
        autoReloading = true
        webView.reload()
        return true
    }

    private fun toggleDiag() {
        if (!PageScripts.diagSupported()) {
            Toast.makeText(this, R.string.page_scripts_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        diagOn = !diagOn
        diagOverlay.visibility = if (diagOn) View.VISIBLE else View.GONE
        val reloaded = applyPageScripts(reload = true)
        // 统计对象换了，从新文档开始统计，"较打开时"的基准才准确
        if (!reloaded && currentUrl != null) {
            reloadReason = if (diagOn) "开启诊断" else "关闭诊断"
            autoReloading = true
            webView.reload()
        }
        if (diagOn) {
            diagOverlay.text = stats?.render()
            val path = stats?.logPath
            val text = if (path != null) getString(R.string.diag_on_log, path) else getString(R.string.diag_on_no_log)
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.diag_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDiagMessage(data: String?) {
        val s = stats ?: return
        val catchUpMs = AppPrefs.catchUpSec(this) * 1000L
        if (s.accept(data, catchUpMs)) catchUp()
        if (diagOn) diagOverlay.text = s.render()
        if (catchUpMs > 0 && !noTimestampWarned && currentUrl != null &&
            s.hasConnection() && !s.hasTimestamp() && s.sinceNavigationMs() > NO_TIMESTAMP_WARN_MS
        ) {
            noTimestampWarned = true
            Toast.makeText(this, R.string.catch_up_no_timestamp, Toast.LENGTH_LONG).show()
        }
    }

    /** 页面比打开时落后超过阈值：重新加载，直接从服务端拿最新状态。 */
    private fun catchUp() {
        if (currentUrl == null || customView != null || pageLoading) return
        val now = SystemClock.elapsedRealtime()
        if (lastCatchUpAt != 0L && now - lastCatchUpAt < CATCH_UP_COOLDOWN_MS) return
        lastCatchUpAt = now
        reloadReason = "按延迟追帧"
        autoReloading = true
        webView.reload()
    }

    // ---- 书签与快捷方格 ----

    private fun openUrl(url: String) {
        if (permit(url)) webView.loadUrl(url)
    }

    private fun showQuickAccess(show: Boolean) {
        if (show) refreshQuickAccess()
        quickAccess.visibility = if (show) View.VISIBLE else View.GONE
        if (show) updateStar(null)
    }

    /** 首页方格：全部书签 + 末尾的“添加快捷方式”。每行最多 7 个，整体居中。 */
    private fun refreshQuickAccess() {
        tileContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val screenDp = resources.configuration.screenWidthDp
        val columns = ((screenDp - 24) / 110).coerceIn(3, 7)
        val widthPx = (columns * 110 * density).toInt()
        for (view in listOf(tileContainer, homeInput)) {
            val lp = view.layoutParams as LinearLayout.LayoutParams
            lp.width = widthPx
            view.layoutParams = lp
        }

        val items = Bookmarks.load(this)
        for (chunk in (items.map<Bookmark, Bookmark?> { it } + null).chunked(columns)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (i in 0 until columns) {
                if (i >= chunk.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val item = chunk[i]
                val tile = layoutInflater.inflate(R.layout.item_tile, row, false)
                tile.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val icon = tile.findViewById<TextView>(R.id.tileIcon)
                val title = tile.findViewById<TextView>(R.id.tileTitle)
                if (item == null) {
                    icon.text = "+"
                    icon.textSize = 26f
                    title.setText(R.string.add_shortcut)
                    tile.setOnClickListener { showAddShortcutDialog() }
                } else {
                    icon.text = item.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
                    title.text = item.title
                    tile.setOnClickListener { openUrl(item.url) }
                    tile.setOnLongClickListener {
                        val popup = PopupMenu(this, tile)
                        popup.menu.add(0, 1, 0, R.string.delete_shortcut)
                        popup.setOnMenuItemClickListener {
                            Bookmarks.remove(this, item.url)
                            refreshQuickAccess()
                            true
                        }
                        popup.show()
                        true
                    }
                }
                row.addView(tile)
            }
            tileContainer.addView(row)
        }
    }

    private fun showAddShortcutDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(pad, pad / 2, pad, 0)
        val nameInput = EditText(this)
        nameInput.setHint(R.string.shortcut_name_hint)
        nameInput.setSingleLine()
        nameInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        val urlInput = EditText(this)
        urlInput.setHint(R.string.shortcut_url_hint)
        urlInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlInput.setSingleLine()
        urlInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        form.addView(nameInput)
        form.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_shortcut)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                addShortcut(nameInput.text.toString(), urlInput.text.toString())
            }
            .show()
    }

    /** 添加快捷方式：同时把域名加入白名单（已存在则忽略）。 */
    private fun addShortcut(name: String, rawUrl: String) {
        val raw = rawUrl.trim()
        if (raw.isEmpty()) return
        val url = if (raw.contains("://")) raw else "https://$raw"
        val validScheme = url.startsWith("http://", true) || url.startsWith("https://", true)
        if (!validScheme || Whitelist.normalize(url) == null) {
            Toast.makeText(this, R.string.shortcut_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        Whitelist.add(this, url)
        Bookmarks.add(this, name, url)
        refreshQuickAccess()
        Toast.makeText(this, R.string.shortcut_added, Toast.LENGTH_SHORT).show()
    }

    private fun goHome() {
        if (webView.url == "about:blank" || webView.url.isNullOrBlank()) {
            showQuickAccess(true)
        } else {
            webView.loadUrl("about:blank")
        }
    }

    private fun updateStar(url: String?) {
        val starred = !url.isNullOrBlank() && Bookmarks.contains(this, url)
        btnStar.setImageResource(if (starred) R.drawable.ic_star else R.drawable.ic_star_outline)
    }

    private fun toggleBookmark() {
        val url = webView.url
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (Bookmarks.contains(this, url)) {
            Bookmarks.remove(this, url)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            Bookmarks.add(this, webView.title, url)
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
        updateStar(url)
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, R.string.home)
        popup.menu.add(0, 1, 1, R.string.bookmarks)
        popup.menu.add(0, 3, 2, getString(
            R.string.auto_refresh_menu,
            RefreshInterval.label(this, AppPrefs.refreshIntervalSec(this)),
        ))
        popup.menu.add(0, 4, 3, R.string.diag_mode).apply {
            isCheckable = true
            isChecked = diagOn
        }
        popup.menu.add(0, 2, 4, R.string.settings_title)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                4 -> toggleDiag()
                3 -> RefreshInterval.show(this) { scheduleAutoRefresh() }
                0 -> goHome()
                1 -> bookmarkLauncher.launch(Intent(this, BookmarksActivity::class.java))
                2 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        popup.show()
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

    private fun reloadPage() {
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank") {
            refreshQuickAccess()
            return
        }
        reloadReason = "手动刷新"
        webView.reload()
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
        // 让系统自动填充服务（密码管理器）可以识别页面里的登录表单
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }

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
                stats?.markNavigation(reloadReason ?: if (url == "about:blank") "回到首页" else "导航")
                reloadReason = null
                showQuickAccess(url == "about:blank")
                pageLoading = true
                loadStartedAt = SystemClock.elapsedRealtime()
                currentUrl = if (url == "about:blank") null else url
                probeSentAt = 0L
                slowProbes = 0
                // 自动刷新不显示进度条，减少视觉打扰
                progress.visibility = if (autoReloading) View.GONE else View.VISIBLE
                if (!addressBar.hasFocus()) addressBar.setText(if (url == "about:blank") "" else url)
                updateBackState()
                updateStar(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoading = false
                // 页面刚加载完主线程本来就忙，给一段宽限期再开始计慢
                quietUntil = SystemClock.elapsedRealtime() + POST_LOAD_QUIET_MS
                autoReloading = false
                progress.visibility = View.GONE
                updateBackState()
                updateStar(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                updateBackState()
                updateStar(url)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
            }

            /**
             * 渲染进程被系统回收或崩溃。不处理的话整个应用会被一起带走。
             * 返回 true 表示由我们接管：重建 WebView 并回到刚才的页面；
             * 10 秒内再次出现说明页面本身会把渲染进程撑爆，退回首页避免死循环。
             */
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== webView) return true
                val now = SystemClock.elapsedRealtime()
                val looping = now - lastGoneAt < RENDER_GONE_LOOP_MS
                lastGoneAt = now
                val url = currentUrl
                autoReloading = true
                // 反复崩溃时直接回首页不会再加载页面，原因当场记下，免得挂到用户下一次导航上
                if (looping) stats?.markNavigation("渲染进程反复崩溃，回到首页") else reloadReason = "渲染进程崩溃恢复"
                // 回调内同步销毁自身不安全，投递到下一轮消息再重建
                refreshHandler.post { rebuildWebView(if (looping) null else url) }
                return true
            }
        }
        // 低内存时优先保住渲染进程，不让系统先杀它
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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
        btnStar.setOnClickListener { toggleBookmark() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        makeDraggable(findViewById(R.id.floatRefresh), floatPill) { reloadPage() }
        makeDraggable(findViewById(R.id.floatExit), floatPill) { setFullscreen(false) }

        for (field in listOf(addressBar, homeInput)) {
            field.setOnEditorActionListener { v, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
                val raw = v.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val url = if (raw.contains("://")) raw else "https://$raw"
                    if (permit(url)) {
                        webView.loadUrl(url)
                        v.text = ""
                        v.clearFocus()
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(v.windowToken, 0)
                    }
                }
                true
            }
        }
    }

    private fun updateBackState() {
        btnBack.alpha = if (webView.canGoBack()) 1f else 0.35f
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private const val RESUME_PROBE_DELAY_MS = 300L
        private const val SLOW_MS = 1_500L            // 单次探测回调超过它算一次慢
        private const val SLOW_STREAK = 3             // 连续慢几次触发重载
        private const val HUNG_MS = 10_000L           // 一条探测这么久不回，判定渲染进程挂死
        private const val RECOVER_COOLDOWN_MS = 30_000L
        private const val POST_LOAD_QUIET_MS = 5_000L
        private const val LOAD_GRACE_MS = 20_000L
        private const val RENDER_GONE_LOOP_MS = 10_000L
        private const val CATCH_UP_COOLDOWN_MS = 30_000L      // 两次追帧刷新之间的最短间隔
        private const val NO_TIMESTAMP_WARN_MS = 20_000L      // 开启追帧后这么久还没识别到时间戳就提示一次
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
