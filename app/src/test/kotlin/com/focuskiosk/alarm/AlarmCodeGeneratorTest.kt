package com.focuskiosk.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors produced by the PC portal (docs/index.html) — phone and portal must agree. */
class AlarmCodeGeneratorTest {

    @Test
    fun matchesPortalVectors() {
        assertEquals("275955462717", AlarmCodeGenerator.codeForStep("jisan", "1234", 14868000))
        assertEquals("658106945536", AlarmCodeGenerator.codeForStep("Jisan ", "1234", 14868001))
        assertEquals("222009283229", AlarmCodeGenerator.codeForStep("Jisan ", "k7q2-m9xd", 14868001))
        assertEquals("222009283229", AlarmCodeGenerator.codeForStep("jisan", "K7Q2M9XD", 14868001))
        assertEquals("039646448297", AlarmCodeGenerator.codeForStep("x", "", 99999999))
    }

    @Test
    fun keyCheckMatchesPortal() {
        assertEquals("35F7", AlarmCodeGenerator.keyCheck("jisan", "1234"))
        assertEquals("B860", AlarmCodeGenerator.keyCheck("jisan", "K7Q2-M9XD"))
        assertEquals("8264", AlarmCodeGenerator.keyCheck("x", ""))
    }

    @Test
    fun acceptsRecentCodesOnly() {
        val now = 14868000L * 120_000L + 30_000L
        val step = AlarmCodeGenerator.stepAt(now)
        fun code(offset: Long) = AlarmCodeGenerator.codeForStep("jisan", "1234", step + offset)
        assertTrue(AlarmCodeGenerator.isValid("jisan", "1234", code(0), now))
        assertTrue(AlarmCodeGenerator.isValid("jisan", "1234", code(-4), now))
        assertTrue(AlarmCodeGenerator.isValid("jisan", "1234", code(1), now))
        assertFalse(AlarmCodeGenerator.isValid("jisan", "1234", code(-5), now))
        assertFalse(AlarmCodeGenerator.isValid("jisan", "1234", code(2), now))
        assertFalse(AlarmCodeGenerator.isValid("jisan", "wrong", code(0), now))
        assertTrue(AlarmCodeGenerator.isValid("jisan", "1234", code(0).chunked(4).joinToString(" "), now))
    }
}
