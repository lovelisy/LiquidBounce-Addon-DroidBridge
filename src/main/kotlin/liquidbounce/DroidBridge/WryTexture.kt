package liquidbounce.DroidBridge

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.integration.backend.BrowserTexture
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.render.asTextureSetup
import net.ccbluex.liquidbounce.utils.render.asView
import net.minecraft.client.gui.render.TextureSetup
import java.nio.ByteBuffer
import java.util.function.Supplier

/**
 * The last frame of a page that the system draws below the game, kept on a texture of the game.
 *
 * The page is [HEADER] bytes of measurements followed by one RGBA texel per pixel, which is what
 * [WryNative.snapshot] answers. The texture is handed to the renderer of the game, so the game draws
 * it in the hud pass and every screen drawn afterwards covers it, exactly like its own hud. [upload]
 * follows the same staging path the client uses for [net.ccbluex.liquidbounce.utils.render.write].
 */
internal class WryTexture(private val id: Long) {

    private var texture: GpuTexture? = null

    private var view: GpuTextureView? = null

    private var setup: TextureSetup? = null

    private var direct: ByteBuffer? = null

    private var width = 0

    private var height = 0

    private var stamp = -1L

    private var took = 0

    private var painted = 0

    private var reports = 0

    /**
     * What the game draws, or null while no frame has been read.
     */
    var browserTexture: BrowserTexture? = null
        private set

    fun poll() {
        val frame = WryNative.snapshot(id) ?: return
        if (frame.size <= HEADER) {
            return
        }
        val sourceWidth = readShort(frame, 0)
        val sourceHeight = readShort(frame, 2)
        if (sourceWidth <= 0 || sourceHeight <= 0 || frame.size != sourceWidth * sourceHeight * PIXELS + HEADER) {
            return
        }
        val tookMs = readInt(frame, 4)
        val paintedPixels = readInt(frame, 8)
        val counter = readInt(frame, 12)
        if (stamp == counter.toLong() && sourceWidth == width && sourceHeight == height) {
            return
        }
        stamp = counter.toLong()
        took = tookMs
        painted = paintedPixels
        val pixels = frame.copyOfRange(HEADER, frame.size)
        upload(pixels, sourceWidth, sourceHeight)
        report()
    }

    private fun upload(pixels: ByteArray, sourceWidth: Int, sourceHeight: Int) {
        if (texture == null || width != sourceWidth || height != sourceHeight) {
            drop()
            val created = gpuDevice.createTexture(
                Supplier { "DroidBridge page $id ($sourceWidth x $sourceHeight)" },
                GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                sourceWidth,
                sourceHeight,
                1,
                1,
            )
            texture = created
            view = created.asView()
            setup = null
            width = sourceWidth
            height = sourceHeight
        }
        val target = texture ?: return
        val buffer = (direct?.takeIf { it.capacity() >= pixels.size }
            ?: ByteBuffer.allocateDirect(pixels.size).also { direct = it })
            .apply {
                position(0)
                put(pixels)
                position(0)
            }
        val encoder = gpuDevice.createCommandEncoder()
        val slice = encoder.transientMemory().uploadStaging(buffer, 1L, GpuBuffer.USAGE_COPY_SRC)
        encoder.copyBufferToTexture(
            slice, 0, 0, sourceWidth, sourceHeight,
            target, 0, 0, sourceWidth, sourceHeight,
            0, 0,
        )
        val ready = setup ?: (view ?: return).asTextureSetup(sampler).also { setup = it }
        browserTexture = BrowserTexture(ready, sourceWidth, sourceHeight, false)
    }

    fun close() {
        drop()
        browserTexture = null
        stamp = -1L
    }

    private fun report() {
        if (width <= 0 || height <= 0 || reports++ % 30 != 0) {
            return
        }
        logger.info(
            "The page draws $width x $height in $took ms, $painted pixels painted, " +
                "texture ${if (browserTexture == null) "not ready" else "ready"}"
        )
    }

    private fun drop() {
        view?.close()
        view = null
        texture?.close()
        texture = null
        setup = null
        width = 0
        height = 0
    }

    companion object {

        private const val HEADER = 16

        private const val PIXELS = 4

        private val logger = org.apache.logging.log4j.LogManager.getLogger("LiquidBounce/DroidBridge")

        private val sampler: GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

        private fun readShort(bytes: ByteArray, offset: Int) =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun readInt(bytes: ByteArray, offset: Int) =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

    }

}
