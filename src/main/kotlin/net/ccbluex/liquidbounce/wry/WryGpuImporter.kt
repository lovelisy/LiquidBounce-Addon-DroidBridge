package net.ccbluex.liquidbounce.wry

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.opengl.FrameBufferCache
import com.mojang.renderpearl.backend.opengl.GlTexture
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import org.apache.logging.log4j.LogManager
import org.lwjgl.system.Platform

/**
 * Brings frames that stay on the GPU into textures of the game's OpenGL context, importing each buffer of the native
 * library once: the web views draw into the same few buffers over and over.
 */
internal abstract class WryGpuImporter : AutoCloseable {

    protected class Imported(
        val texture: GpuTexture,
        val view: GpuTextureView,
        val width: Int,
        val height: Int,
        private val onClose: () -> Unit = {}
    ) {
        fun close() {
            view.close()
            texture.close()
            onClose()
        }
    }

    private class ImportedGlTexture(id: Int, width: Int, height: Int) : GlTexture(
        USAGE_TEXTURE_BINDING, "Wry frame", GpuFormat.RGBA8_UNORM, width, height, 1, 1, id, FRAME_BUFFER_CACHE
    )

    protected val logger = LogManager.getLogger("LiquidBounce/Wry")!!
    private val imported = Long2ObjectOpenHashMap<Imported>()
    private val gone = mutableListOf<Imported>()
    private var hasFailed = false

    /**
     * Whether the textures hold BGRA, which the renderer swaps.
     */
    abstract val bgra: Boolean

    /**
     * Linux: the DRM render node the game renders on.
     */
    open val renderNode: String?
        get() = null

    /**
     * Linux: pairs of DRM fourcc and modifier the game can import.
     */
    open val formats: LongArray
        get() = LongArray(0)

    /**
     * The buffer a frame is in, see [WryNative.takeFrame] for the layout of [frame].
     */
    abstract fun id(frame: LongArray): Long

    protected abstract fun import(frame: LongArray, width: Int, height: Int): Imported?

    /**
     * Brings a new frame of an imported buffer into its texture, where the buffer isn't the texture itself.
     */
    protected open fun update(imported: Imported, frame: LongArray) = Unit

    fun texture(frame: LongArray): GpuTextureView? {
        val id = id(frame)
        val width = frame[0].toInt()
        val height = frame[1].toInt()
        val entry = imported[id]?.takeIf { it.width == width && it.height == height } ?: run {
            imported.remove(id)?.let(gone::add)
            val entry = import(frame, width, height) ?: return null
            imported.put(id, entry)
            entry
        }
        update(entry, frame)
        return entry.view
    }

    protected fun wrap(texture: Int, width: Int, height: Int, onClose: () -> Unit = {}): Imported {
        val gpuTexture = ImportedGlTexture(texture, width, height)
        return Imported(gpuTexture, RenderSystem.getDevice().createTextureView(gpuTexture), width, height, onClose)
    }

    protected fun fail(message: String) {
        if (!hasFailed) {
            hasFailed = true
            logger.error("A page's frame could not be imported: $message")
        }
    }

    /**
     * A buffer the native library let go of, its texture goes once no browser shows it anymore.
     */
    fun forget(id: Long) {
        imported.remove(id)?.let(gone::add)
    }

    fun collect(shown: Set<Long>) {
        if (gone.isEmpty()) {
            return
        }
        val shownTextures = shown.mapNotNullTo(HashSet()) { imported[it] }
        gone.removeAll { entry ->
            (entry !in shownTextures).also { if (it) entry.close() }
        }
    }

    override fun close() {
        imported.values.forEach(Imported::close)
        imported.clear()
        gone.forEach(Imported::close)
        gone.clear()
    }

    companion object {

        private val FRAME_BUFFER_CACHE = FrameBufferCache()
        private val logger = LogManager.getLogger("LiquidBounce/Wry")

        /**
         * An importer for this system, or null when frames have to go through memory.
         */
        fun create(): WryGpuImporter? {
            val backend = RenderSystem.getDevice().deviceInfo.backendName()
            if (!backend.contains("OpenGL", ignoreCase = true)) {
                logger.info("The game renders with $backend, pages are copied through memory")
                return null
            }

            return runCatching {
                when (Platform.get()) {
                    Platform.LINUX -> WryDmaBufImporter.create()
                    Platform.WINDOWS -> WryD3D11Importer.create()
                    Platform.MACOSX -> WryIoSurfaceImporter.create()
                    else -> null
                }
            }.onFailure {
                logger.warn("Frames can't stay on the GPU, pages are copied through memory", it)
            }.getOrNull()
        }

    }

}
