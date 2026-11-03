package com.chargecheck.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

/** 화면 내용은 flavor마다 다르다: demo = 시나리오 재생, real = Pleos 어댑터. ([FlavorContent]) */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFECEDEE), onPrimary = Color(0xFF121314),
                background = Color(0xFF121314), onBackground = Color(0xFFECEDEE),
                surface = Color(0xFF1E2022), onSurface = Color(0xFFECEDEE),
            )) {
                FlavorContent()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground.value = true
    }

    override fun onStop() {
        AppVisibility.foreground.value = false
        super.onStop()
    }
}

/** 앱 화면이 보이는지. 보이면 카드는 화면에, 안 보이면(내비를 보는 중) 알림으로 띄운다. */
object AppVisibility {
    val foreground = MutableStateFlow(false)
}
