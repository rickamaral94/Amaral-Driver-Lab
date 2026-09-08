package com.amaral.driverlab.vk

import com.amaral.driverlab.driver.ConformanceVersion
import com.amaral.driverlab.driver.DriverIdentity
import com.amaral.driverlab.driver.DriverSource
import com.amaral.driverlab.driver.LoadOutcome
import com.amaral.driverlab.driver.LoadRequest
import com.amaral.driverlab.driver.VulkanDriverLoader
import kotlinx.serialization.json.Json
import java.io.Closeable

/** Identifiers for the workloads this module implements. Versioned: see [WorkloadSpec]. */
public object WorkloadIds {
    public const val BASELINE: String = "baseline/v1"
    public const val TILING_GMEM: String = "tiling_gmem/v1"
}

/**
 * Fixed work, stated as counts (P4).
 *
 * Changing any field here changes what the number means, so a run records the
 * whole spec alongside its frametimes and results are only ever compared when the
 * specs match.
 */
public data class WorkloadSpec(
    val workloadId: String,
    val width: Int = 1280,
    val height: Int = 720,
    val frameCount: Int = 300,
    val warmupFrames: Int = 30,
    val drawsPerFrame: Int = 1,
    val trianglesPerDraw: Int = 1024,
    val captureImage: Boolean = true,
) {
    init {
        require(frameCount > 0) { "frameCount must be positive" }
        require(warmupFrames >= 0) { "warmupFrames cannot be negative" }
        require(width > 0 && height > 0) { "the render target must have a positive size" }
    }

    /** Total frames this spec will submit, warmup included. Used to estimate duration. */
    public val totalFrames: Int get() = frameCount + warmupFrames

    public companion object {
        /**
         * Below this much GPU time, an execution cannot separate two drivers and a tie from it
         * means "we did not look long enough", not "they perform the same".
         *
         * The first version of the standard profiles produced about two seconds per execution
         * on an Adreno 740 — the whole Complete profile ran in 3.6 minutes, of which 82% was
         * cooldown. A comparison built on that is measuring fixed submit overhead, not the
         * driver, and it reported a technical tie between Turnip and the Qualcomm blob.
         */
        public const val MINIMUM_USEFUL_GPU_NANOS: Long = 5_000_000_000L

        /**
         * Workload 1: sanity, and the cost of getting a frame into the queue at all.
         *
         * The counts are scaled from a measured Odin2 Portal run at roughly 3.9 ms per frame.
         * They are a starting point, not a calibration: section 6 requires each workload to
         * demonstrate it separates two known-different builds before it belongs in the default
         * profile, and that has not been done yet.
         */
        public fun baseline(): WorkloadSpec = WorkloadSpec(
            workloadId = WorkloadIds.BASELINE,
            width = 1280,
            height = 720,
            frameCount = 1800,
            warmupFrames = 120,
            drawsPerFrame = 192,
        )

        /**
         * Workload 2: binning and GMEM decisions, the classic Turnip regression vector.
         *
         * The counts come from what the device reported, not from an estimate. At 900 frames
         * of 8 draws this produced 1.45 s of GPU time against baseline's 10.9 s, so it kept
         * failing [MINIMUM_USEFUL_GPU_NANOS] while baseline passed — the first scaling pass
         * raised the frame count and left the per-frame cost where it was. Binning work is
         * what this workload exists to measure, so the draws are what grows.
         */
        public fun tilingGmem(): WorkloadSpec = WorkloadSpec(
            workloadId = WorkloadIds.TILING_GMEM,
            width = 1920,
            height = 1080,
            frameCount = 1200,
            warmupFrames = 80,
            drawsPerFrame = 56,
            trianglesPerDraw = 3072,
        )
    }
}

public data class WorkloadRun(
    val spec: WorkloadSpec,
    /** One entry per timed frame. Empty when the queue reported no usable timestamps. */
    val gpuFrametimesNs: LongArray,
    /** Host cost of building and submitting each frame, excluding the wait on the fence. */
    val cpuFrametimesNs: LongArray,
    val imageSha256: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val sampleCount: Int,
    val depthFormat: Int,
    val attachmentBytes: Long,
    val timestampsUsable: Boolean,
) {
    /**
     * The series the statistics run on. GPU timestamps when the device has them,
     * host time otherwise — and [timestampsUsable] records which, because the two
     * measure different things and must never be compared with each other.
     */
    public val primaryFrametimesNs: LongArray
        get() = if (gpuFrametimesNs.isNotEmpty()) gpuFrametimesNs else cpuFrametimesNs

    // Explicit because the class holds arrays; the default would compare identities.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkloadRun) return false
        return spec == other.spec &&
            gpuFrametimesNs.contentEquals(other.gpuFrametimesNs) &&
            cpuFrametimesNs.contentEquals(other.cpuFrametimesNs) &&
            imageSha256 == other.imageSha256
    }

    override fun hashCode(): Int {
        var result = spec.hashCode()
        result = 31 * result + gpuFrametimesNs.contentHashCode()
        result = 31 * result + cpuFrametimesNs.contentHashCode()
        result = 31 * result + imageSha256.hashCode()
        return result
    }
}

public sealed interface WorkloadResult {
    public data class Completed(val run: WorkloadRun) : WorkloadResult
    public data class Failed(val stage: String, val message: String) : WorkloadResult
}

/**
 * A live Vulkan device on one ICD.
 *
 * Created through [VulkanEngine.open] and closed when the phase ends. The runner
 * process is discarded after each phase, so a driver that takes the process down
 * with it costs one phase and is recorded as a crash rather than losing a session.
 */
