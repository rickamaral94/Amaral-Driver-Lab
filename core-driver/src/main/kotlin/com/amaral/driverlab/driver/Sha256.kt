package com.amaral.driverlab.driver

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

public object Sha256 {

    private const val BUFFER_BYTES = 64 * 1024

    public fun ofFile(file: File): String = file.inputStream().use { ofStream(it) }

    public fun ofStream(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    public fun ofBytes(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
