package com.amaral.driverlab.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfInspectorTest {

    @Test
    fun `recognises an aarch64 shared object`() {
        val summary = ElfInspector.inspect(TestPackages.elf())!!
        assertTrue(summary.isAarch64SharedObject)
        assertEquals("aarch64", summary.describeMachine())
    }

    @Test
    fun `rejects a build for another architecture`() {
        val summary = ElfInspector.inspect(TestPackages.elf(machine = ElfSummary.EM_X86_64))!!
        assertFalse(summary.isAarch64SharedObject)
        assertEquals("x86-64", summary.describeMachine())
    }

    @Test
    fun `rejects a 32-bit build`() {
        val summary = ElfInspector.inspect(TestPackages.elf(machine = ElfSummary.EM_ARM, elfClass = 1))!!
        assertFalse(summary.is64Bit)
        assertFalse(summary.isAarch64SharedObject)
    }

    @Test
    fun `rejects an executable that is not a shared object`() {
        // ET_EXEC rather than ET_DYN: dlopen would refuse it, so the importer should say why first.
        val summary = ElfInspector.inspect(TestPackages.elf(type = 2))!!
        assertFalse(summary.isSharedObject)
        assertFalse(summary.isAarch64SharedObject)
    }

    @Test
    fun `a file that is not ELF at all returns nothing`() {
        assertNull(ElfInspector.inspect("this is a text file, not a library".toByteArray()))
    }

    @Test
    fun `a truncated header is not guessed at`() {
        assertNull(ElfInspector.inspect(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte())))
    }
}
