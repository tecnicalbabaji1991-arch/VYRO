package com.vyro.bridge.jni

import android.view.Surface
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger

/**
 * Low-level JNI boundary matching Blueprint Section 6.2.
 * Fails safely if libvyro_engine.so is absent (e.g. builds without NDK/CMake provisioned).
 */
class NativeEngine private constructor(
    private var nativeHandle: Long,
) {
    val handle: Long get() = nativeHandle

    fun isValid(): Boolean = nativeHandle != 0L && isLibraryLoaded

    fun release() {
        val h = nativeHandle
        if (h != 0L && isLibraryLoaded) {
            nativeRelease(h)
        }
        nativeHandle = 0L
    }

    fun createProject(configJson: String): Long =
        if (isValid()) nativeCreateProject(nativeHandle, configJson) else -1L

    fun openProject(projectId: Long): Int =
        if (isValid()) nativeOpenProject(nativeHandle, projectId) else 1001

    fun closeProject(projectId: Long): Int =
        if (isValid()) nativeCloseProject(nativeHandle, projectId) else 1001

    fun submitCommand(projectId: Long, commandJson: String): Int =
        if (isValid()) nativeSubmitCommand(nativeHandle, projectId, commandJson) else 1001

    fun seek(projectId: Long, positionUs: Long): Int =
        if (isValid()) nativeSeek(nativeHandle, projectId, positionUs) else 1001

    fun play(projectId: Long): Int =
        if (isValid()) nativePlay(nativeHandle, projectId) else 1001

    fun pause(projectId: Long): Int =
        if (isValid()) nativePause(nativeHandle, projectId) else 1001

    fun attachPreviewSurface(projectId: Long, surface: Surface): Int =
        if (isValid()) nativeAttachPreviewSurface(nativeHandle, projectId, surface) else 1001

    fun detachPreviewSurface(projectId: Long): Int =
        if (isValid()) nativeDetachPreviewSurface(nativeHandle, projectId) else 1001

    fun getTelemetryJson(): String =
        if (isValid()) nativeGetTelemetryJson(nativeHandle) else "{}"

    companion object {
        val isLibraryLoaded: Boolean = try {
            System.loadLibrary("vyro_engine")
            VyroLogger.log(LogSeverity.INFO, "bridge:jni", "Loaded native libvyro_engine.so")
            true
        } catch (err: UnsatisfiedLinkError) {
            VyroLogger.log(
                LogSeverity.WARN,
                "bridge:jni",
                "libvyro_engine.so unavailable (${err.message}); using JVM contract parity backend",
            )
            false
        }

        fun createOrNull(): NativeEngine? {
            if (!isLibraryLoaded) return null
            val handle = nativeCreate()
            return if (handle != 0L) NativeEngine(handle) else null
        }

        fun activeSessionCount(): Int =
            if (isLibraryLoaded) nativeActiveSessionCount() else 0

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeRelease(handle: Long)
        @JvmStatic external fun nativeCreateProject(handle: Long, configJson: String): Long
        @JvmStatic external fun nativeOpenProject(handle: Long, projectId: Long): Int
        @JvmStatic external fun nativeCloseProject(handle: Long, projectId: Long): Int
        @JvmStatic external fun nativeSubmitCommand(handle: Long, projectId: Long, commandJson: String): Int
        @JvmStatic external fun nativeSeek(handle: Long, projectId: Long, positionUs: Long): Int
        @JvmStatic external fun nativePlay(handle: Long, projectId: Long): Int
        @JvmStatic external fun nativePause(handle: Long, projectId: Long): Int
        @JvmStatic external fun nativeAttachPreviewSurface(handle: Long, projectId: Long, surface: Surface): Int
        @JvmStatic external fun nativeDetachPreviewSurface(handle: Long, projectId: Long): Int
        @JvmStatic external fun nativeActiveSessionCount(): Int
        @JvmStatic external fun nativeGetTelemetryJson(handle: Long): String
    }
}
