package com.amaral.driverlab.driver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `meta.json` as it appears inside a community driver zip.
 *
 * Every field here is a *claim by the package author*. It names the file to load and gives the
 * user something readable in a list, and that is all it is trusted for. What actually ran is
 * decided by [DriverIdentity], read back from Vulkan after the ICD is loaded. Section 5 of the
 * spec exists because a previous generation of this tool believed this file.
 */
@Serializable
public data class DriverMetadata(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("author") val author: String = "",
    @SerialName("vendor") val vendor: String = "",
    @SerialName("driverVersion") val driverVersion: String = "",
    @SerialName("packageVersion") val packageVersion: String = "",
    @SerialName("minApi") val minApi: Int = 0,
    @SerialName("libraryName") val libraryName: String = "",
)
