package com.amaral.driverlab.driver

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds driver zips in memory, including the ELF headers the importer inspects. */
internal object TestPackages {

    /** A minimal but structurally valid ELF64 little-endian shared object header, plus padding. */
    fun elf(machine: Int = ElfSummary.EM_AARCH64, type: Int = 3, elfClass: Int = 2, padding: Int = 512): ByteArray {
        val bytes = ByteArray(64 + padding)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = elfClass.toByte()   // EI_CLASS
        bytes[5] = 1                   // EI_DATA, little-endian
        bytes[6] = 1                   // EI_VERSION
        bytes[16] = (type and 0xFF).toByte()
        bytes[17] = ((type shr 8) and 0xFF).toByte()
        bytes[18] = (machine and 0xFF).toByte()
        bytes[19] = ((machine shr 8) and 0xFF).toByte()
        return bytes
    }

    fun metaJson(
        libraryName: String = "libvulkan_freedreno.so",
        name: String = "Turnip A740 test build",
        minApi: Int = 30,
    ): String = """
        {
          "schemaVersion": 1,
          "name": "$name",
          "description": "unit test package",
          "author": "test",
          "vendor": "Mesa/Turnip",
          "driverVersion": "25.1.0",
          "packageVersion": "1.0.0",
          "minApi": $minApi,
          "libraryName": "$libraryName"
        }
    """.trimIndent()

    fun zip(entries: List<Pair<String, ByteArray>>): InputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    fun validPackage(
        libraryName: String = "libvulkan_freedreno.so",
        machine: Int = ElfSummary.EM_AARCH64,
        minApi: Int = 30,
    ): InputStream = zip(
        listOf(
            DriverImporter.META_JSON to metaJson(libraryName = libraryName, minApi = minApi).toByteArray(),
            libraryName to elf(machine = machine),
        ),
    )
}
