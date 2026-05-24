package com.mohammadadnan.vpn

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(0xFF000000.toInt())
        }

        val title = TextView(this).apply {
            text = "الإعدادات"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        fun makeLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = android.view.Gravity.END
            setPadding(0, 20, 0, 4)
        }

        fun makeInput(hint: String, value: String) = EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF444444.toInt())
            setBackgroundColor(0xFF111111.toInt())
            setPadding(16, 16, 16, 16)
            gravity = android.view.Gravity.END
        }

        val serverInput = makeInput("السيرفر", prefs.getString("server", "51.254.130.47")!!)
        val portInput = makeInput("البورت", prefs.getString("port", "80")!!)
        val uuidInput = makeInput("UUID", prefs.getString("uuid", "free.facebook.com")!!)
        val payloadInput = makeInput("Payload", prefs.getString("payload", "HTTP/78 2026 HTTP/1.1 300 ok\r\nHost: proxy.exhxx.com:8080\r\nConnection: Keep-Alive\r\nProxy-Connection: Keep-Alive")!!)
        payloadInput.minLines = 4

        val saveBtn = Button(this).apply {
            text = "حفظ"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9B59B6.toInt())
            setPadding(0, 20, 0, 20)
        }

        saveBtn.setOnClickListener {
            prefs.edit()
                .putString("server", serverInput.text.toString())
                .putString("port", portInput.text.toString())
                .putString("uuid", uuidInput.text.toString())
                .putString("payload", payloadInput.text.toString())
                .apply()
            Toast.makeText(this, "تم الحفظ ✓", Toast.LENGTH_SHORT).show()
            finish()
        }

        layout.addView(title)
        layout.addView(makeLabel("target server"))
        layout.addView(serverInput)
        layout.addView(makeLabel("port"))
        layout.addView(portInput)
        layout.addView(makeLabel("uuid"))
        layout.addView(uuidInput)
        layout.addView(makeLabel("payload"))
        layout.addView(payloadInput)
        layout.addView(saveBtn)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
    }
}
