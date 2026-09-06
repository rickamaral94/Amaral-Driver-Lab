package com.amaral.driverlab.driver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * SHA-256 to friendly label for builds the project knows about.
 *
 * An unknown hash is not an error. It runs, and it is marked `unverified` — testing a build nobody
 * has catalogued yet is the normal case for this app. What the list buys is that a *known* build
 * gets a stable name across devices and reports, so the leaderboard can group by build rather
 * than by whatever the packager typed into `meta.json`.
 */
@Serializable
public data class AllowlistEntry(
    val sha256: String,
    val label: String,
    val vendor: String = "",
    val driverVersion: String = "",
    val sourceUrl: String = "",
)

@Serializable
public data class AllowlistDocument(
    val schemaVersion: Int = 1,
    val updatedAt: String = "",
    val entries: List<AllowlistEntry> = emptyList(),
)

public class DriverAllowlist(entries: List<AllowlistEntry>) {

    private val byChecksum: Map<String, AllowlistEntry> =
        entries.associateBy { it.sha256.lowercase() }

    public val size: Int get() = byChecksum.size

    public fun lookup(sha256: String): AllowlistEntry? = byChecksum[sha256.lowercase()]

    public fun contains(sha256: String): Boolean = lookup(sha256) != null

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        public fun empty(): DriverAllowlist = DriverAllowlist(emptyList())

        public fun parse(text: String): DriverAllowlist =
            DriverAllowlist(json.decodeFromString(AllowlistDocument.serializer(), text).entries)
    }
}
