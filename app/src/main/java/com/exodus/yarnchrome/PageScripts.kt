package com.exodus.yarnchrome

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

/**
 * 页面加载前注入的脚本：诊断采集（diag.js）和 devicePixelRatio 上限。
 * 两者都依赖 WebView 的 DOCUMENT_START_SCRIPT，采集上报还依赖 WEB_MESSAGE_LISTENER，系统 WebView 太旧时不可用。
 */
object PageScripts {
    /** 注入页面的上报对象名，diag.js 里按这个名字查找。 */
    const val HOST_OBJECT = "yarnDiagHost"
    private val ALL_ORIGINS = setOf("*")
    private var diagSource: String? = null

    fun diagSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    fun ratioSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** 一个 WebView 上已注入的内容，配置变化时整体移除再重装。 */
    class Installed(
        private val webView: WebView,
        private val handles: List<ScriptHandler>,
        private val listener: Boolean,
    ) {
        fun remove() {
            for (h in handles) h.remove()
            if (listener) WebViewCompat.removeWebMessageListener(webView, HOST_OBJECT)
        }
    }

    /** 按配置注入，只对之后创建的文档生效。不支持的部分直接跳过。 */
    fun install(
        context: Context,
        webView: WebView,
        diag: Boolean,
        ratioCap: Float,
        listener: WebViewCompat.WebMessageListener,
    ): Installed {
        val handles = ArrayList<ScriptHandler>()
        var hasListener = false
        if (diag && diagSupported()) {
            WebViewCompat.addWebMessageListener(webView, HOST_OBJECT, ALL_ORIGINS, listener)
            hasListener = true
            handles += WebViewCompat.addDocumentStartJavaScript(webView, diagScript(context), ALL_ORIGINS)
        }
        if (ratioCap > 0f && ratioSupported()) {
            handles += WebViewCompat.addDocumentStartJavaScript(webView, ratioScript(ratioCap), ALL_ORIGINS)
        }
        return Installed(webView, handles, hasListener)
    }

    private fun diagScript(context: Context): String =
        diagSource ?: context.assets.open("diag.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .also { diagSource = it }

    /**
     * 让页面读到的 devicePixelRatio 不超过上限。three.js、Unity WebGL 等按它决定画布分辨率，
     * 压低后每帧要绘制的像素成倍减少；CSS 布局不受影响，网页文字仍按原分辨率显示。
     */
    private fun ratioScript(cap: Float): String {
        val value = String.format(Locale.ROOT, "%.2f", cap)
        return """
            (() => {
              const cap = $value;
              const d = Object.getOwnPropertyDescriptor(window, 'devicePixelRatio')
                || Object.getOwnPropertyDescriptor(Window.prototype, 'devicePixelRatio');
              if (!d || !d.get) return;
              try {
                Object.defineProperty(window, 'devicePixelRatio', {
                  configurable: true,
                  enumerable: d.enumerable,
                  get: () => Math.min(cap, d.get.call(window)),
                  set: d.set ? (v) => d.set.call(window, v) : undefined,
                });
              } catch (e) {
                // 属性不可重定义时保持原样
              }
            })();
        """.trimIndent()
    }
}
