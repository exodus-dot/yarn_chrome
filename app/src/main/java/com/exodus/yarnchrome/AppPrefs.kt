package com.exodus.yarnchrome

import android.content.Context

/** 应用级开关。 */
object AppPrefs {
    private const val PREFS = "app_prefs"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_REFRESH_INTERVAL = "refresh_interval_sec"

    fun isKeepScreenOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, false)

    /** 定时刷新间隔（秒），0 表示关闭。 */
    fun refreshIntervalSec(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_REFRESH_INTERVAL, 0)

    fun setRefreshIntervalSec(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REFRESH_INTERVAL, seconds)
            .apply()
    }

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_SCREEN_ON, value)
            .apply()
    }
}
