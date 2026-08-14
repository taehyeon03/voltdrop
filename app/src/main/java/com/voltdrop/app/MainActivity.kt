package com.voltdrop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.voltdrop.app.data.ChargingRepository
import com.voltdrop.app.service.ChargingService
import com.voltdrop.app.ui.HomeScreen

class MainActivity : ComponentActivity() {

    private lateinit var repo: ChargingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ChargingRepository(this, lifecycleScope)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF060C09),
                    surface = Color(0xFF0F1D16)
                )
            ) {
                val state by repo.state.collectAsState()
                HomeScreen(state = state, screenOn = true)
            }
        }
    }

    /** 화면이 앞에 있을 때만 1초 샘플링. 뒤로 가면 즉시 멈춘다. */
    override fun onResume() {
        super.onResume()
        repo.startForeground()
    }

    override fun onPause() {
        super.onPause()
        repo.stop()                    // 앱 쪽 루프는 완전히 정지
        ChargingService.start(this)    // 기록은 서비스가 30초 간격으로 이어받는다
    }
}
