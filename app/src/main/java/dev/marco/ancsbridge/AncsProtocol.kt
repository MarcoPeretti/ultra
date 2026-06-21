package dev.marco.ancsbridge

import java.util.UUID

/**
 * Pure (no Android dependencies) encoder/decoder for the Apple Notification Center
 * Service (ANCS). Keeping this Android-free lets the parsing logic be covered by
 * fast JVM unit tests without any Bluetooth hardware.
 *
 * Reference: Apple ANCS Specification (CoreBluetooth / archived developer docs).
 */
object AncsProtocol {

    // --- GATT identifiers -----------------------------------------------------
    val SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    /** Standard Client Characteristic Configuration Descriptor (for enabling notify). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // --- EventID (Notification Source byte 0) ---------------------------------
    const val EVENT_ADDED = 0
    const val EVENT_MODIFIED = 1
    const val EVENT_REMOVED = 2

    // --- CategoryID (Notification Source byte 2) ------------------------------
    const val CATEGORY_OTHER = 0
    const val CATEGORY_INCOMING_CALL = 1
    const val CATEGORY_MISSED_CALL = 2
    const val CATEGORY_VOICEMAIL = 3
    const val CATEGORY_SOCIAL = 4          // WhatsApp / messaging apps land here
    const val CATEGORY_SCHEDULE = 5
    const val CATEGORY_EMAIL = 6
    const val CATEGORY_NEWS = 7
    const val CATEGORY_HEALTH = 8
    const val CATEGORY_BUSINESS = 9
    const val CATEGORY_LOCATION = 10
    const val CATEGORY_ENTERTAINMENT = 11

    // --- CommandID (Control Point / Data Source byte 0) -----------------------
    const val COMMAND_GET_NOTIFICATION_ATTRIBUTES = 0

    // --- AttributeID ----------------------------------------------------------
    const val ATTR_APP_IDENTIFIER = 0      // no max-length parameter
    const val ATTR_TITLE = 1               // requires 2-byte max length
    const val ATTR_SUBTITLE = 2            // requires 2-byte max length
    const val ATTR_MESSAGE = 3             // requires 2-byte max length
    const val ATTR_MESSAGE_SIZE = 4
    const val ATTR_DATE = 5

    /** Parsed Notification Source packet (8 bytes). */
    data class NotificationSourceEvent(
        val eventId: Int,
        val eventFlags: Int,
        val categoryId: Int,
        val categoryCount: Int,
        val notificationUid: Int,
    ) {
        val isIncomingCall: Boolean get() = categoryId == CATEGORY_INCOMING_CALL
        val isSocial: Boolean get() = categoryId == CATEGORY_SOCIAL
    }

    /**
     * Parses the 8-byte Notification Source characteristic value.
     * Returns null if the buffer is malformed (too short).
     */
    fun parseNotificationSource(bytes: ByteArray): NotificationSourceEvent? {
        if (bytes.size < 8) return null
        return NotificationSourceEvent(
            eventId = bytes[0].u(),
            eventFlags = bytes[1].u(),
            categoryId = bytes[2].u(),
            categoryCount = bytes[3].u(),
            notificationUid = readUInt32LE(bytes, 4),
        )
    }

    /** One attribute to request, with an optional UTF-8 max length (Title/Subtitle/Message). */
    data class AttributeRequest(val attributeId: Int, val maxLength: Int? = null)

    /**
     * Builds a "Get Notification Attributes" command to write to the Control Point.
     * Layout: CommandID(1) | NotificationUID(4 LE) | [ AttributeID(1) | MaxLen(2 LE)? ]...
     */
    fun buildGetNotificationAttributesCommand(
        notificationUid: Int,
        attributes: List<AttributeRequest>,
    ): ByteArray {
        val out = ArrayList<Byte>(8 + attributes.size * 3)
        out.add(COMMAND_GET_NOTIFICATION_ATTRIBUTES.toByte())
        writeUInt32LE(out, notificationUid)
        for (attr in attributes) {
            out.add(attr.attributeId.toByte())
            if (attr.maxLength != null) {
                out.add((attr.maxLength and 0xFF).toByte())
                out.add(((attr.maxLength shr 8) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /** Parsed Data Source response to a Get Notification Attributes command. */
    data class AttributesResponse(
        val commandId: Int,
        val notificationUid: Int,
        val attributes: Map<Int, String>,
    ) {
        val title: String? get() = attributes[ATTR_TITLE]
        val message: String? get() = attributes[ATTR_MESSAGE]
        val appIdentifier: String? get() = attributes[ATTR_APP_IDENTIFIER]
    }

    /**
     * Attempts to parse a (possibly reassembled) Data Source buffer.
     *
     * Data Source responses can be fragmented across several GATT notifications, so
     * callers accumulate bytes and retry. Returns null while more bytes are needed
     * (header incomplete, or fewer than [expectedAttributeCount] attributes present).
     *
     * Response layout: CommandID(1) | NotificationUID(4 LE) |
     *                  [ AttributeID(1) | Length(2 LE) | Value(Length, UTF-8) ]...
     */
    fun parseGetAttributesResponse(bytes: ByteArray, expectedAttributeCount: Int): AttributesResponse? {
        if (bytes.size < 5) return null
        val commandId = bytes[0].u()
        val uid = readUInt32LE(bytes, 1)
        val attributes = LinkedHashMap<Int, String>()
        var offset = 5
        while (attributes.size < expectedAttributeCount) {
            if (offset + 3 > bytes.size) return null            // need attribute header
            val attributeId = bytes[offset].u()
            val length = readUInt16LE(bytes, offset + 1)
            val valueStart = offset + 3
            if (valueStart + length > bytes.size) return null   // value not fully arrived
            val value = String(bytes, valueStart, length, Charsets.UTF_8)
            attributes[attributeId] = value
            offset = valueStart + length
        }
        return AttributesResponse(commandId, uid, attributes)
    }

    // --- little-endian helpers ------------------------------------------------
    private fun Byte.u(): Int = toInt() and 0xFF

    private fun readUInt16LE(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32LE(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or
            ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or
            ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun writeUInt32LE(out: MutableList<Byte>, value: Int) {
        out.add((value and 0xFF).toByte())
        out.add(((value shr 8) and 0xFF).toByte())
        out.add(((value shr 16) and 0xFF).toByte())
        out.add(((value shr 24) and 0xFF).toByte())
    }
}
