package dev.marco.ancsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the watch advertising as a connectable BLE peripheral
 * (so the iPhone can connect + bond), then runs [AncsGattClient] over that link to
 * receive notifications. The foreground type is `connectedDevice` so the OS keeps the
 * BLE connection alive while the watch screen is off.
 */
@SuppressLint("MissingPermission")
class AncsService : Service() {

    private val btManager by lazy { getSystemService(BluetoothManager::class.java) }
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var client: AncsGattClient? = null
    private var phone: BluetoothDevice? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AncsState.addLog("service onCreate")
        try {
            createChannels()
            startForegroundServiceNotification()
            registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            if (!hasBluetoothPermissions()) {
                AncsState.addLog("missing Bluetooth permissions — grant them in the app, then restart")
                stopSelf()
                return
            }
            AncsState.addLog("permissions ok; starting advertiser + GATT server")
            startGattServer()
            startAdvertising()
        } catch (e: Exception) {
            AncsState.addLog("onCreate failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(bondReceiver) }
        advertiser?.stopAdvertising(advertiseCallback)
        client?.close()
        gattServer?.close()
        AncsState.setConnection(ConnectionState.IDLE)
    }

    // --- advertising ----------------------------------------------------------
    private fun startAdvertising() {
        val adapter = btManager.adapter ?: return
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            AncsState.addLog("this watch cannot advertise as BLE peripheral")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Solicit the ANCS service: this is the ANCS-spec signal that tells iOS
        // "connect to me, I want your notifications", which is what gets a generic
        // peripheral to surface for pairing. A 128-bit UUID + the device name won't
        // both fit in the 31-byte advertisement, so the name goes in the scan response.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceSolicitationUuid(ParcelUuid(AncsProtocol.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        AncsState.addLog("calling startAdvertising…")
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            AncsState.setConnection(ConnectionState.ADVERTISING)
        }

        override fun onStartFailure(errorCode: Int) {
            AncsState.addLog("advertise failed: error $errorCode")
        }
    }

    // --- GATT server (lets iOS connect + bond) --------------------------------
    private fun startGattServer() {
        gattServer = btManager.openGattServer(this, gattServerCallback)
        // A minimal placeholder service so the peripheral is connectable. Its UUID is
        // arbitrary and intentionally NOT the ANCS UUID — ANCS lives on the iPhone, not
        // here; we only need something for iOS to connect to so bonding can start.
        val service = BluetoothGattService(
            LOCAL_PLACEHOLDER_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    AncsState.addLog("iPhone connected: ${device.address}")
                    phone = device
                    if (device.bondState == BluetoothDevice.BOND_NONE) {
                        device.createBond()
                    }
                    startClient(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    AncsState.addLog("iPhone disconnected")
                    client?.close()
                    client = null
                    AncsState.setConnection(ConnectionState.DISCONNECTED)
                }
            }
        }
    }

    private fun startClient(device: BluetoothDevice) {
        client?.close()
        client = AncsGattClient(this, device) { notification -> onAncsNotification(notification) }
        client?.connect()
    }

    // --- bonding --------------------------------------------------------------
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            if (bondState == BluetoothDevice.BOND_BONDED) {
                AncsState.addLog("bonded — re-discovering ANCS")
                client?.rediscoverServices()
            }
        }
    }

    // --- surfacing notifications ---------------------------------------------
    private fun onAncsNotification(notification: AncsNotification) {
        AncsState.setNotification(notification)
        val label = if (notification.isIncomingCall) "Incoming call" else (notification.appId ?: "Notification")
        val title = notification.title ?: label
        val text = if (notification.isIncomingCall) "Incoming call" else (notification.message ?: "")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(if (notification.isIncomingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(notification.uid, notif)
    }

    // --- foreground + channels ------------------------------------------------
    private fun startForegroundServiceNotification() {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Bridging iPhone notifications")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SERVICE_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(SERVICE_NOTIF_ID, notif)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Bridge service", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "iPhone alerts", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun hasBluetoothPermissions(): Boolean {
        val needed = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val CHANNEL_SERVICE = "bridge_service"
        private const val CHANNEL_ALERTS = "iphone_alerts"
        private const val SERVICE_NOTIF_ID = 1

        /** Arbitrary placeholder GATT service hosted on the watch so iOS can connect. */
        private val LOCAL_PLACEHOLDER_SERVICE: java.util.UUID =
            java.util.UUID.fromString("A1B2C3D4-0001-4000-8000-ABCDEF012345")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AncsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AncsService::class.java))
        }
    }
}
