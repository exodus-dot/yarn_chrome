package com.exodus.yarnchrome

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** 定时刷新间隔的显示文案与选择对话框，主界面菜单和设置页共用。 */
object RefreshInterval {
    private val presets = intArrayOf(0, 15, 30, 60, 300)
    private const val MIN_SECONDS = 5
    private const val MAX_SECONDS = 86400

    fun label(context: Context, seconds: Int): String = when {
        seconds <= 0 -> context.getString(R.string.interval_off)
        seconds >= 60 && seconds % 60 == 0 -> context.getString(R.string.interval_minutes, seconds / 60)
        else -> context.getString(R.string.interval_seconds, seconds)
    }

    fun show(context: Context, onChanged: () -> Unit) {
        val current = AppPrefs.refreshIntervalSec(context)
        val labels = presets.map { label(context, it) } + context.getString(R.string.interval_custom)
        val checked = presets.indexOf(current).takeIf { it >= 0 } ?: presets.size
        AlertDialog.Builder(context)
            .setTitle(R.string.auto_refresh)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which < presets.size) {
                    AppPrefs.setRefreshIntervalSec(context, presets[which])
                    onChanged()
                } else {
                    showCustom(context, current, onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustom(context: Context, current: Int, onChanged: () -> Unit) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setHint(R.string.interval_custom_hint)
        input.setSingleLine()
        if (current > 0) input.setText(current.toString())
        val holder = FrameLayout(context)
        holder.setPadding(pad, pad / 2, pad, 0)
        holder.addView(input)
        AlertDialog.Builder(context)
            .setTitle(R.string.interval_custom)
            .setView(holder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val seconds = input.text.toString().toIntOrNull()
                if (seconds == null || seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
                    Toast.makeText(context, R.string.interval_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    AppPrefs.setRefreshIntervalSec(context, seconds)
                    onChanged()
                }
            }
            .show()
    }
}
