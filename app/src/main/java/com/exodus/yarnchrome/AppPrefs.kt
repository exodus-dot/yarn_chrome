package com.exodus.yarnchrome

import android.content.Context

/** 应用级开关。 */
object AppPrefs {
    private const val PREFS = "app_prefs"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_REFRESH_INTERVAL = "refresh_interval_sec"
    private const val KEY_CATCH_UP = "catch_up_sec"
    private const val KEY_PIXEL_RATIO_CAP = "pixel_ratio_cap"

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

    /** 按延迟自动追帧的阈值（秒），0 表示关闭。 */
    fun catchUpSec(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CATCH_UP, 0)

    fun setCatchUpSec(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CATCH_UP, seconds)
            .apply()
    }

    /** 页面看到的 devicePixelRatio 上限，0 表示不限制。 */
    fun pixelRatioCap(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_PIXEL_RATIO_CAP, 0f)

    fun setPixelRatioCap(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PIXEL_RATIO_CAP, value)
            .apply()
    }

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_SCREEN_ON, value)
            .apply()
    }
}
