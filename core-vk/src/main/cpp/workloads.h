#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "vk_context.h"

namespace amaral {

/**
 * Fixed work, never fixed time (P4).
 *
 * Every field here is a count. Running "for thirty seconds" turns throughput into
 * a measurement of how hot the device happened to be, because a faster driver is
 * rewarded with more work and the same wall clock.
 */
struct WorkloadConfig {
    uint32_t width = 1280;
    uint32_t height = 720;
    /** Frames that are timed. */
    uint32_t frameCount = 300;
    /** Frames run and discarded first, to get past shader compilation and clocks ramping. */
    uint32_t warmupFrames = 30;
    uint32_t drawsPerFrame = 1;
    /** Triangles per draw. Only workload 2 uses this. */
    uint32_t trianglesPerDraw = 1024;
    bool captureImage = true;
};

struct WorkloadOutcome {
    bool ok = false;
    std::string error;
    std::string errorStage;

    /** One entry per timed frame. Empty when the queue has no usable timestamps. */
    std::vector<int64_t> gpuFrametimesNs;
    /** Host-side cost of building and submitting each frame, excluding the wait. */
    std::vector<int64_t> cpuFrametimesNs;

    std::string imageSha256;
    uint32_t imageWidth = 0;
    uint32_t imageHeight = 0;
    std::string metadataJson;
};

/** Workload identifiers, versioned. A change in what is drawn needs a new version. */
extern const char* const kWorkloadBaseline;
extern const char* const kWorkloadTilingGmem;

WorkloadOutcome runWorkload(VulkanContext& context,
                            const std::string& workloadId,
                            const WorkloadConfig& config);

}  // namespace amaral
