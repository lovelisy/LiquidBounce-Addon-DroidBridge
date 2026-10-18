package net.ccbluex.liquidbounce.wry

import com.mojang.renderpearl.backend.opengl.GlStateManager
import com.mojang.renderpearl.backend.opengl.GlTexture
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import java.nio.ByteBuffer

/**
 * Binds the IOSurfaces CARenderer draws the pages into on macOS to rectangle textures of the game's OpenGL context,
 * and copies every new frame on the GPU into a texture the game's renderer samples, turned the right way up.
 */
internal class WryIoSurfaceImporter : WryGpuImporter() {

    override val bgra = false

    override fun id(frame: LongArray) = frame[4]

    private val rectangles = HashMap<Long, Int>()

    override fun import(frame: LongArray, width: Int, height: Int): Imported? {
        val surface = id(frame)
        val previous = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_RECTANGLE)
        val rectangle = GL11.glGenTextures()
        GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, rectangle)
        val error = WryNative.bindIoSurface(surface, width, height)
        GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, previous)
        if (error != 0) {
            GL11.glDeleteTextures(rectangle)
            fail("CGLTexImageIOSurface2D failed with $error")
            return null
        }

        val texture = GL11.glGenTextures()
        GlStateManager._bindTexture(texture)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
            null as ByteBuffer?
        )
        GlStateManager._bindTexture(0)
        rectangles[surface] = rectangle
        return wrap(texture, width, height) {
            rectangles.remove(surface, rectangle)
            GL11.glDeleteTextures(rectangle)
        }
    }

    override fun update(imported: Imported, frame: LongArray) {
        val rectangle = rectangles[id(frame)] ?: return
        val texture = (imported.texture as GlTexture).glId()
        val width = imported.width
        val height = imported.height

        val previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val read = GL30.glGenFramebuffers()
        val draw = GL30.glGenFramebuffers()
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read)
            GL30.glFramebufferTexture2D(
                GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL31.GL_TEXTURE_RECTANGLE, rectangle, 0
            )
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw)
            GL30.glFramebufferTexture2D(
                GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0
            )
            // CARenderer draws upside down
            GL30.glBlitFramebuffer(
                0, 0, width, height, 0, height, width, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
            )
            val error = GL11.glGetError()
            if (error != GL11.GL_NO_ERROR) {
                fail("copying an IOSurface failed with 0x${Integer.toHexString(error)}")
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw)
            GL30.glDeleteFramebuffers(read)
            GL30.glDeleteFramebuffers(draw)
        }
    }

    companion object {

        fun create() = WryIoSurfaceImporter()

    }

}
