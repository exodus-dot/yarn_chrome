package com.exodus.yarnchrome

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BookmarksActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)
        list = findViewById(R.id.bookmarkList)
        emptyView = findViewById(R.id.emptyView)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val items = Bookmarks.load(this)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            val row = layoutInflater.inflate(R.layout.item_bookmark, list, false)
            row.findViewById<TextView>(R.id.bookmarkTitle).text = item.title
            row.findViewById<TextView>(R.id.bookmarkUrl).text = item.url
            row.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, item.url))
                finish()
            }
            row.findViewById<ImageButton>(R.id.btnRemove).setOnClickListener {
                Bookmarks.remove(this, item.url)
                render()
            }
            list.addView(row)
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
