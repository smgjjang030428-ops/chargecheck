package com.chargecheck.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

/** 화면 내용은 flavor마다 다르다: demo = 시나리오 재생, real = Pleos 어댑터. ([FlavorContent]) */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FlavorContent()
            }
        }
    }
}
