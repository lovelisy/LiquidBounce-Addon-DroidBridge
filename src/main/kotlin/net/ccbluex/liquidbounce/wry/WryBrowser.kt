package net.ccbluex.liquidbounce.wry

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.integration.backend.BrowserTexture
import net.ccbluex.liquidbounce.integration.backend.browser.Browser
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserRenderer
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.browser.GlobalBrowserSettings
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.backend.input.InputHandler
import net.ccbluex.liquidbounce.integration.backend.input.InputListener
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.render.TextureSetup
import org.apache.logging.log4j.LogManager
import org.joml.component1
import org.joml.component2
import org.lwjgl.system.MemoryUtil

/**
 * A browser of the Wry backend, a web view the native library draws off-screen.
 *
 * Everything that reaches the native library happens on the render thread.
 */
@Suppress("TooManyFunctions")
class WryBrowser internal constructor(
    private val backend: WryBrowserBackend,
    url: String,
    viewport: BrowserViewport,
    val settings: BrowserSettings,
    override var priority: Short = 0,
    override val isIncognito: Boolean = false,
    inputAcceptor: InputAcceptor? = null
) : Browser, InputHandler {

    internal val id: Long

    private val logger = LogManager.getLogger("LiquidBounce/WryBrowser/${System.identityHashCode(this)}")

    @Volatile
    private var currentUrl = url

    private var fps = settings.currentFps
    private var size = viewport.getScaledDimensions(GlobalBrowserSettings.quality)
    private var isClosed = false

    private val frame = LongArray(32)
    private var uploadTexture: GpuTexture? = null
    private var uploadView: GpuTextureView? = null
    private var shown: BrowserTexture? = null

    /**
     * The GPU buffer the browser shows, which has to stay imported.
     */
    internal var shownBuffer: Long? = null
        private set

    init {
        require(url.isNotEmpty()) { "URL cannot be empty." }

        val quality = GlobalBrowserSettings.quality
        val (width, height) = size
        id = WryNative.createBrowser(url, width, height, quality.toDouble(), isIncognito, fps)

        logger.info("Initialized browser (url='$url')")
    }

    override val isInitialized = true

    override var state: BrowserState = BrowserState.Idle
        private set(value) {
            field = value

            when (value) {
                is BrowserState.Loading -> logger.info("Started loading (url='$currentUrl')")
                is BrowserState.Success ->
                    logger.info("Finished loading (url='$currentUrl', httpStatusCode=${value.httpStatusCode})")
                is BrowserState.Failure -> logger.warn(
                    "Failed to load (url='${value.failedUrl}', errorCode=${value.errorCode}, " +
                        "errorText=${value.errorText})"
                )
                else -> { /* Idle state, do nothing */ }
            }
        }

    override var viewport: BrowserViewport = viewport
        set(value) {
            field = value

            onRenderThread {
                val quality = GlobalBrowserSettings.quality
                val scaled = value.getScaledDimensions(quality)
                if (scaled != size) {
                    size = scaled
                    WryNative.resize(id, scaled.x(), scaled.y(), quality.toDouble())
                }
            }
        }

    override var visible = true

    private val renderer = BrowserRenderer(this)
    private val inputListener: InputListener? = inputAcceptor?.let { InputListener(this, this, it) }

    override var url: String
        get() = currentUrl
        set(value) {
            currentUrl = value
            onRenderThread {
                state = BrowserState.Idle
                WryNative.navigate(id, value)
            }
        }

    override val texture: BrowserTexture?
        get() = shown

    override fun forceReload() = onRenderThread { WryNative.reload(id, true) }

    override fun reload() = onRenderThread { WryNative.reload(id, false) }

    override fun goForward() = onRenderThread { WryNative.goForward(id) }

    override fun goBack() = onRenderThread { WryNative.goBack(id) }

    override fun close() {
        renderer.close()
        inputListener?.close()
        backend.removeBrowser(this)

        onRenderThread {
            if (isClosed) {
                return@onRenderThread
            }
            isClosed = true
            if (backend.isInitialized) {
                WryNative.closeBrowser(id)
            }
            shown = null
            shownBuffer = null
            uploadView?.close()
            uploadTexture?.close()
        }
    }

    override fun update(width: Int, height: Int) {
        if (!viewport.fullScreen) {
            return
        }

        viewport = viewport.copy(width = width, height = height)
    }

    // Pages send a new frame whenever they change
    override fun invalidate() = Unit

    override fun toString() = "WryBrowser(" +
        "id=$id, " +
        "url='$currentUrl', " +
        "incognito=$isIncognito, " +
        "visible=$visible, " +
        "priority=$priority" +
        ")"

    /**
     * Brings the newest frame of the page into a texture of the game.
     */
    internal fun updateFrame() {
        if (settings.currentFps != fps) {
            fps = settings.currentFps
            WryNative.setFps(id, fps)
        }
        if (!visible) {
            return
        }

        when (WryNative.takeFrame(id, frame)) {
            WryNative.FRAME_PIXELS -> upload(
                address = frame[4],
                length = frame[5].toInt(),
                width = frame[0].toInt(),
                height = frame[1].toInt(),
                stride = frame[2].toInt(),
                bgra = frame[3] and 1L != 0L
            )
            WryNative.FRAME_DMA_BUF, WryNative.FRAME_SHARED_TEXTURE, WryNative.FRAME_IO_SURFACE -> {
                val importer = backend.gpuImporter ?: return
                val texture = importer.texture(frame) ?: return
                shownBuffer = importer.id(frame)
                shown = BrowserTexture(
                    TextureSetup.singleTexture(texture, sampler), viewport.width, viewport.height, importer.bgra
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun upload(address: Long, length: Int, width: Int, height: Int, stride: Int, bgra: Boolean) {
        val device = RenderSystem.getDevice()
        var texture = uploadTexture
        var view = uploadView
        if (texture == null || view == null || texture.getWidth(0) != width || texture.getHeight(0) != height) {
            view?.close()
            texture?.close()
            texture = device.createTexture(
                "Wry browser $id",
                GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
            )
            view = device.createTextureView(texture)
            uploadTexture = texture
            uploadView = view
        }

        val encoder = device.createCommandEncoder()
        val staging = encoder.transientMemory()
            .uploadStaging(MemoryUtil.memByteBuffer(address, length), 4L, GpuBuffer.USAGE_COPY_SRC)
        encoder.copyBufferToTexture(staging, 0, 0, stride / 4, height, texture, 0, 0, width, height, 0, 0)

        shownBuffer = null
        shown = BrowserTexture(TextureSetup.singleTexture(view, sampler), viewport.width, viewport.height, bgra)
    }

    internal fun onEvent(event: WryEvent) {
        when (event.kind) {
            WryNative.EVENT_LOADING -> state = BrowserState.Loading
            WryNative.EVENT_LOADED -> state = BrowserState.Success(event.code)
            WryNative.EVENT_FAILED ->
                state = BrowserState.Failure(event.code, event.detail, event.text.ifEmpty { currentUrl })
            WryNative.EVENT_URL -> if (event.text.isNotEmpty()) {
                currentUrl = event.text
            }
            WryNative.EVENT_CONSOLE -> {
                val text = "[console] ${event.text}"
                if (event.code >= 2) logger.warn(text) else logger.debug(text)
            }
            WryNative.EVENT_CURSOR -> if (visible) {
                mc.window.selectCursor(WryInput.cursor(event.text))
            }
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int) {
        val button = WryInput.mouseButton(mouseButton) ?: return
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)

        native {
            WryNative.focus(id)
            WryNative.mouseButton(id, x.toDouble(), y.toDouble(), button, true)
        }
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, mouseButton: Int) {
        val button = WryInput.mouseButton(mouseButton) ?: return
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)

        native { WryNative.mouseButton(id, x.toDouble(), y.toDouble(), button, false) }
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)

        native { WryNative.mouseMove(id, x.toDouble(), y.toDouble()) }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double) {
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)

        native { WryNative.mouseScroll(id, x.toDouble(), y.toDouble(), delta) }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int) = native {
        WryNative.focus(id)
        WryNative.key(id, true, keyCode, scanCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int) = native {
        WryNative.key(id, false, keyCode, scanCode, modifiers)
    }

    override fun charTyped(codepoint: Int) = native {
        WryNative.focus(id)
        WryNative.text(id, String(Character.toChars(codepoint)))
    }

    private inline fun native(action: () -> Unit) {
        if (!isClosed && backend.isInitialized) {
            action()
        }
    }

    private fun onRenderThread(action: () -> Unit) {
        if (RenderSystem.isOnRenderThread()) {
            action()
        } else {
            mc.execute(action)
        }
    }

    private companion object {
        val sampler
            get() = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

}
