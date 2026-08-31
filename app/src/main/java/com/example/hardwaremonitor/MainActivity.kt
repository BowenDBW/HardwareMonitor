package com.example.hardwaremonitor

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.hardwaremonitor.data.Monitor
import com.example.hardwaremonitor.ui.MonitorScreen
import com.example.hardwaremonitor.ui.theme.HardwareMonitorTheme

class MainActivity : ComponentActivity() {

    private var monitor: Monitor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 监控页常亮，避免息屏打断观察
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 深色沉浸：透明系统栏 + 浅色图标
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        monitor = Monitor(applicationContext)
        setContent {
            HardwareMonitorTheme {
                MonitorScreen(monitor = monitor!!)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        monitor?.start()
    }

    override fun onStop() {
        monitor?.stop()
        super.onStop()
    }
}
