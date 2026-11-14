package com.example.ilbbangandroid

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = "일빵빵 Android 테스트 성공!"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
        }

        setContentView(text)
    }
}
