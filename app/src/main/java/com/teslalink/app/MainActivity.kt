package com.teslalink.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.teslalink.app.ui.AppRoot
import com.teslalink.app.ui.theme.TeslaLinkTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            com.teslalink.app.debug.DebugLog.log("app", "权限请求结果: $result")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as App).container.appInForeground.value = true
        requestBlePermissions()
        val granted = permissionsPresent()
        com.teslalink.app.debug.DebugLog.log(
            "app",
            "启动 model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "adapter=${(getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled} " +
                "蓝牙权限已授予=$granted"
        )
        setContent {
            TeslaLinkTheme {
                val container = (application as App).container
                AppRoot(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as App).container.appInForeground.value = true
    }

    override fun onPause() {
        super.onPause()
        (application as App).container.appInForeground.value = false
    }

    private fun permissionsPresent(): Boolean {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return perms.all {
            ActivityCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            // Requested even on Android 12+ on purpose: without it, some OEM stacks
            // (OriginOS etc.) deliver a restricted BLE scan result set that can omit
            // the vehicle's advertisement. Match nRF Connect's posture exactly.
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
