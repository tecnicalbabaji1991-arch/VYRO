#include <jni.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

#include "vyro/timeline/timeline_engine.hpp"

namespace {

std::mutex gSessionMutex;
std::unordered_map<jlong, std::unique_ptr<vyro::timeline::TimelineEngine>> gSessions;
std::atomic<jlong> gNextHandle{1000};

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

template <typename Fn>
jint withSession(jlong handle, Fn&& fn) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    auto it = gSessions.find(handle);
    if (it == gSessions.end() || !it->second) {
        return static_cast<jint>(vyro::core::EngineErrorCode::INVALID_STATE);
    }
    return fn(*it->second);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeCreate(JNIEnv* /*env*/, jclass /*clazz*/) {
    try {
        const jlong handle = gNextHandle.fetch_add(1);
        auto engine = std::make_unique<vyro::timeline::TimelineEngine>(handle);
        std::lock_guard<std::mutex> lock(gSessionMutex);
        gSessions.emplace(handle, std::move(engine));
        return handle;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeRelease(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    gSessions.erase(handle);
}

JNIEXPORT jlong JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeCreateProject(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring configJson
) {
    const std::string cfg = toStdString(env, configJson);
    std::lock_guard<std::mutex> lock(gSessionMutex);
    auto it = gSessions.find(handle);
    if (it == gSessions.end() || !it->second) return -1;
    return it->second->createProject(cfg);
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeOpenProject(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong projectId
) {
    return withSession(handle, [projectId](auto& engine) {
        return engine.openProject(projectId).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeCloseProject(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong projectId
) {
    return withSession(handle, [projectId](auto& engine) {
        return engine.closeProject(projectId).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeSubmitCommand(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jlong projectId, jstring commandJson
) {
    const std::string cmd = toStdString(env, commandJson);
    return withSession(handle, [projectId, &cmd](auto& engine) {
        return engine.submitCommand(projectId, cmd).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeSeek(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong projectId, jlong positionUs
) {
    return withSession(handle, [projectId, positionUs](auto& engine) {
        return engine.seek(projectId, positionUs).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativePlay(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong projectId
) {
    return withSession(handle, [projectId](auto& engine) {
        return engine.play(projectId).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativePause(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong projectId
) {
    return withSession(handle, [projectId](auto& engine) {
        return engine.pause(projectId).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeAttachPreviewSurface(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jlong projectId, jobject surface
) {
    if (surface == nullptr) {
        return static_cast<jint>(vyro::core::EngineErrorCode::INVALID_STATE);
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    const bool validWindow = (window != nullptr);
    if (window != nullptr) {
        ANativeWindow_release(window);
    }
    return withSession(handle, [projectId, validWindow](auto& engine) {
        return engine.attachPreviewSurface(projectId, validWindow).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeDetachPreviewSurface(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong projectId
) {
    return withSession(handle, [projectId](auto& engine) {
        return engine.detachPreviewSurface(projectId).rawCode();
    });
}

JNIEXPORT jint JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeActiveSessionCount(JNIEnv* /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    return static_cast<jint>(gSessions.size());
}

JNIEXPORT jstring JNICALL
Java_com_vyro_bridge_jni_NativeEngine_nativeGetTelemetryJson(
    JNIEnv* env, jclass /*clazz*/, jlong handle
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    auto it = gSessions.find(handle);
    if (it == gSessions.end() || !it->second) {
        return env->NewStringUTF("{\"error\":\"INVALID_STATE\"}");
    }
    const auto t = it->second->telemetry();
    std::ostringstream out;
    out << "{\"engineHandle\":" << t.engineHandle
        << ",\"activeProjectId\":" << t.activeProjectId
        << ",\"playheadUs\":" << t.playheadUs
        << ",\"isPlaying\":" << (t.isPlaying ? "true" : "false")
        << ",\"surfaceAttached\":" << (t.surfaceAttached ? "true" : "false")
        << ",\"commandCount\":" << t.commandCount << "}";
    return env->NewStringUTF(out.str().c_str());
}

}  // extern "C"
