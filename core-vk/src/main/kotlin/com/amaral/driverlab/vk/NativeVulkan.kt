package com.amaral.driverlab.vk

/**
 * The JNI surface, and nothing else.
 *
 * Frametimes never cross this boundary one at a time: a workload returns whole
 * `LongArray`s of nanoseconds that were measured entirely in C++, so no per-frame
 * measurement is ever taken on a thread the garbage collector can pause.
 */
internal object NativeVulkan {

    @Volatile
    private var loadError: Throwable? = null

    val available: Boolean by lazy {
        try {
            System.loadLibrary("amaral_vk")
            true
        } catch (e: Throwable) {
            loadError = e
            false
        }
    }

    fun loadFailure(): String? = loadError?.message

    /**
     * Opens an ICD and brings up instance and device.
     *
     * @param outStatus a one-element array that receives a JSON status blob whether
     *   the call succeeds or fails.
     * @return an opaque native handle, or 0 on failure.
     */
    @JvmStatic
    external fun nativeOpen(
        systemDriver: Boolean,
        libraryDirectory: String?,
        libraryName: String?,
        temporaryDirectory: String,
        enableValidation: Boolean,
        outStatus: Array<String?>,
    ): Long

    @JvmStatic
    external fun nativeClose(handle: Long)

    /** @return `[LongArray gpuNs, LongArray cpuNs, String json]`. */
    @JvmStatic
    external fun nativeRunWorkload(
        handle: Long,
        workloadId: String,
        width: Int,
        height: Int,
        frameCount: Int,
        warmupFrames: Int,
        drawsPerFrame: Int,
        trianglesPerDraw: Int,
        captureImage: Boolean,
    ): Array<Any?>?
}
