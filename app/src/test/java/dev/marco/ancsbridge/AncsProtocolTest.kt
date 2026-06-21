package dev.marco.ancsbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AncsProtocolTest {

    @Test
    fun parsesIncomingCallNotificationSource() {
        // EventID=Added, Flags=Important, Category=IncomingCall, Count=1, UID=0x0000002A
        val bytes = byteArrayOf(0x00, 0x02, 0x01, 0x01, 0x2A, 0x00, 0x00, 0x00)
        val event = AncsProtocol.parseNotificationSource(bytes)!!
        assertEquals(AncsProtocol.EVENT_ADDED, event.eventId)
        assertEquals(AncsProtocol.CATEGORY_INCOMING_CALL, event.categoryId)
        assertEquals(1, event.categoryCount)
        assertEquals(0x2A, event.notificationUid)
        assertTrue(event.isIncomingCall)
    }

    @Test
    fun parsesLittleEndianUid() {
        val bytes = byteArrayOf(0x02, 0x00, 0x04, 0x01, 0x78.toByte(), 0x56, 0x34, 0x12)
        val event = AncsProtocol.parseNotificationSource(bytes)!!
        assertEquals(AncsProtocol.EVENT_REMOVED, event.eventId)
        assertTrue(event.isSocial)
        assertEquals(0x12345678, event.notificationUid)
    }

    @Test
    fun rejectsShortNotificationSource() {
        assertNull(AncsProtocol.parseNotificationSource(byteArrayOf(0x00, 0x01, 0x01)))
    }

    @Test
    fun buildsGetAttributesCommandWithLengths() {
        val cmd = AncsProtocol.buildGetNotificationAttributesCommand(
            notificationUid = 0x2A,
            attributes = listOf(
                AncsProtocol.AttributeRequest(AncsProtocol.ATTR_APP_IDENTIFIER),
                AncsProtocol.AttributeRequest(AncsProtocol.ATTR_TITLE, maxLength = 64),
            ),
        )
        // cmd(0) | uid LE(4) | appId(0) | title(1) + len 64 LE(0x40,0x00)
        val expected = byteArrayOf(0x00, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x01, 0x40, 0x00)
        assertArrayEquals(expected, cmd)
    }

    @Test
    fun parsesAttributesResponse() {
        val title = "Mom".toByteArray(Charsets.UTF_8)
        val response = mutableListOf<Byte>().apply {
            add(0x00)                                   // CommandID = GetNotificationAttributes
            addAll(listOf(0x2A, 0x00, 0x00, 0x00))      // UID = 0x2A (LE)
            add(AncsProtocol.ATTR_TITLE.toByte())       // AttributeID = Title
            add(title.size.toByte()); add(0x00)         // length LE
            addAll(title.toList())
        }.toByteArray()

        val parsed = AncsProtocol.parseGetAttributesResponse(response, expectedAttributeCount = 1)!!
        assertEquals(0x2A, parsed.notificationUid)
        assertEquals("Mom", parsed.title)
    }

    @Test
    fun returnsNullWhenResponseFragmentIncomplete() {
        // Header says a 5-byte title value but only 2 bytes have arrived so far.
        val partial = byteArrayOf(0x00, 0x2A, 0x00, 0x00, 0x00, 0x01, 0x05, 0x00, 'H'.code.toByte(), 'i'.code.toByte())
        assertNull(AncsProtocol.parseGetAttributesResponse(partial, expectedAttributeCount = 1))
    }
}
