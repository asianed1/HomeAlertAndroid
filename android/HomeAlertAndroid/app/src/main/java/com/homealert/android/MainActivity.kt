package com.homealert.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.homealert.android.ui.HomeAlertApp
import com.homealert.android.ui.HomeAlertViewModel
import com.homealert.android.ui.theme.HomeAlertTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HomeAlertViewModel by viewModels()
    private var hasCameraPermission by mutableStateOf(false)
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasCameraPermission = isPermissionGranted(Manifest.permission.CAMERA)
        viewModel.refreshSims()
        viewModel.refreshGallery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCameraPermission = isPermissionGranted(Manifest.permission.CAMERA)
        requestAllRequiredPermissions()

        setContent {
            HomeAlertTheme {
                HomeAlertApp(
                    viewModel = viewModel,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermissions = ::requestAllRequiredPermissions,
                    onKeepScreenOnChanged = ::applyKeepScreenOnPolicy,
                    onStealthBrightnessChanged = ::applyStealthWindowPolicy
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = isPermissionGranted(Manifest.permission.CAMERA)
        if (granted != hasCameraPermission) {
            hasCameraPermission = granted
            viewModel.refreshSims()
            viewModel.refreshGallery()
        }
    }

    private fun applyKeepScreenOnPolicy(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyStealthWindowPolicy(stealthEnabled: Boolean) {
        val attrs = window.attributes
        if (stealthEnabled) {
            if (savedBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                savedBrightness = attrs.screenBrightness
            }
            attrs.screenBrightness = 0.01f
            window.attributes = attrs
        } else if (savedBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            attrs.screenBrightness = savedBrightness
            savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        val missing = perms.filterNot(::isPermissionGranted)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
