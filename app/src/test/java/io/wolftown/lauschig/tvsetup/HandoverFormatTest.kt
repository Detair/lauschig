package io.wolftown.lauschig.tvsetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The plaintext block inside the packet: two bytes length, JSON, zeros up to [HandoverFormat.PLAIN_SIZE]. */
class HandoverFormatTest {
    private val handover = PhoneHandover(local = "http://192.168.1.20:13378", public = "https://abs.example.org", refresh = "refresh-token-123")

    /** A block written by hand, so the reader is checked against the protocol and not only against the writer. */
    private fun block(json: String, size: Int = HandoverFormat.PLAIN_SIZE, length: Int? = null): ByteArray {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = length ?: bytes.size
        return ByteArray(size).also {
            it[0] = (n shr 8).toByte()
            it[1] = n.toByte()
            bytes.copyInto(it, 2)
        }
    }

    @Test fun roundTrip() {
        val plain = HandoverFormat.encode(handover)!!
        assertEquals(HandoverFormat.PLAIN_SIZE, plain.size)
        val read = HandoverFormat.decode(plain)!!
        assertEquals("http://192.168.1.20:13378", read.local)
        assertEquals("https://abs.example.org", read.public)
        assertEquals("refresh-token-123", read.refresh)
        assertEquals(1, read.v)
        assertEquals(handover, read)
    }

    @Test fun oneAddress_isEnough() {
        assertEquals(PhoneHandover(public = "https://abs.example.org", refresh = "r"), HandoverFormat.decode(block("""{"v":1,"public":"https://abs.example.org","refresh":"r"}""")))
        assertEquals(PhoneHandover(local = "http://192.168.1.20:13378", refresh = "r"), HandoverFormat.decode(block("""{"v":1,"local":"http://192.168.1.20:13378","refresh":"r"}""")))
    }

