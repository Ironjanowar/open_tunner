package com.cuwano.afinador

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cuwano.afinador.audio.AndroidAudioInputFactory
import com.cuwano.afinador.tuner.TunerCaptureCoordinator
import com.cuwano.afinador.tuner.TunerEngineProcessor
import com.cuwano.afinador.tuner.TunerPermissionPolicy
import com.cuwano.afinador.tuner.TunerSession
import com.cuwano.afinador.tuner.TunerUiState
import com.cuwano.afinador.ui.TunerScreen
import kotlinx.coroutines.delay

/**
 * Thin shell: owns the capture coordinator, tracks the runtime permission and the
 * RESUMED lifecycle gate, and renders [TunerScreen]. All wiring decisions live in
 * [TunerCaptureCoordinator] / [TunerPermissionPolicy].
 */
class MainActivity : ComponentActivity() {

    private lateinit var capture: TunerCaptureCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        capture = TunerCaptureCoordinator(
            TunerSession(
                audioInputFactory = AndroidAudioInputFactory(),
                processorFactory = { sampleRate -> TunerEngineProcessor(sampleRate) },
            ),
        )

        val permissionGranted = mutableStateOf(hasRecordAudioPermission())
        val permanentlyDenied = mutableStateOf(false)
        val hasRequested = mutableStateOf(false)
        val resumed = mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        val granted = hasRecordAudioPermission()
                        permissionGranted.value = granted
                        if (granted) permanentlyDenied.value = false
                        resumed.value = true
                    }
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> resumed.value = false
                    else -> Unit
                }
            },
        )

        setContent {
            val uiState = remember { mutableStateOf<TunerUiState>(TunerUiState.PermissionNeeded) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                permissionGranted.value = granted
                permanentlyDenied.value = TunerPermissionPolicy.isPermanentlyDenied(
                    granted = granted,
                    shouldShowRationale = shouldShowRequestPermissionRationale(RECORD_AUDIO),
                    hasRequested = hasRequested.value,
                )
            }

            // Start capture on grant + RESUMED, release the microphone on pause/stop.
            LaunchedEffect(permissionGranted.value, resumed.value) {
                capture.reconcile(
                    permissionGranted = permissionGranted.value,
                    lifecycleResumed = resumed.value,
                )
            }

            // Poll the session off the compose frame; never blocks the main thread.
            LaunchedEffect(Unit) {
                while (true) {
                    uiState.value = capture.uiState(
                        permissionGranted = permissionGranted.value,
                        permanentlyDenied = permanentlyDenied.value,
                    )
                    delay(STATE_REFRESH_INTERVAL_MS)
                }
            }

            TunerScreen(
                state = uiState.value,
                onRequestPermission = {
                    hasRequested.value = true
                    permissionLauncher.launch(RECORD_AUDIO)
                },
                onOpenSettings = ::openAppSettings,
            )
        }
    }

    override fun onDestroy() {
        capture.release()
        super.onDestroy()
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private companion object {
        const val STATE_REFRESH_INTERVAL_MS = 50L
        val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
    }
}
