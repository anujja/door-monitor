package com.doormonitor.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doormonitor.data.SettingsRepository
import com.doormonitor.ui.theme.DoorMonitorTheme
import kotlinx.coroutines.flow.map

/**
 * Dedicated full-screen camera activity. Launched via the local API/MQTT (`/camera?id=`)
 * or by tapping a camera in the dashboard. Shows a single feed using the right engine.
 */
class CameraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID).orEmpty()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val repo = SettingsRepository(applicationContext)
        val cameraFlow = repo.settings.map { s -> s.cameras.firstOrNull { it.id == cameraId } }

        setContent {
            DoorMonitorTheme {
                val camera by cameraFlow.collectAsStateWithLifecycle(initialValue = null)
                Box(Modifier.fillMaxSize()) {
                    val cam = camera
                    if (cam != null) {
                        CameraSurface(camera = cam, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(
                            text = "Camera '$cameraId' not found",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    IconButton(
                        onClick = { finish() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
    }
}
