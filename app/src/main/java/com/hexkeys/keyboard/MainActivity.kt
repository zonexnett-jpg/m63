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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("hexkeys", MODE_PRIVATE)
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
                prefs.edit()
                    .remove("perm_letters").remove("perm_arabic").remove("perm_symbols").apply()
                Toast.makeText(this@MainActivity, "تمت إعادة الترتيب", Toast.LENGTH_SHORT).show()
            }
        })

        fun addSlider(title: String, maxValue: Int, start: Int, onChange: (Int) -> Unit) {
            root.addView(TextView(this).apply {
                text = title
                setPadding(0, pad, 0, 0)
            })
            root.addView(SeekBar(this).apply {
                max = maxValue
                progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        addSlider("ارتفاع الكيبورد عن الحافة السفلية", 190, prefs.getFloat("lift_dp", 0f).toInt()) {
            prefs.edit().putFloat("lift_dp", it.toFloat()).apply()
        }
        addSlider("انحناء زوايا الأزرار", 100, (prefs.getFloat("corner", 0.22f) * 100).toInt()) {
            prefs.edit().putFloat("corner", it / 100f).apply()
        }

        root.addView(EditText(this).apply { hint = "جرّب الكتابة هنا" })
        setContentView(root)
    }
}