    @Test fun theWriter_leavesOutAMissingAddress() {
        val plain = HandoverFormat.encode(PhoneHandover(public = "https://abs.example.org", refresh = "r"))!!
        val json = String(plain, 2, ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF), Charsets.UTF_8)
        assertEquals("""{"v":1,"public":"https://abs.example.org","refresh":"r"}""", json)
    }

    @Test fun unknownFields_areDropped() {
        assertEquals(
            PhoneHandover(public = "https://abs.example.org", refresh = "r"),
            HandoverFormat.decode(block("""{"v":1,"public":"https://abs.example.org","refresh":"r","password":"secret"}""")),
        )
    }

    @Test fun tooLong_isNull() {
        assertNull(HandoverFormat.encode(handover.copyWithRefresh("x".repeat(HandoverFormat.PLAIN_SIZE))))
        // Exactly full still fits: 2 bytes of length plus 2046 bytes of JSON.
        val overhead = HandoverFormat.encode(handover.copyWithRefresh(""))!!.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        assertNotNull(HandoverFormat.encode(handover.copyWithRefresh("x".repeat(HandoverFormat.PLAIN_SIZE - 2 - overhead))))
        assertNull(HandoverFormat.encode(handover.copyWithRefresh("x".repeat(HandoverFormat.PLAIN_SIZE - 1 - overhead))))
    }

    @Test fun blocksOfDifferentContent_areTheSameSize() {
        val short = HandoverFormat.encode(PhoneHandover(public = "https://abs.example.org", refresh = "r"))!!
        val long = HandoverFormat.encode(handover.copyWithRefresh("x".repeat(1500)))!!
        assertEquals(short.size, long.size)
    }

    @Test fun aWrongBlockSize_isNull() {
        val plain = HandoverFormat.encode(handover)!!
        assertNull(HandoverFormat.decode(plain.copyOf(HandoverFormat.PLAIN_SIZE - 1)))
        assertNull(HandoverFormat.decode(plain.copyOf(HandoverFormat.PLAIN_SIZE + 1)))
        assertNull(HandoverFormat.decode(ByteArray(0)))
    }

    @Test fun aLengthBeyondTheBlock_isNull() {
        val json = """{"v":1,"public":"https://abs.example.org","refresh":"r"}"""
        assertNull(HandoverFormat.decode(block(json, length = HandoverFormat.PLAIN_SIZE - 1)))
        assertNull(HandoverFormat.decode(block(json, length = 0xFFFF)))
        assertNull(HandoverFormat.decode(block(json, length = 0)))
    }

    @Test fun somethingAfterTheJson_isNull() {
        val plain = block("""{"v":1,"public":"https://abs.example.org","refresh":"r"}""")
        plain[HandoverFormat.PLAIN_SIZE - 1] = 1
        assertNull(HandoverFormat.decode(plain))
    }

    @Test fun brokenContent_isNull() {
        listOf(
            """{"v":1,"public":"https://abs.example.org","refresh":"r"""",
            """not json""",
            """[1,2]""",
            """{"v":2,"public":"https://abs.example.org","refresh":"r"}""",
            """{"v":"1","public":"https://abs.example.org","refresh":"r"}""",
            """{"public":"https://abs.example.org","refresh":"r"}""",
            """{"v":1,"refresh":"r"}""",
            """{"v":1,"local":null,"public":null,"refresh":"r"}""",
            """{"v":1,"public":"https://user:pass@abs.example.org","refresh":"r"}""",
            """{"v":1,"public":"https://user@abs.example.org","refresh":"r"}""",
            """{"v":1,"public":"ftp://abs.example.org","refresh":"r"}""",
            """{"v":1,"public":"https://abs.example.org?x=1","refresh":"r"}""",
            """{"v":1,"public":"https://abs.example.org#x","refresh":"r"}""",
            """{"v":1,"public":"","refresh":"r"}""",
            """{"v":1,"public":"https://abs.example.org","refresh":""}""",
            """{"v":1,"public":"https://abs.example.org"}""",
        ).forEach { assertNull(it, HandoverFormat.decode(block(it))) }
    }

    @Test fun addresses_comeOutInTheCheckedForm() {
        val read = HandoverFormat.decode(block("""{"v":1,"local":"HTTP://192.168.1.20:13378/","public":"https://ABS.Example.org/","refresh":"r"}"""))!!
        assertEquals("http://192.168.1.20:13378", read.local)
        assertEquals("https://abs.example.org", read.public)
        assertEquals("https://abs.example.org", HandoverFormat.decode(block("""{"v":1,"public":"https://abs.example.org/","refresh":"r"}"""))!!.public)
    }

    @Test fun hostileNesting_isNull_withoutParsing() {
        // Deep enough that a recursive parser would overflow the stack if it ever saw it.
        val deep = 1000
        // Five characters per object level; 300 levels still fit into the 2046 bytes of JSON.
        val objects = 300
        listOf(
            "[".repeat(deep),
            "[".repeat(deep) + "]".repeat(deep),
            """{"v":1,"public":"https://abs.example.org","refresh":"r","x":""" + """{"a":""".repeat(objects) + "1" + "}".repeat(objects + 1),
            "{\"a\":".repeat(objects),
        ).forEach { assertNull(it.take(40), HandoverFormat.decode(block(it))) }
        val shallow = """{"v":1,"public":"https://abs.example.org","refresh":"r","x":{"a":[{"b":1}]}}"""
        assertEquals("depth 4 is still read", PhoneHandover(public = "https://abs.example.org", refresh = "r"), HandoverFormat.decode(block(shallow)))
    }

    @Test fun bracketsInsideStrings_doNotCountAsNesting() {
        val token = "[[[[[{{{{{\\\"[[[[[" // a quote escaped inside the token must not end the string for the scan
        val read = HandoverFormat.decode(block("""{"v":1,"public":"https://abs.example.org/[[[[[[","refresh":"$token"}"""))!!
        assertEquals("[[[[[{{{{{\"[[[[[", read.refresh)
        assertEquals(handover.copyWithRefresh("{{{{{{[[[[[[\"]]"), HandoverFormat.decode(HandoverFormat.encode(handover.copyWithRefresh("{{{{{{[[[[[[\"]]"))!!))
    }

    @Test fun bytesThatAreNoUtf8_areNull() {
        val plain = block("""{"v":1,"public":"https://abs.example.org","refresh":"r"}""")
        plain[30] = 0xC3.toByte()
        plain[31] = 0x28
        assertNull(HandoverFormat.decode(plain))
    }

    @Test fun toString_holdsNoTokenAndNoAddress() {
        listOf("refresh-token-123", "192.168.1.20", "abs.example.org").forEach { assertFalse(it, handover.toString().contains(it)) }
    }

    @Test fun replies_haveTheProtocolCodes() {
        assertEquals(listOf(1, 2, 3, 4, 5), HandoverReply.entries.map { it.code })
    }

    private fun PhoneHandover.copyWithRefresh(refresh: String) = PhoneHandover(v, local, public, refresh)
}
