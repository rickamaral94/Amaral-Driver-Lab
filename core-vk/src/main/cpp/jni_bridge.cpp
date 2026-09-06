#include <jni.h>

#include <memory>
#include <sstream>
#include <string>

#include "icd_loader.h"
#include "offscreen_target.h"
#include "vk_context.h"
#include "workloads.h"

// The bridge stays thin on purpose. Timing happens entirely on this side of it —
// no frametime ever passes through a JNI call or touches the garbage collector —
// and what crosses back is a long[] of nanoseconds plus a small JSON blob whose
// schema the Kotlin side owns.

namespace {

using amaral::VulkanContext;

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c;
        }
    }
    return out;
}

std::string failureJson(const char* stage, const std::string& message) {
    std::ostringstream out;
    out << "{\"ok\":false,\"stage\":\"" << stage << "\",\"message\":\"" << jsonEscape(message)
        << "\"}";
    return out.str();
}

jlongArray toLongArray(JNIEnv* env, const std::vector<int64_t>& values) {
    jlongArray array = env->NewLongArray(static_cast<jsize>(values.size()));
    if (array == nullptr) return nullptr;
    if (!values.empty()) {
        env->SetLongArrayRegion(array, 0, static_cast<jsize>(values.size()),
                                reinterpret_cast<const jlong*>(values.data()));
    }
    return array;
}

}  // namespace

extern "C" {

/**
 * Opens an ICD and brings up instance and device. Returns a native handle, or 0
 * with the reason in the out-parameter array.
 *
 * @param outStatus a String[1] that receives a JSON status blob either way.
 */
JNIEXPORT jlong JNICALL
Java_com_amaral_driverlab_vk_NativeVulkan_nativeOpen(JNIEnv* env,
                                                     jclass,
                                                     jboolean systemDriver,
                                                     jstring libraryDirectory,
                                                     jstring libraryName,
                                                     jstring temporaryDirectory,
                                                     jboolean enableValidation,
                                                     jobjectArray outStatus) {
    const std::string directory = toStdString(env, libraryDirectory);
    const std::string name = toStdString(env, libraryName);
    const std::string temporary = toStdString(env, temporaryDirectory);

    amaral::IcdLoadResult load = systemDriver == JNI_TRUE
            ? amaral::openSystemLoader()
            : amaral::openPackagedDriver(directory, name, temporary);

    if (!load.ok) {
        env->SetObjectArrayElement(
                outStatus, 0,
                toJavaString(env, failureJson(amaral::describe(load.stage), load.message)));
        return 0;
    }

    auto context = std::make_unique<VulkanContext>();
    amaral::ContextResult result =
            amaral::createContext(*context, load.handle, enableValidation == JNI_TRUE);
    if (!result.ok) {
        std::string message = result.message;
        if (result.vulkanResult != VK_SUCCESS) {
            message += std::string(" (") + amaral::describeVkResult(result.vulkanResult) + ")";
        }
        env->SetObjectArrayElement(
                outStatus, 0, toJavaString(env, failureJson(amaral::describe(result.stage), message)));
        return 0;
    }

    std::ostringstream success;
    success << "{\"ok\":true,\"identity\":" << amaral::identityToJson(*context) << '}';
    env->SetObjectArrayElement(outStatus, 0, toJavaString(env, success.str()));
    return reinterpret_cast<jlong>(context.release());
}

JNIEXPORT void JNICALL
Java_com_amaral_driverlab_vk_NativeVulkan_nativeClose(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<VulkanContext*>(handle);
}

/**
 * Runs one workload.
 *
 * @return Object[3]: long[] of GPU frametimes in ns, long[] of host frametimes in
 *         ns, and a JSON string with the image hash, dimensions and any error.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_amaral_driverlab_vk_NativeVulkan_nativeRunWorkload(JNIEnv* env,
                                                            jclass,
                                                            jlong handle,
                                                            jstring workloadId,
                                                            jint width,
                                                            jint height,
                                                            jint frameCount,
                                                            jint warmupFrames,
                                                            jint drawsPerFrame,
                                                            jint trianglesPerDraw,
                                                            jboolean captureImage) {
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    if (result == nullptr) return nullptr;

    if (handle == 0) {
        env->SetObjectArrayElement(result, 2,
                                   toJavaString(env, failureJson("NO_CONTEXT",
                                                                 "no Vulkan context is open")));
        return result;
    }

    VulkanContext* context = reinterpret_cast<VulkanContext*>(handle);

    amaral::WorkloadConfig config;
    config.width = static_cast<uint32_t>(width);
    config.height = static_cast<uint32_t>(height);
    config.frameCount = static_cast<uint32_t>(frameCount);
    config.warmupFrames = static_cast<uint32_t>(warmupFrames);
    config.drawsPerFrame = static_cast<uint32_t>(drawsPerFrame);
    config.trianglesPerDraw = static_cast<uint32_t>(trianglesPerDraw);
    config.captureImage = captureImage == JNI_TRUE;

    amaral::WorkloadOutcome outcome =
            amaral::runWorkload(*context, toStdString(env, workloadId), config);

    if (!outcome.ok) {
        env->SetObjectArrayElement(
                result, 2, toJavaString(env, failureJson(outcome.errorStage.c_str(), outcome.error)));
        return result;
    }

    env->SetObjectArrayElement(result, 0, toLongArray(env, outcome.gpuFrametimesNs));
    env->SetObjectArrayElement(result, 1, toLongArray(env, outcome.cpuFrametimesNs));

    std::ostringstream json;
    json << "{\"ok\":true,"
         << "\"imageSha256\":\"" << outcome.imageSha256 << "\","
         << "\"imageWidth\":" << outcome.imageWidth << ','
         << "\"imageHeight\":" << outcome.imageHeight << ','
         << "\"workload\":" << outcome.metadataJson << '}';
    env->SetObjectArrayElement(result, 2, toJavaString(env, json.str()));
    return result;
}

}  // extern "C"
