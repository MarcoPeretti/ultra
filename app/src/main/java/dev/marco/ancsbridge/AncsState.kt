package dev.marco.ancsbridge

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single notification surfaced from the iPhone over ANCS. */
data class AncsNotification(
    val uid: Int,
    val categoryId: Int,
    val title: String?,
    val message: String?,
    val appId: String?,
    val receivedAtMs: Long = System.currentTimeMillis(),
) {
    val isIncomingCall: Boolean get() = categoryId == AncsProtocol.CATEGORY_INCOMING_CALL
}

enum class ConnectionState { IDLE, ADVERTISING, CONNECTED, BONDED, SUBSCRIBED, DISCONNECTED }

/**
 * Process-wide observable state shared between [AncsService] and the Compose UI.
 * Kept tiny and singleton because there is only ever one phone link.
 */
object AncsState {
    private val _connection = MutableStateFlow(ConnectionState.IDLE)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _lastNotification = MutableStateFlow<AncsNotification?>(null)
    val lastNotification: StateFlow<AncsNotification?> = _lastNotification.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun setConnection(state: ConnectionState) {
        _connection.value = state
        addLog("state → $state")
    }

    fun setNotification(notification: AncsNotification) {
        _lastNotification.value = notification
    }

    fun addLog(line: String) {
        Log.i(TAG, line)
        _log.value = (_log.value + line).takeLast(50)
    }

    private const val TAG = "AncsBridge"
}
