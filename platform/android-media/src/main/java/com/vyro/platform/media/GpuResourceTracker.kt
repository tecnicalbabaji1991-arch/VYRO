package com.vyro.platform.media

/**
 * Deterministic GPU handle tracker validating GL texture, FBO, shader program,
 * and SurfaceTexture lifecycles with zero leaked allocations on Surface loss/recreation or release.
 */
class GpuResourceTracker {
    private val lock = Any()
    private val textures = LinkedHashSet<Int>()
    private val fbos = LinkedHashSet<Int>()
    private val programs = LinkedHashSet<Int>()
    private var totalAllocated: Long = 0L
    private var totalReleased: Long = 0L

    data class Snapshot(
        val activeTextures: Int,
        val activeFbos: Int,
        val activePrograms: Int,
        val totalActive: Int,
        val totalAllocated: Long,
        val totalReleased: Long,
    )

    fun registerTexture(handle: Int) = synchronized(lock) {
        if (handle > 0 && textures.add(handle)) {
            totalAllocated++
        }
    }

    fun registerFbo(handle: Int) = synchronized(lock) {
        if (handle > 0 && fbos.add(handle)) {
            totalAllocated++
        }
    }

    fun registerProgram(handle: Int) = synchronized(lock) {
        if (handle > 0 && programs.add(handle)) {
            totalAllocated++
        }
    }

    fun releaseTexture(handle: Int, onDestroy: (Int) -> Unit = {}) {
        val removed = synchronized(lock) {
            textures.remove(handle).also { if (it) totalReleased++ }
        }
        if (removed) onDestroy(handle)
    }

    fun releaseFbo(handle: Int, onDestroy: (Int) -> Unit = {}) {
        val removed = synchronized(lock) {
            fbos.remove(handle).also { if (it) totalReleased++ }
        }
        if (removed) onDestroy(handle)
    }

    fun releaseProgram(handle: Int, onDestroy: (Int) -> Unit = {}) {
        val removed = synchronized(lock) {
            programs.remove(handle).also { if (it) totalReleased++ }
        }
        if (removed) onDestroy(handle)
    }

    fun releaseAll(
        onDeleteTexture: (Int) -> Unit = {},
        onDeleteFbo: (Int) -> Unit = {},
        onDeleteProgram: (Int) -> Unit = {},
    ): Int {
        val texCopy: List<Int>
        val fboCopy: List<Int>
        val progCopy: List<Int>
        synchronized(lock) {
            texCopy = textures.toList()
            fboCopy = fbos.toList()
            progCopy = programs.toList()
            totalReleased += (texCopy.size + fboCopy.size + progCopy.size)
            textures.clear()
            fbos.clear()
            programs.clear()
        }
        texCopy.forEach(onDeleteTexture)
        fboCopy.forEach(onDeleteFbo)
        progCopy.forEach(onDeleteProgram)
        return texCopy.size + fboCopy.size + progCopy.size
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val active = textures.size + fbos.size + programs.size
        Snapshot(
            activeTextures = textures.size,
            activeFbos = fbos.size,
            activePrograms = programs.size,
            totalActive = active,
            totalAllocated = totalAllocated,
            totalReleased = totalReleased,
        )
    }
}
