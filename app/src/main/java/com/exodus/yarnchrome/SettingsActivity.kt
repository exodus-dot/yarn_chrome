package com.exodus.yarnchrome

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.IDN

class SettingsActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        input = findViewById(R.id.domainInput)
        list = findViewById(R.id.domainList)
        emptyView = findViewById(R.id.emptyView)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        render()
    }

    private fun submit() {
        val text = input.text.toString()
        if (text.isBlank()) return
        when (Whitelist.add(this, text)) {
            Whitelist.AddResult.ADDED -> {
                input.text.clear()
                render()
            }
            Whitelist.AddResult.INVALID ->
                Toast.makeText(this, R.string.invalid_domain, Toast.LENGTH_SHORT).show()
            Whitelist.AddResult.EXISTS ->
                Toast.makeText(this, R.string.domain_exists, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        list.removeAllViews()
        val entries = Whitelist.load(this)
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for (domain in entries) {
            val row = layoutInflater.inflate(R.layout.item_domain, list, false)
            row.findViewById<TextView>(R.id.domainText).text = IDN.toUnicode(domain)
            row.findViewById<ImageButton>(R.id.btnRemove).setOnClickListener {
                Whitelist.remove(this, domain)
                render()
            }
            list.addView(row)
        }
    }
}
