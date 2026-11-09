package com.homeflix.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPinTest {
    @Test
    fun `kinds generate valid values`() {
        val pin4 = ServerPin.generate(PinKind.PIN4)
        assertTrue(pin4.matches(Regex("\\d{4}")))
        assertTrue(ServerPin.isValid(pin4, PinKind.PIN4))

        val pin6 = ServerPin.generate(PinKind.PIN6)
        assertTrue(pin6.matches(Regex("\\d{6}")))
        assertTrue(ServerPin.isValid(pin6, PinKind.PIN6))

        repeat(20) {
            val password = ServerPin.generate(PinKind.PASSWORD)
            assertTrue(ServerPin.isValid(password, PinKind.PASSWORD))
            assertTrue(password.any(Char::isLetter))
            assertTrue(password.any(Char::isDigit))
            assertFalse(password.contains('0') && password.contains('O') && password.contains('l'))
        }
    }

    @Test
    fun `kindOf classifies values`() {
        assertEquals(PinKind.PIN4, ServerPin.kindOf("1234"))
        assertEquals(PinKind.PIN6, ServerPin.kindOf("123456"))
        assertEquals(PinKind.PASSWORD, ServerPin.kindOf("Homeflix2026"))
        assertEquals(PinKind.PASSWORD, ServerPin.kindOf("a1b2c3"))
    }

    @Test
    fun `validation rejects bad inputs`() {
        assertTrue(ServerPin.isValid("1234", PinKind.PIN4))
        assertFalse(ServerPin.isValid("12345", PinKind.PIN4))
        assertFalse(ServerPin.isValid("123abc", PinKind.PIN4))
        assertTrue(ServerPin.isValid("Homeflix2026", PinKind.PASSWORD))
        assertFalse(ServerPin.isValid("abcdefg", PinKind.PASSWORD)) // no digit
        assertFalse(ServerPin.isValid("12345678", PinKind.PASSWORD)) // no letter
        assertFalse(ServerPin.isValid("12345", PinKind.PASSWORD)) // shorter than 6
        assertFalse(ServerPin.isValid("has space1", PinKind.PASSWORD))
        assertFalse(ServerPin.isValid("123", PinKind.PIN4))
    }
}
