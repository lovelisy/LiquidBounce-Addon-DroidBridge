package net.ccbluex.liquidbounce.wry

import com.mojang.renderpearl.backend.opengl.GlStateManager
import org.apache.logging.log4j.LogManager
import org.lwjgl.opengl.EXTMemoryObject
import org.lwjgl.opengl.EXTMemoryObjectWin32
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11

/**
 * Opens the shared D3D11 textures WebView2's frames are copied into on Windows as textures of the game, by their NT
 * handles, as the client already does for Chromium's shared textures.
 */
internal class WryD3D11Importer private constructor() : WryGpuImporter() {

    // The D3D11 textures are BGRA
    override val bgra = true

    override fun id(frame: LongArray) = frame[4]

    override fun import(frame: LongArray, width: Int, height: Int): Imported? {
        val memory = EXTMemoryObject.glCreateMemoryObjectsEXT()
        if (memory == 0) {
            fail("glCreateMemoryObjectsEXT failed")
            return null
        }
        EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(
            memory, 0L, EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT, id(frame)
        )
        val texture = GL11.glGenTextures()
        GlStateManager._bindTexture(texture)
        EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, width, height, memory, 0L)
        EXTMemoryObject.glDeleteMemoryObjectsEXT(memory)
        val error = GL11.glGetError()
        GlStateManager._bindTexture(0)
        if (error != GL11.GL_NO_ERROR) {
            GL11.glDeleteTextures(texture)
            fail("importing a shared texture failed with 0x${Integer.toHexString(error)}")
            return null
        }
        return wrap(texture, width, height)
    }

    companion object {

        private val logger = LogManager.getLogger("LiquidBounce/Wry")

        fun create(): WryD3D11Importer? {
            val capabilities = GL.getCapabilities()
            if (!capabilities.GL_EXT_memory_object || !capabilities.GL_EXT_memory_object_win32) {
                logger.info("The game's OpenGL can't open D3D11 textures, pages are copied through memory")
                return null
            }
            return WryD3D11Importer()
        }

    }

}
