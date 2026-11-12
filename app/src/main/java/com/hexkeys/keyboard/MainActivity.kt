package com.hexkeys.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "لوحة المفاتيح السداسية"
            textSize = 24f
        })
        root.addView(Button(this).apply {
            text = "١. تفعيل لوحة المفاتيح"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "٢. اختيار لوحة المفاتيح"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        root.addView(Button(this).apply {
            text = "إعادة ترتيب الأزرار الافتراضي"
            setOnClickListener {
                getSharedPreferences("hexkeys", MODE_PRIVATE).edit()
                    .remove("perm_letters").remove("perm_arabic").remove("perm_symbols").apply()
                Toast.makeText(this@MainActivity, "تمت إعادة الترتيب", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(EditText(this).apply { hint = "جرّب الكتابة هنا" })
        setContentView(root)
    }
}