public class VulkanSession internal constructor(
    private val handle: Long,
    public val identity: DriverIdentity,
) : Closeable {

    private var closed = false

    public fun run(spec: WorkloadSpec): WorkloadResult {
        check(!closed) { "this Vulkan session is closed" }
        val raw = NativeVulkan.nativeRunWorkload(
            handle,
            spec.workloadId,
            spec.width,
            spec.height,
            spec.frameCount,
            spec.warmupFrames,
            spec.drawsPerFrame,
            spec.trianglesPerDraw,
            spec.captureImage,
        ) ?: return WorkloadResult.Failed("JNI", "the native workload returned nothing")

        val json = raw.getOrNull(2) as? String
            ?: return WorkloadResult.Failed("JNI", "the native workload returned no status")
        val status = VulkanEngine.json.decodeFromString(NativeWorkloadStatus.serializer(), json)
        if (!status.ok) {
            return WorkloadResult.Failed(status.stage.ifBlank { "UNKNOWN" }, status.message)
        }

        val metadata = status.workload
            ?: return WorkloadResult.Failed("JNI", "the native workload reported no metadata")

        return WorkloadResult.Completed(
            WorkloadRun(
                spec = spec,
                gpuFrametimesNs = raw.getOrNull(0) as? LongArray ?: LongArray(0),
                cpuFrametimesNs = raw.getOrNull(1) as? LongArray ?: LongArray(0),
                imageSha256 = status.imageSha256,
                imageWidth = status.imageWidth,
                imageHeight = status.imageHeight,
                sampleCount = metadata.sampleCount,
                depthFormat = metadata.depthFormat,
                attachmentBytes = metadata.attachmentBytes,
                timestampsUsable = metadata.timestampsUsable,
            ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        NativeVulkan.nativeClose(handle)
    }
}

/**
 * Opens ICDs and reports what they say about themselves.
 *
 * Implements [VulkanDriverLoader] so [com.amaral.driverlab.driver.IdentityGuard] can
 * judge a driver's identity without depending on the graphics stack.
 */
public object VulkanEngine : VulkanDriverLoader {

    internal val json: Json = Json { ignoreUnknownKeys = true }

    override fun probe(request: LoadRequest): LoadOutcome {
        val opened = open(request)
        return when (opened) {
            is OpenResult.Opened -> opened.session.use { LoadOutcome.Success(it.identity) }
            is OpenResult.Failed -> LoadOutcome.Failure(opened.stage, opened.message)
        }
    }

    public sealed interface OpenResult {
        public data class Opened(val session: VulkanSession) : OpenResult
        public data class Failed(val stage: LoadOutcome.Stage, val message: String) : OpenResult
    }

    public fun open(request: LoadRequest): OpenResult {
        if (!NativeVulkan.available) {
            return OpenResult.Failed(
                LoadOutcome.Stage.HOOK_UNAVAILABLE,
                "libamaral_vk could not be loaded: ${NativeVulkan.loadFailure() ?: "unknown reason"}",
            )
        }

        val status = arrayOfNulls<String>(1)
        val handle = NativeVulkan.nativeOpen(
            request.source == DriverSource.SYSTEM,
            request.libraryDirectory?.absolutePath,
            request.libraryName,
            request.nativeLibraryDirectory.absolutePath,
            request.enableValidationLayer,
            status,
        )

        val parsed = status[0]?.let { json.decodeFromString(NativeStatus.serializer(), it) }
            ?: return OpenResult.Failed(
                LoadOutcome.Stage.IDENTITY_UNAVAILABLE,
                "the native loader returned no status",
            )

        if (handle == 0L || !parsed.ok || parsed.identity == null) {
            if (handle != 0L) NativeVulkan.nativeClose(handle)
            return OpenResult.Failed(stageOf(parsed.stage), parsed.message.ifBlank { "the driver did not load" })
        }

        // The checksum travels with the request so the identity carries the bytes that ran.
        val checksum = if (request.source == DriverSource.SYSTEM) "" else request.libraryChecksum.orEmpty()
        return OpenResult.Opened(VulkanSession(handle, parsed.identity.toDomain(request.source, checksum)))
    }

    private fun stageOf(stage: String): LoadOutcome.Stage = when (stage) {
        "HOOK_UNAVAILABLE" -> LoadOutcome.Stage.HOOK_UNAVAILABLE
        "DLOPEN_FAILED" -> LoadOutcome.Stage.DLOPEN_FAILED
        "INSTANCE_CREATION_FAILED" -> LoadOutcome.Stage.INSTANCE_CREATION_FAILED
        "NO_PHYSICAL_DEVICE" -> LoadOutcome.Stage.NO_PHYSICAL_DEVICE
        "DEVICE_CREATION_FAILED" -> LoadOutcome.Stage.DEVICE_CREATION_FAILED
        else -> LoadOutcome.Stage.IDENTITY_UNAVAILABLE
    }
}

internal fun NativeIdentity.toDomain(source: DriverSource, libraryChecksum: String): DriverIdentity =
    DriverIdentity(
        libraryChecksum = libraryChecksum,
        driverId = driverId,
        driverName = driverName,
        driverInfo = driverInfo,
        conformanceVersion = if (hasConformanceVersion) {
            ConformanceVersion(conformanceMajor, conformanceMinor, conformanceSubminor, conformancePatch)
        } else {
            null
        },
        deviceName = deviceName,
        vendorId = vendorId.toInt(),
        deviceId = deviceId.toInt(),
        apiVersion = apiVersion.toInt(),
        driverVersion = driverVersion.toInt(),
        instanceExtensions = instanceExtensions,
        deviceExtensions = deviceExtensions,
        source = source,
    )
