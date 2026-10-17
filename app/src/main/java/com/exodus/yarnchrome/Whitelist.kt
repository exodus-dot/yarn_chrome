package com.exodus.yarnchrome

import android.content.Context
import android.net.Uri
import java.net.IDN
import java.util.Locale

/** 域名白名单：按域名匹配，自动包含子域名。 */
object Whitelist {
    private const val PREFS = "whitelist"
    private const val KEY = "entries"

    /** 把用户输入整理成纯域名（小写、punycode），无法识别返回 null。 */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        s = s.substringBefore(':')
        s = s.removePrefix("*.").trim('.')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        return try {
            IDN.toASCII(s)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }

    private fun save(context: Context, entries: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, entries.joinToString("\n"))
            .apply()
    }

    enum class AddResult { ADDED, INVALID, EXISTS }

    fun add(context: Context, input: String): AddResult {
        val domain = normalize(input) ?: return AddResult.INVALID
        val entries = load(context)
        if (domain in entries) return AddResult.EXISTS
        save(context, entries + domain)
        return AddResult.ADDED
    }

    fun remove(context: Context, domain: String) {
        save(context, load(context).filter { it != domain })
    }

    /** 首页：白名单第一项。 */
    fun home(context: Context): String? = load(context).firstOrNull()?.let { "https://$it" }

    /** 只放行 http/https 且主机命中白名单（本域或其子域）的地址。 */
    fun isAllowed(context: Context, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        // Android 的 Uri 解析对反斜杠与 WebView 实际行为不一致，可被用来伪装主机名
        if (url.contains('\\')) return false
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val rawHost = uri.host?.lowercase(Locale.ROOT)?.trim('.') ?: return false
        val host = try {
            IDN.toASCII(rawHost)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return load(context).any { host == it || host.endsWith(".$it") }
    }
}
