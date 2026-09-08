package com.amaral.driverlab.driver

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Why an import was refused. Each maps to one sentence the user can act on. */
public enum class ImportRejection {
    NOT_A_ZIP,
    MISSING_META_JSON,
    UNREADABLE_META_JSON,
    MISSING_LIBRARY_NAME,
    LIBRARY_NOT_IN_PACKAGE,
    PATH_TRAVERSAL,
    ENTRY_TOO_LARGE,
    PACKAGE_TOO_LARGE,
    TOO_MANY_ENTRIES,
    NOT_AN_ELF_OBJECT,
    WRONG_ARCHITECTURE,
    MIN_API_TOO_HIGH,
}

public class DriverImportException(
    public val rejection: ImportRejection,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * A driver package that has been unpacked and checked, but not yet run.
 *
 * [libraryChecksum] is the SHA-256 of the `.so` that will actually be handed to the loader —
 * not of the zip, and not of anything `meta.json` claimed. It is the identifier a published
 * result is keyed on, so it has to name the bytes that executed.
 */
public data class DriverPackage(
    val id: String,
    val metadata: DriverMetadata,
    val installDirectory: File,
    val libraryFile: File,
    val libraryChecksum: String,
    val packageChecksum: String,
    val libraryBytes: Long,
    val elf: ElfSummary,
    val extractedFiles: List<String>,
) {
    /** What to show in a list. Falls back to the file name when the package did not name itself. */
    public val displayName: String
        get() = metadata.name.ifBlank { libraryFile.name }
}

/**
 * Unpacks a community driver zip into private storage.
 *
 * The zip is fully untrusted: it arrives through a file picker and its contents end up in
 * `dlopen`. Everything here is a defence against a specific way that goes wrong — entry names
 * escaping the destination, a small archive that expands until the device runs out of storage,
 * a library for the wrong architecture that would fail deep inside the linker.
 */
public class DriverImporter(
    private val maximumEntryBytes: Long = DEFAULT_MAXIMUM_ENTRY_BYTES,
    private val maximumTotalBytes: Long = DEFAULT_MAXIMUM_TOTAL_BYTES,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val deviceApiLevel: Int = Int.MAX_VALUE,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param source the zip, still compressed. Consumed once.
     * @param destination an empty directory the caller owns; it is populated, not cleaned up.
     */
    public fun import(source: InputStream, destination: File, id: String): DriverPackage {
        require(destination.isDirectory || destination.mkdirs()) {
            "could not create $destination"
        }
        val canonicalDestination = destination.canonicalFile

        var totalBytes = 0L
        var entryCount = 0
        val extracted = mutableListOf<String>()
        var sawAnyEntry = false

        // Hash the archive as it streams past, so the package checksum costs no extra read.
        val counting = DigestingInputStream(source)

        ZipInputStream(counting).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                sawAnyEntry = true
                entryCount++
                if (entryCount > maximumEntries) {
                    throw DriverImportException(
                        ImportRejection.TOO_MANY_ENTRIES,
                        "Package has more than $maximumEntries entries.",
                    )
                }

                val target = File(canonicalDestination, entry.name).canonicalFile
                if (!target.path.startsWith(canonicalDestination.path + File.separator) &&
                    target.path != canonicalDestination.path
                ) {
                    throw DriverImportException(
                        ImportRejection.PATH_TRAVERSAL,
                        "Entry \"${entry.name}\" would be written outside the package directory.",
                    )
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                    zip.closeEntry()
                    continue
                }

                target.parentFile?.mkdirs()
                var entryBytes = 0L
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        entryBytes += read
                        totalBytes += read
                        // Checked against the bytes written, not against the declared size:
                        // a zip bomb lies in the header and tells the truth only as it expands.
                        if (entryBytes > maximumEntryBytes) {
                            throw DriverImportException(
                                ImportRejection.ENTRY_TOO_LARGE,
                                "Entry \"${entry.name}\" is larger than ${maximumEntryBytes / (1024 * 1024)} MB.",
                            )
                        }
                        if (totalBytes > maximumTotalBytes) {
                            throw DriverImportException(
                                ImportRejection.PACKAGE_TOO_LARGE,
                                "Package expands past ${maximumTotalBytes / (1024 * 1024)} MB.",
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                }
                extracted += entry.name
                zip.closeEntry()
            }
        }
        // Drain whatever the zip reader left so the package hash covers the whole file.
        counting.drain()

        if (!sawAnyEntry) {
            throw DriverImportException(ImportRejection.NOT_A_ZIP, "The file is not a readable zip archive.")
        }

        val metaFile = File(canonicalDestination, META_JSON)
        if (!metaFile.isFile) {
            throw DriverImportException(
                ImportRejection.MISSING_META_JSON,
                "Package has no $META_JSON at its root.",
            )
        }
        val metadata = try {
            json.decodeFromString(DriverMetadata.serializer(), metaFile.readText())
        } catch (e: Exception) {
            throw DriverImportException(
                ImportRejection.UNREADABLE_META_JSON,
                "$META_JSON could not be parsed: ${e.message}",
                e,
            )
        }

        if (metadata.libraryName.isBlank()) {
            throw DriverImportException(
                ImportRejection.MISSING_LIBRARY_NAME,
                "$META_JSON does not say which library to load.",
            )
        }
        if (metadata.minApi > deviceApiLevel) {
            throw DriverImportException(
                ImportRejection.MIN_API_TOO_HIGH,
                "Package needs Android API ${metadata.minApi}; this device is API $deviceApiLevel.",
            )
        }

        // The library name is resolved against the destination the same way as any other entry,
        // so "../../libfoo.so" in meta.json cannot reach outside either.
        val libraryFile = File(canonicalDestination, metadata.libraryName).canonicalFile
        if (!libraryFile.path.startsWith(canonicalDestination.path + File.separator) || !libraryFile.isFile) {
            throw DriverImportException(
                ImportRejection.LIBRARY_NOT_IN_PACKAGE,
                "$META_JSON names \"${metadata.libraryName}\", which is not in the package.",
            )
        }

        val elf = ElfInspector.inspect(libraryFile)
            ?: throw DriverImportException(
                ImportRejection.NOT_AN_ELF_OBJECT,
                "\"${metadata.libraryName}\" is not an ELF object.",
            )
        if (!elf.isAarch64SharedObject) {
            throw DriverImportException(
                ImportRejection.WRONG_ARCHITECTURE,
                "\"${metadata.libraryName}\" is ${elf.describeMachine()}; this app runs arm64-v8a only.",
            )
        }

        return DriverPackage(
            id = id,
            metadata = metadata,
            installDirectory = canonicalDestination,
            libraryFile = libraryFile,
            libraryChecksum = Sha256.ofFile(libraryFile),
            packageChecksum = counting.hexDigest,
            libraryBytes = libraryFile.length(),
            elf = elf,
            extractedFiles = extracted,
        )
    }

    public companion object {
        public const val META_JSON: String = "meta.json"
        public const val DEFAULT_MAXIMUM_ENTRY_BYTES: Long = 96L * 1024 * 1024
        public const val DEFAULT_MAXIMUM_TOTAL_BYTES: Long = 256L * 1024 * 1024
        public const val DEFAULT_MAXIMUM_ENTRIES: Int = 512
    }
}

/** Streams through a digest so the archive's own checksum costs no second pass. */
private class DigestingInputStream(private val delegate: InputStream) : InputStream() {

    private val digest = java.security.MessageDigest.getInstance("SHA-256")
    private var cachedHex: String? = null

    /** Valid only after [drain]; the zip parser stops before the archive's trailing bytes. */
    val hexDigest: String
        get() = requireNotNull(cachedHex) { "hexDigest read before drain()" }

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) digest.update(value.toByte())
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) digest.update(b, off, read)
        return read
    }

    /** Reads whatever the zip parser did not, so the digest covers the entire file. */
    fun drain() {
        if (cachedHex != null) return
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = delegate.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        cachedHex = toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }
}
