package com.exodus.yarnchrome

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

/** 书签：按 URL（忽略 # 片段）去重，保持添加顺序。 */
object Bookmarks {
    private const val PREFS = "bookmarks"
    private const val KEY = "items"

    private fun key(url: String) = url.substringBefore('#')

    fun load(context: Context): List<Bookmark> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]")
            .orEmpty()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Bookmark(o.optString("title"), o.getString("url"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun save(context: Context, items: List<Bookmark>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun contains(context: Context, url: String): Boolean =
        load(context).any { key(it.url) == key(url) }

    fun add(context: Context, title: String?, url: String) {
        if (contains(context, url)) return
        val name = title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: Uri.parse(url).host
            ?: url
        save(context, load(context) + Bookmark(name, key(url)))
    }

    fun remove(context: Context, url: String) {
        save(context, load(context).filter { key(it.url) != key(url) })
    }
}
