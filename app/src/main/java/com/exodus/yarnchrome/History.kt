package com.exodus.yarnchrome

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class HistoryEntry(val title: String, val url: String, val time: Long)

/** 浏览历史：最新的在前，最多保留 MAX 条；连续访问同一页只更新时间。 */
object History {
    private const val PREFS = "history"
    private const val KEY = "items"
    private const val MAX = 500

    private fun key(url: String) = url.substringBefore('#')

    @Synchronized
    fun load(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                HistoryEntry(o.optString("title"), o.getString("url"), o.optLong("time"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun save(context: Context, items: List<HistoryEntry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun record(context: Context, title: String?, url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        val items = load(context)
        val name = title?.trim().orEmpty()
        val first = items.firstOrNull()
        val entry = HistoryEntry(
            name.ifEmpty { if (first != null && key(first.url) == key(url)) first.title else "" },
            url,
            System.currentTimeMillis(),
        )
        val rest = if (first != null && key(first.url) == key(url)) items.drop(1) else items
        save(context, (listOf(entry) + rest).take(MAX))
    }

    /** 页面标题晚于加载完成到达时，补到最新一条上。 */
    @Synchronized
    fun updateTitle(context: Context, url: String, title: String?) {
        val name = title?.trim().orEmpty()
        if (name.isEmpty()) return
        val items = load(context)
        val first = items.firstOrNull() ?: return
        if (key(first.url) != key(url) || first.title == name) return
        save(context, listOf(first.copy(title = name)) + items.drop(1))
    }

    @Synchronized
    fun remove(context: Context, entry: HistoryEntry) {
        save(context, load(context).filter { it != entry })
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyList())
    }
}
