package com.exodus.yarnchrome

import android.content.Context

/** 应用级开关。 */
object AppPrefs {
    private const val PREFS = "app_prefs"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    fun isKeepScreenOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_SCREEN_ON, value)
            .apply()
    }
}
