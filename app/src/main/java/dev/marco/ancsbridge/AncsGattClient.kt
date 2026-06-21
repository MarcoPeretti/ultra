package dev.marco.ancsbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.ArrayDeque

/**
 * Drives the watch side of ANCS as a GATT *client* against the connected iPhone.
 *
 * Flow: connect → discover services → (iOS exposes ANCS only once bonded) → enable
 * notifications on Notification Source + Data Source → on an incoming-call/social
 * "Added" event, ask the Control Point for the title/message and assemble the reply
 * from Data Source fragments.
 *
 * Permission checks live in [AncsService] before this is constructed, hence the
 * class-level MissingPermission suppression.
 */
@SuppressLint("MissingPermission")
class AncsGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onNotification: (AncsNotification) -> Unit,
) {
    private var gatt: BluetoothGatt? = null

    // Serialises GATT writes — the stack allows only one outstanding operation.
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    // Tracks the in-progress Get Notification Attributes round-trip.
    private var pendingUid: Int = 0
    private var pendingCategory: Int = AncsProtocol.CATEGORY_OTHER
    private var pendingAttributeCount: Int = 0
    private var dataSourceBuffer = ByteArray(0)

    fun connect() {
        AncsState.addLog("connecting GATT client to ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Re-run discovery — call after bonding completes, since iOS only then exposes ANCS. */
    fun rediscoverServices() {
        gatt?.discoverServices()
    }

    fun close() {
        gatt?.close()
        gatt = null
        opQueue.clear()
        opInFlight = false
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    AncsState.setConnection(ConnectionState.CONNECTED)
                    g.requestMtu(512) // larger MTU → fewer Data Source fragments
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    AncsState.setConnection(ConnectionState.DISCONNECTED)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            AncsState.addLog("MTU = $mtu, discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ancs = g.getService(AncsProtocol.SERVICE_UUID)
            if (ancs == null) {
                // iOS withholds ANCS until the devices are bonded. The service triggers
                // bonding and re-discovers; log so this is visible during bring-up.
                AncsState.addLog("ANCS service not visible yet (bond required?)")
                return
            }
            AncsState.setConnection(ConnectionState.BONDED)
            // Enable Data Source first so attribute replies aren't missed, then the source.
            ancs.getCharacteristic(AncsProtocol.DATA_SOURCE_UUID)?.let { enableNotify(g, it) }
            ancs.getCharacteristic(AncsProtocol.NOTIFICATION_SOURCE_UUID)?.let {
                enableNotify(g, it)
                AncsState.setConnection(ConnectionState.SUBSCRIBED)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            operationComplete()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            operationComplete()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            when (c.uuid) {
                AncsProtocol.NOTIFICATION_SOURCE_UUID -> handleNotificationSource(value)
                AncsProtocol.DATA_SOURCE_UUID -> handleDataSource(value)
            }
        }
    }

    private fun handleNotificationSource(value: ByteArray) {
        val event = AncsProtocol.parseNotificationSource(value) ?: return
        AncsState.addLog("notif uid=${event.notificationUid} cat=${event.categoryId} evt=${event.eventId}")
        if (event.eventId != AncsProtocol.EVENT_ADDED) return
        // MVP filter: calls now, social (WhatsApp) once you broaden this set.
        if (!event.isIncomingCall && !event.isSocial) return
        requestAttributes(event)
    }

    private fun requestAttributes(event: AncsProtocol.NotificationSourceEvent) {
        val requests = listOf(
            AncsProtocol.AttributeRequest(AncsProtocol.ATTR_APP_IDENTIFIER),
            AncsProtocol.AttributeRequest(AncsProtocol.ATTR_TITLE, maxLength = 64),
            AncsProtocol.AttributeRequest(AncsProtocol.ATTR_MESSAGE, maxLength = 256),
        )
        pendingUid = event.notificationUid
        pendingCategory = event.categoryId
        pendingAttributeCount = requests.size
        dataSourceBuffer = ByteArray(0)

        val controlPoint = gatt?.getService(AncsProtocol.SERVICE_UUID)
            ?.getCharacteristic(AncsProtocol.CONTROL_POINT_UUID) ?: return
        val command = AncsProtocol.buildGetNotificationAttributesCommand(event.notificationUid, requests)
        enqueue {
            gatt?.writeCharacteristic(
                controlPoint,
                command,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        }
    }

    private fun handleDataSource(chunk: ByteArray) {
        dataSourceBuffer += chunk
        val response = AncsProtocol.parseGetAttributesResponse(dataSourceBuffer, pendingAttributeCount)
            ?: return // more fragments to come
        if (response.notificationUid != pendingUid) return
        onNotification(
            AncsNotification(
                uid = response.notificationUid,
                categoryId = pendingCategory,
                title = response.title,
                message = response.message,
                appId = response.appIdentifier,
            ),
        )
        dataSourceBuffer = ByteArray(0)
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(AncsProtocol.CCCD_UUID) ?: return
        enqueue { g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
    }

    // --- minimal serial operation queue --------------------------------------
    private fun enqueue(op: () -> Unit) {
        opQueue.add(op)
        if (!opInFlight) runNext()
    }

    private fun runNext() {
        val op = opQueue.poll() ?: return
        opInFlight = true
        try {
            op()
        } catch (e: Exception) {
            Log.w(TAG, "GATT op failed", e)
            operationComplete()
        }
    }

    private fun operationComplete() {
        opInFlight = false
        runNext()
    }

    companion object {
        private const val TAG = "AncsGattClient"
    }
}
