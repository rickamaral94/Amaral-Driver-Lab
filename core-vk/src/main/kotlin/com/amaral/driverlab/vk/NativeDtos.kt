package com.amaral.driverlab.vk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the JSON the native layer returns. Deliberately separate from the
 * report schema: the native side is free to grow a field without that being a
 * schema change anybody has to migrate.
 */
@Serializable
internal data class NativeStatus(
    val ok: Boolean = false,
    val stage: String = "",
    val message: String = "",
    val identity: NativeIdentity? = null,
)

@Serializable
internal data class NativeIdentity(
    val driverId: Int = 0,
    val driverName: String = "",
    val driverInfo: String = "",
    val hasConformanceVersion: Boolean = false,
    val conformanceMajor: Int = 0,
    val conformanceMinor: Int = 0,
    val conformanceSubminor: Int = 0,
    val conformancePatch: Int = 0,
    val deviceName: String = "",
    val vendorId: Long = 0,
    val deviceId: Long = 0,
    val apiVersion: Long = 0,
    val driverVersion: Long = 0,
    val timestampPeriod: Float = 0f,
    val timestampValidBits: Int = 0,
    val maxImageDimension2D: Long = 0,
    val maxColorAttachments: Long = 0,
    val framebufferColorSampleCounts: Long = 0,
    val framebufferDepthSampleCounts: Long = 0,
    val deviceLocalHeapBytes: Long = 0,
    val validationEnabled: Boolean = false,
    val usedAdrenotools: Boolean = false,
    val instanceExtensions: List<String> = emptyList(),
    val deviceExtensions: List<String> = emptyList(),
)

@Serializable
internal data class NativeWorkloadStatus(
    val ok: Boolean = false,
    val stage: String = "",
    val message: String = "",
    @SerialName("imageSha256") val imageSha256: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val workload: NativeWorkloadMetadata? = null,
)

@Serializable
internal data class NativeWorkloadMetadata(
    val workloadId: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frameCount: Int = 0,
    val warmupFrames: Int = 0,
    val drawsPerFrame: Int = 0,
    val trianglesPerDraw: Int = 0,
    val sampleCount: Int = 1,
    val depthFormat: Int = 0,
    val colorFormat: Int = 0,
    val timestampsUsable: Boolean = false,
    val timestampPeriod: Float = 0f,
    val attachmentBytes: Long = 0,
)
