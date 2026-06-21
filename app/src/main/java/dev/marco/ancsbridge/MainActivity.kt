package dev.marco.ancsbridge

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startBridgeIfReady() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Auto-start the bridge on open so it just runs in the background; request
        // permissions first if they aren't granted yet.
        if (hasBluetoothPermissions()) {
            startBridgeIfReady()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
        setContent { BridgeScreen() }
    }

    private fun startBridgeIfReady() {
        if (hasBluetoothPermissions()) AncsService.start(this)
    }

    private fun hasBluetoothPermissions(): Boolean = listOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    ).all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

@Composable
private fun BridgeScreen() {
    val context = LocalContext.current
    val connection by AncsState.connection.collectAsStateWithLifecycle()
    val lastNotification by AncsState.lastNotification.collectAsStateWithLifecycle()
    val log by AncsState.log.collectAsStateWithLifecycle()

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "ANCS Bridge",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            Text(
                text = connection.label(),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )

            Button(onClick = { AncsService.start(context) }) { Text("Start") }
            Button(onClick = { AncsService.stop(context) }) { Text("Stop") }

            lastNotification?.let { n ->
                Text(
                    text = if (n.isIncomingCall) "Incoming call" else "Message",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = n.title ?: "(no caller info)",
                    style = MaterialTheme.typography.body1,
                    textAlign = TextAlign.Center,
                )
                if (!n.message.isNullOrBlank()) {
                    Text(
                        text = n.message,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (log.isNotEmpty()) {
                Text(
                    text = log.takeLast(8).joinToString("\n"),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.IDLE -> "Idle — tap Start"
    ConnectionState.ADVERTISING -> "Advertising — pair from iPhone Settings ▸ Bluetooth"
    ConnectionState.CONNECTED -> "Connected — bonding…"
    ConnectionState.BONDED -> "Bonded — subscribing…"
    ConnectionState.SUBSCRIBED -> "Ready ✓"
    ConnectionState.DISCONNECTED -> "Disconnected"
}
