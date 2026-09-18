package pl.mazovia.offroad

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import pl.mazovia.offroad.designsystem.theme.MazoviaOffroadTheme
import pl.mazovia.offroad.domain.model.AppMode
import pl.mazovia.offroad.state.AppModeManager
import pl.mazovia.offroad.ui.MazoviaNavHost

class MainActivity : ComponentActivity() {

    private val appModeManager = AppModeManager()

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission responses if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restore mode from saved state
        savedInstanceState?.getString("app_mode")?.let { modeName ->
            try {
                appModeManager.restore(AppMode.valueOf(modeName))
            } catch (_: Exception) {}
        }

        setContent {
            LaunchedEffect(Unit) {
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                locationPermissionRequest.launch(permissions.toTypedArray())
            }

            MazoviaOffroadTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val app = application as MazoviaOffroadApp
                    val appMode by appModeManager.currentMode.collectAsState()

                    MazoviaNavHost(
                        appMode = appMode,
                        appModeManager = appModeManager,
                        app = app
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("app_mode", appModeManager.currentMode.value.name)
    }
}
