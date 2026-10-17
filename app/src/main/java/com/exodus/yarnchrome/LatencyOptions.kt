package com.exodus.yarnchrome

import android.content.Context
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/** 实时页面延迟相关选项：按延迟自动追帧、渲染分辨率上限。主界面菜单和设置页共用。 */
object LatencyOptions {
    private val catchUpPresets = intArrayOf(0, 2, 5, 10)
    private val ratioPresets = floatArrayOf(0f, 1.5f, 1f)

    fun catchUpLabel(context: Context, seconds: Int): String =
        if (seconds <= 0) context.getString(R.string.interval_off)
        else context.getString(R.string.catch_up_over, seconds)

    fun ratioLabel(context: Context, cap: Float): String =
        if (cap <= 0f) context.getString(R.string.pixel_ratio_native)
        else context.getString(R.string.pixel_ratio_cap_value, String.format(Locale.ROOT, "%.1f", cap).removeSuffix(".0"))

    fun showCatchUp(context: Context, onChanged: () -> Unit) {
        val current = AppPrefs.catchUpSec(context)
        val labels = catchUpPresets.map { catchUpLabel(context, it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.catch_up)
            .setSingleChoiceItems(labels, catchUpPresets.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                AppPrefs.setCatchUpSec(context, catchUpPresets[which])
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showPixelRatio(context: Context, onChanged: () -> Unit) {
        val current = AppPrefs.pixelRatioCap(context)
        val labels = ratioPresets.map { ratioLabel(context, it) }.toTypedArray()
        val checked = ratioPresets.indexOfFirst { it == current }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle(R.string.pixel_ratio)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                AppPrefs.setPixelRatioCap(context, ratioPresets[which])
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
