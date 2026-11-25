package com.example.screenview

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(48, 96, 48, 48)

        val text = TextView(this)
        text.text = "ScreenView работает"
        text.textSize = 22f

        val button = Button(this)
        button.text = "Старт"
        button.setOnClickListener {
            text.text = "Кнопка нажата"
        }

        layout.addView(text)
        layout.addView(button)
        setContentView(layout)
    }
}
