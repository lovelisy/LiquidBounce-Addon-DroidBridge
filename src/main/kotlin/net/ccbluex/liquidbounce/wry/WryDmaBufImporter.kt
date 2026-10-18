package net.ccbluex.liquidbounce.wry

import com.mojang.renderpearl.backend.opengl.GlStateManager
import org.apache.logging.log4j.LogManager
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL14
import org.lwjgl.egl.EGLCapabilities
import org.lwjgl.egl.EXTDeviceDRMRenderNode
import org.lwjgl.egl.EXTDeviceQuery
import org.lwjgl.egl.EXTImageDMABufImport
import org.lwjgl.egl.EXTImageDMABufImportModifiers
import org.lwjgl.egl.KHRImageBase
import org.lwjgl.opengl.EXTEGLImageStorage
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer

/**
 * Brings the dmabufs the web views on Linux draw into straight into textures of the game, through EGL.
 *
 * Only possible while the game renders with OpenGL on an EGL context of a GPU with a DRM render node.
 */
internal class WryDmaBufImporter private constructor(
    private val display: Long,
    private val capabilities: EGLCapabilities,
    override val renderNode: String,
    override val formats: LongArray
) : WryGpuImporter() {

    // The EGL import already hands over the channels in order
    override val bgra = false

    override fun id(frame: LongArray) = frame[6]

    override fun import(frame: LongArray, width: Int, height: Int): Imported? {
        val planes = frame[9].toInt()
        val texture = createTexture(
            fourcc = frame[7].toInt(),
            modifier = frame[8],
            width = width,
            height = height,
            fds = IntArray(planes) { frame[10 + it * 3].toInt() },
            offsets = IntArray(planes) { frame[11 + it * 3].toInt() },
            strides = IntArray(planes) { frame[12 + it * 3].toInt() }
        ) ?: return null
        return wrap(texture, width, height)
    }

    @Suppress("LongParameterList")
    private fun createTexture(
        fourcc: Int,
        modifier: Long,
        width: Int,
        height: Int,
        fds: IntArray,
        offsets: IntArray,
        strides: IntArray
    ): Int? = MemoryStack.stackPush().use { stack ->
        val withModifier = capabilities.EGL_EXT_image_dma_buf_import_modifiers && modifier != DRM_FORMAT_MOD_INVALID
        val attributes = stack.mallocInt(7 + fds.size * 10)
        attributes.put(EGL14.EGL_WIDTH).put(width)
            .put(EGL14.EGL_HEIGHT).put(height)
            .put(EXTImageDMABufImport.EGL_LINUX_DRM_FOURCC_EXT).put(fourcc)
        for (plane in fds.indices) {
            attributes.put(PLANE_FD[plane]).put(fds[plane])
                .put(PLANE_OFFSET[plane]).put(offsets[plane])
                .put(PLANE_PITCH[plane]).put(strides[plane])
            if (withModifier) {
                attributes.put(PLANE_MODIFIER_LO[plane]).put(modifier.toInt())
                    .put(PLANE_MODIFIER_HI[plane]).put((modifier ushr 32).toInt())
            }
        }
        attributes.put(EGL14.EGL_NONE).flip()

        // Called directly, the binding insists on a client buffer, which dmabufs don't have
        val image = JNI.callPPPPP(
            display, EGL14.EGL_NO_CONTEXT, EXTImageDMABufImport.EGL_LINUX_DMA_BUF_EXT, 0L,
            MemoryUtil.memAddress(attributes), capabilities.eglCreateImageKHR
        )
        if (image == 0L) {
            fail("eglCreateImageKHR failed with 0x${Integer.toHexString(EGL14.eglGetError())} " +
                "(fourcc 0x${Integer.toHexString(fourcc)}, modifier 0x${java.lang.Long.toHexString(modifier)})")
            return null
        }

        val texture = GL11.glGenTextures()
        GlStateManager._bindTexture(texture)
        EXTEGLImageStorage.glEGLImageTargetTexStorageEXT(GL11.GL_TEXTURE_2D, image, null as IntBuffer?)
        KHRImageBase.eglDestroyImageKHR(display, image)
        val error = GL11.glGetError()
        GlStateManager._bindTexture(0)
        if (error != GL11.GL_NO_ERROR) {
            GL11.glDeleteTextures(texture)
            fail("glEGLImageTargetTexStorageEXT failed with 0x${Integer.toHexString(error)}")
            return null
        }
        texture
    }

    companion object {

        private val logger = LogManager.getLogger("LiquidBounce/Wry")

        private const val DRM_FORMAT_MOD_INVALID = 0x00ffffffffffffffL

        private val FORMATS = intArrayOf(
            fourcc('A', 'R', '2', '4'),
            fourcc('X', 'R', '2', '4'),
            fourcc('A', 'B', '2', '4'),
            fourcc('X', 'B', '2', '4')
        )

        private val PLANE_FD = intArrayOf(
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE0_FD_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE1_FD_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE2_FD_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE3_FD_EXT
        )
        private val PLANE_OFFSET = intArrayOf(
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE0_OFFSET_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE1_OFFSET_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE2_OFFSET_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE3_OFFSET_EXT
        )
        private val PLANE_PITCH = intArrayOf(
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE0_PITCH_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE1_PITCH_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE2_PITCH_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE3_PITCH_EXT
        )
        private val PLANE_MODIFIER_LO = intArrayOf(
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE3_MODIFIER_LO_EXT
        )
        private val PLANE_MODIFIER_HI = intArrayOf(
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE3_MODIFIER_HI_EXT
        )

        private fun fourcc(a: Char, b: Char, c: Char, d: Char) =
            a.code or (b.code shl 8) or (c.code shl 16) or (d.code shl 24)

        /**
         * An importer for the game's EGL context, or null when the game can't take dmabufs.
         */
        fun create(): WryDmaBufImporter? {
            try {
                EGL.getCapabilities()
            } catch (_: IllegalStateException) {
                EGL.create()
            }

            val display = EGL14.eglGetCurrentDisplay()
            if (display == EGL14.EGL_NO_DISPLAY || EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
                logger.info("The game doesn't render through EGL, pages are copied through memory")
                return null
            }

            val capabilities = EGL.createDisplayCapabilities(display)
            // LWJGL leaves the client extensions out of EGL.getCapabilities()
            val clientExtensions = EGL14.eglQueryString(EGL14.EGL_NO_DISPLAY, EGL14.EGL_EXTENSIONS).orEmpty().split(' ')
            val missing = listOfNotNull(
                "EGL_EXT_image_dma_buf_import".takeUnless { capabilities.EGL_EXT_image_dma_buf_import },
                "EGL_KHR_image_base".takeUnless { capabilities.EGL_KHR_image_base },
                "EGL_EXT_device_query".takeUnless { "EGL_EXT_device_query" in clientExtensions },
                "GL_EXT_EGL_image_storage".takeUnless { GL.getCapabilities().GL_EXT_EGL_image_storage }
            )
            if (missing.isNotEmpty()) {
                logger.info("The game's EGL lacks ${missing.joinToString()}, pages are copied through memory")
                return null
            }

            val renderNode = renderNode(display) ?: run {
                logger.info("The game's GPU has no DRM render node, pages are copied through memory")
                return null
            }
            return WryDmaBufImporter(display, capabilities, renderNode, formats(display, capabilities))
        }

        private fun renderNode(display: Long): String? = MemoryStack.stackPush().use { stack ->
            val device = stack.mallocPointer(1)
            if (!EXTDeviceQuery.eglQueryDisplayAttribEXT(display, EXTDeviceQuery.EGL_DEVICE_EXT, device)) {
                return null
            }
            val extensions = EXTDeviceQuery.eglQueryDeviceStringEXT(device[0], EGL14.EGL_EXTENSIONS) ?: return null
            if ("EGL_EXT_device_drm_render_node" !in extensions.split(' ')) {
                return null
            }
            EXTDeviceQuery.eglQueryDeviceStringEXT(device[0], EXTDeviceDRMRenderNode.EGL_DRM_RENDER_NODE_FILE_EXT)
        }

        /**
         * Pairs of fourcc and modifier the game can sample, empty when EGL doesn't tell.
         */
        private fun formats(display: Long, capabilities: EGLCapabilities): LongArray {
            if (!capabilities.EGL_EXT_image_dma_buf_import_modifiers) {
                return LongArray(0)
            }
            val formats = mutableListOf<Long>()
            for (format in FORMATS) {
                val count = IntArray(1)
                if (!EXTImageDMABufImportModifiers.eglQueryDmaBufModifiersEXT(display, format, null, null, count)) {
                    continue
                }
                val modifiers = LongArray(count[0])
                val externalOnly = IntArray(count[0])
                EXTImageDMABufImportModifiers.eglQueryDmaBufModifiersEXT(display, format, modifiers, externalOnly, count)
                for (index in 0 until count[0]) {
                    if (externalOnly[index] == 0) {
                        formats += format.toLong()
                        formats += modifiers[index]
                    }
                }
            }
            return formats.toLongArray()
        }

    }

}
