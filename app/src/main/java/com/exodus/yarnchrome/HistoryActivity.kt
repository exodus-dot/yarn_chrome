package com.exodus.yarnchrome

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var emptyView: TextView
    private val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        list = findViewById(R.id.historyList)
        emptyView = findViewById(R.id.emptyView)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            if (History.load(this).isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    History.clear(this)
                    render()
                }
                .show()
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val items = History.load(this)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            val row = layoutInflater.inflate(R.layout.item_bookmark, list, false)
            row.findViewById<TextView>(R.id.bookmarkTitle).text =
                item.title.ifEmpty { item.url }
            row.findViewById<TextView>(R.id.bookmarkUrl).text =
                "${format.format(Date(item.time))}  ${item.url}"
            row.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, item.url))
                finish()
            }
            row.setOnLongClickListener {
                val popup = PopupMenu(this, row)
                popup.menu.add(0, 1, 0, R.string.open_in_new_window)
                popup.setOnMenuItemClickListener {
                    MainActivity.openNewWindow(this, item.url)
                    true
                }
                popup.show()
                true
            }
            row.findViewById<ImageButton>(R.id.btnRemove).setOnClickListener {
                History.remove(this, item)
                render()
            }
            list.addView(row)
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
