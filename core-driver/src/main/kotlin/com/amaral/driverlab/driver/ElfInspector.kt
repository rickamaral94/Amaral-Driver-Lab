package com.amaral.driverlab.driver

import java.io.File
import java.io.RandomAccessFile

/**
 * Enough ELF parsing to answer one question: is this file something this device could load?
 *
 * The zip is untrusted input that ends up in `dlopen`. Checking the header before extraction
 * turns "a native crash somewhere in the loader" into a readable error, and rejects the common
 * mistakes — a 32-bit build, an x86 build, a text file renamed to `.so`.
 */
public data class ElfSummary(
    val is64Bit: Boolean,
    val isLittleEndian: Boolean,
    val isSharedObject: Boolean,
    val machine: Int,
) {
    public val isAarch64SharedObject: Boolean
        get() = is64Bit && isLittleEndian && isSharedObject && machine == EM_AARCH64

    public fun describeMachine(): String = when (machine) {
        EM_AARCH64 -> "aarch64"
        EM_ARM -> "arm (32-bit)"
        EM_X86_64 -> "x86-64"
        EM_386 -> "x86"
        else -> "unknown machine 0x${machine.toString(16)}"
    }

    public companion object {
        public const val EM_386: Int = 3
        public const val EM_ARM: Int = 40
        public const val EM_X86_64: Int = 62
        public const val EM_AARCH64: Int = 183
    }
}

public object ElfInspector {

    private const val ET_DYN = 3
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val HEADER_BYTES = 20

    /** @return null when the file is not an ELF object at all. */
    public fun inspect(file: File): ElfSummary? {
        if (file.length() < HEADER_BYTES) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_BYTES)
            raf.readFully(header)
            return inspect(header)
        }
    }

    public fun inspect(header: ByteArray): ElfSummary? {
        if (header.size < HEADER_BYTES) return null
        val magicMatches = header[0] == 0x7F.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        if (!magicMatches) return null

        val is64Bit = header[4].toInt() == ELFCLASS64
        val isLittleEndian = header[5].toInt() == ELFDATA2LSB
        // e_type at offset 16 and e_machine at offset 18 are both 2 bytes, little-endian here.
        val type = readUShortLe(header, 16)
        val machine = readUShortLe(header, 18)
        return ElfSummary(
            is64Bit = is64Bit,
            isLittleEndian = isLittleEndian,
            isSharedObject = type == ET_DYN,
            machine = machine,
        )
    }

    private fun readUShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
