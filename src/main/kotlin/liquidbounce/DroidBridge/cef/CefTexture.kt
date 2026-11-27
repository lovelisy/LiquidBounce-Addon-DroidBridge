package liquidbounce.DroidBridge.cef

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.gui.render.TextureSetup
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CefTexture {

    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    private var sampler: GpuSampler? = null

    var setup: TextureSetup? = null
        private set

    var width = 0
        private set

    var height = 0
        private set

    var painted = false
        private set

    fun upload(buffer: ByteBuffer, frameWidth: Int, frameHeight: Int) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return
        }

        RenderSystem.assertOnRenderThread()
        val target = ensure(frameWidth, frameHeight)

        val pixels = frameWidth * frameHeight
        swapRedBlue(buffer, pixels)

        buffer.clear()
        buffer.limit(pixels * 4)
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
            target,
            buffer,
            0,
            0,
            0,
            0,
            frameWidth,
            frameHeight
        )
        painted = true
    }

    /**
     * In-place BGRA -> RGBA conversion, matching MCEF Engine Plugin's MCEFRenderer.
     *
     * libmcef hands us CEF frames in BGRA8888, while the texture below is RGBA8_UNORM.
     * Reading little-endian turns one pixel into 0xAARRGGBB; swapping byte 0 and byte 2
     * yields 0xAABBGGRR, i.e. RGBA in memory. Working on Ints keeps this to ~1 pass per
     * frame instead of four byte operations per pixel.
     */
    private fun swapRedBlue(buffer: ByteBuffer, pixelCount: Int) {
        val ints = buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val count = minOf(pixelCount, ints.limit())
        for (index in 0 until count) {
            val value = ints.get(index)
            ints.put(
                index,
                (value and 0xFF00FF00.toInt()) or
                    ((value and 0xFF) shl 16) or
                    ((value ushr 16) and 0xFF)
            )
        }
    }

    private fun ensure(frameWidth: Int, frameHeight: Int): GpuTexture {
        val current = texture
        if (current != null && width == frameWidth && height == frameHeight) {
            return current
        }

        close()

        val created = RenderSystem.getDevice().createTexture(
            "DroidBridge CEF $frameWidth x $frameHeight",
            GpuTexture.USAGE_TEXTURE_BINDING or
                GpuTexture.USAGE_RENDER_ATTACHMENT or
                GpuTexture.USAGE_COPY_SRC or
                GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM,
            frameWidth,
            frameHeight,
            1,
            1
        )
        texture = created
        view = RenderSystem.getDevice().createTextureView(created)
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false)
        setup = TextureSetup.singleTexture(view!!, sampler!!)
        width = frameWidth
        height = frameHeight
        painted = false
        return created
    }

    fun close() {
        view?.close()
        view = null
        texture?.close()
        texture = null
        setup = null
        sampler = null
        width = 0
        height = 0
        painted = false
    }

}
