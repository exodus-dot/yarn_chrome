package com.exodus.yarnchrome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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

        findViewById<SwitchCompat>(R.id.switchKeepOn).apply {
            isChecked = AppPrefs.isKeepScreenOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setKeepScreenOn(this@SettingsActivity, checked)
            }
        }
        setupAutofill()
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

    override fun onResume() {
        super.onResume()
        updateAutofillStatus()
    }

    private fun setupAutofill() {
        val button = findViewById<View>(R.id.btnAutofill)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            button.visibility = View.GONE
            findViewById<TextView>(R.id.autofillStatus).setText(R.string.autofill_unsupported)
            return
        }
        button.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                .setData(Uri.parse("package:$packageName"))
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.autofill_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAutofillStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(AutofillManager::class.java)
        val enabled = manager != null && manager.hasEnabledAutofillServices()
        findViewById<TextView>(R.id.autofillStatus)
            .setText(if (enabled) R.string.autofill_on else R.string.autofill_off)
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
