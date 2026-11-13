package liquidbounce.DroidBridge.cef

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.gui.render.TextureSetup
import java.nio.ByteBuffer

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

        buffer.clear()
        buffer.limit(frameWidth * frameHeight * 4)
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
