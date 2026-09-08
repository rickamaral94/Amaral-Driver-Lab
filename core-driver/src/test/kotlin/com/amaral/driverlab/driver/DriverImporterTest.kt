package com.amaral.driverlab.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DriverImporterTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private var folderCount = 0

    private fun destination() = temporaryFolder.newFolder("package-${folderCount++}")

    private fun expectRejection(rejection: ImportRejection, block: () -> Unit) {
        try {
            block()
            fail("expected $rejection")
        } catch (e: DriverImportException) {
            assertEquals(e.message, rejection, e.rejection)
        }
    }

    @Test
    fun `imports a well formed package`() {
        val result = DriverImporter(deviceApiLevel = 34)
            .import(TestPackages.validPackage(), destination(), id = "pkg-1")

        assertEquals("Turnip A740 test build", result.displayName)
        assertEquals("libvulkan_freedreno.so", result.metadata.libraryName)
        assertTrue(result.libraryFile.isFile)
        assertTrue(result.elf.isAarch64SharedObject)
        assertEquals(64, result.libraryChecksum.length)
        assertTrue(result.extractedFiles.contains(DriverImporter.META_JSON))
    }

    /**
     * P1's foundation: the identifier is the hash of the library that will be handed to the
     * loader. Two packages whose zips differ but whose libraries are identical must produce the
     * same library checksum, and a changed library must change it.
     */
    @Test
    fun `the library checksum names the library, not the archive`() {
        val importer = DriverImporter(deviceApiLevel = 34)

        val plain = importer.import(TestPackages.validPackage(), destination(), "a")
        val withExtraFile = importer.import(
            TestPackages.zip(
                listOf(
                    DriverImporter.META_JSON to TestPackages.metaJson().toByteArray(),
                    "libvulkan_freedreno.so" to TestPackages.elf(),
                    "README.txt" to "a file that changes the archive but not the driver".toByteArray(),
                ),
            ),
            destination(),
            "b",
        )

        assertEquals(plain.libraryChecksum, withExtraFile.libraryChecksum)
        assertNotEquals(plain.packageChecksum, withExtraFile.packageChecksum)

        val different = importer.import(
            TestPackages.zip(
                listOf(
                    DriverImporter.META_JSON to TestPackages.metaJson().toByteArray(),
                    "libvulkan_freedreno.so" to TestPackages.elf(padding = 600),
                ),
            ),
            destination(),
            "c",
        )
        assertNotEquals(plain.libraryChecksum, different.libraryChecksum)
    }

    @Test
    fun `refuses an entry that would escape the destination`() {
        val evil = TestPackages.zip(
            listOf(
                "../escaped.so" to TestPackages.elf(),
                DriverImporter.META_JSON to TestPackages.metaJson().toByteArray(),
            ),
        )
        expectRejection(ImportRejection.PATH_TRAVERSAL) {
            DriverImporter().import(evil, destination(), "evil")
        }
    }

    @Test
    fun `refuses a library name in meta json that points outside the package`() {
        val evil = TestPackages.zip(
            listOf(
                DriverImporter.META_JSON to
                    TestPackages.metaJson(libraryName = "../../../../system/lib64/libvulkan.so").toByteArray(),
                "libvulkan_freedreno.so" to TestPackages.elf(),
            ),
        )
        expectRejection(ImportRejection.LIBRARY_NOT_IN_PACKAGE) {
            DriverImporter().import(evil, destination(), "evil")
        }
    }

    /**
     * A zip bomb declares a small size and expands without end. The limit is checked against
     * bytes actually written, so the declared size is never trusted.
     */
    @Test
    fun `stops an entry that expands past the limit`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DriverImporter.META_JSON))
            zip.write(TestPackages.metaJson().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("libvulkan_freedreno.so"))
            // Highly compressible, so the archive stays tiny while the entry does not.
            val chunk = ByteArray(64 * 1024)
            repeat(80) { zip.write(chunk) }
            zip.closeEntry()
        }
        expectRejection(ImportRejection.ENTRY_TOO_LARGE) {
            DriverImporter(maximumEntryBytes = 1024 * 1024)
                .import(ByteArrayInputStream(out.toByteArray()), destination(), "bomb")
        }
    }

    @Test
    fun `stops a package with too many entries`() {
        val entries = (0 until 20).map { "file$it.bin" to ByteArray(8) } +
            (DriverImporter.META_JSON to TestPackages.metaJson().toByteArray())
        expectRejection(ImportRejection.TOO_MANY_ENTRIES) {
            DriverImporter(maximumEntries = 5).import(TestPackages.zip(entries), destination(), "many")
        }
    }

    @Test
    fun `requires meta json`() {
        expectRejection(ImportRejection.MISSING_META_JSON) {
            DriverImporter().import(
                TestPackages.zip(listOf("libvulkan_freedreno.so" to TestPackages.elf())),
                destination(),
                "no-meta",
            )
        }
    }

    @Test
    fun `reports unreadable meta json rather than guessing`() {
        expectRejection(ImportRejection.UNREADABLE_META_JSON) {
            DriverImporter().import(
                TestPackages.zip(
                    listOf(
                        DriverImporter.META_JSON to "{ this is not json".toByteArray(),
                        "libvulkan_freedreno.so" to TestPackages.elf(),
                    ),
                ),
                destination(),
                "bad-meta",
            )
        }
    }

    @Test
    fun `requires meta json to name a library`() {
        expectRejection(ImportRejection.MISSING_LIBRARY_NAME) {
            DriverImporter().import(
                TestPackages.zip(
                    listOf(
                        DriverImporter.META_JSON to """{"schemaVersion":1,"name":"x"}""".toByteArray(),
                        "libvulkan_freedreno.so" to TestPackages.elf(),
                    ),
                ),
                destination(),
                "no-lib-name",
            )
        }
    }

    @Test
    fun `requires the named library to be present`() {
        expectRejection(ImportRejection.LIBRARY_NOT_IN_PACKAGE) {
            DriverImporter().import(
                TestPackages.zip(
                    listOf(
                        DriverImporter.META_JSON to TestPackages.metaJson(libraryName = "libmissing.so").toByteArray(),
                        "libvulkan_freedreno.so" to TestPackages.elf(),
                    ),
                ),
                destination(),
                "missing-lib",
            )
        }
    }

    @Test
    fun `refuses a library built for another architecture`() {
        expectRejection(ImportRejection.WRONG_ARCHITECTURE) {
            DriverImporter().import(
                TestPackages.validPackage(machine = ElfSummary.EM_X86_64),
                destination(),
                "wrong-arch",
            )
        }
    }

    @Test
    fun `refuses a file that is not a library at all`() {
        expectRejection(ImportRejection.NOT_AN_ELF_OBJECT) {
            DriverImporter().import(
                TestPackages.zip(
                    listOf(
                        DriverImporter.META_JSON to TestPackages.metaJson().toByteArray(),
                        "libvulkan_freedreno.so" to "gotcha".toByteArray(),
                    ),
                ),
                destination(),
                "not-elf",
            )
        }
    }

    @Test
    fun `refuses a package that needs a newer Android than this device`() {
        expectRejection(ImportRejection.MIN_API_TOO_HIGH) {
            DriverImporter(deviceApiLevel = 30)
                .import(TestPackages.validPackage(minApi = 34), destination(), "too-new")
        }
    }

    @Test
    fun `refuses something that is not a zip`() {
        expectRejection(ImportRejection.NOT_A_ZIP) {
            DriverImporter().import(
                ByteArrayInputStream("not a zip at all".toByteArray()),
                destination(),
                "not-zip",
            )
        }
    }

    @Test
    fun `keeps a library in a subdirectory of the package`() {
        val result = DriverImporter(deviceApiLevel = 34).import(
            TestPackages.zip(
                listOf(
                    DriverImporter.META_JSON to
                        TestPackages.metaJson(libraryName = "lib/arm64-v8a/libvulkan_freedreno.so").toByteArray(),
                    "lib/arm64-v8a/libvulkan_freedreno.so" to TestPackages.elf(),
                ),
            ),
            destination(),
            "nested",
        )
        assertTrue(result.libraryFile.path.endsWith("lib/arm64-v8a/libvulkan_freedreno.so"))
    }
}
