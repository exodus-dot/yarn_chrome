package com.exodus.yarnchrome

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate

class YarnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Theme.apply(this)
    }
}

/** 界面主题：跟随系统 / 浅色 / 深色。 */
object Theme {
    private val modes = intArrayOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
    )

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(modes[AppPrefs.themeMode(context).coerceIn(0, 2)])
    }

    fun label(context: Context, mode: Int): String = context.getString(
        when (mode) {
            1 -> R.string.theme_light
            2 -> R.string.theme_dark
            else -> R.string.theme_system
        },
    )

    fun show(context: Context, onChanged: () -> Unit) {
        val labels = (0..2).map { label(context, it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(labels, AppPrefs.themeMode(context).coerceIn(0, 2)) { dialog, which ->
                dialog.dismiss()
                AppPrefs.setThemeMode(context, which)
                onChanged()
                apply(context)   // 会让已打开的界面按新主题重建
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/** 网页强制深色（算法变暗），依赖系统 WebView 支持。 */
object PageDark {
    fun supported(): Boolean =
        androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)

    fun apply(context: Context, webView: android.webkit.WebView) {
        if (!supported()) return
        androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(
            webView.settings,
            AppPrefs.forceDarkPages(context),
        )
    }
}
