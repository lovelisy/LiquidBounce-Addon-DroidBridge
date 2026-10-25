package liquidbounce.DroidBridge

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
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.utils.client.mc
import org.apache.logging.log4j.LogManager
import org.joml.component1
import org.joml.component2

/**
 * A browser of the Android backend, a web view the system draws on top of the game.
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

    private val logger = LogManager.getLogger("LiquidBounce/DroidBridge/${System.identityHashCode(this)}")

    @Volatile
    private var currentUrl = url

    private var size = viewport.getScaledDimensions(GlobalBrowserSettings.quality)
    private var isClosed = false
    private var shownScreen = false

    init {
        require(url.isNotEmpty()) { "URL cannot be empty." }

        val quality = GlobalBrowserSettings.quality
        val (width, height) = size
        id = WryNative.createBrowser(url, width, height, quality.toDouble(), isIncognito, settings.currentFps)

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

    /**
     * The web view of the system is drawn by the system on top of the game, so the game has no texture of it.
     */
    override val texture: BrowserTexture?
        get() = null

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
        }
    }

    override fun update(width: Int, height: Int) {
        if (!viewport.fullScreen) {
            return
        }

        viewport = viewport.copy(width = width, height = height)
    }

    // The page is a view of the system, it invalidates itself
    override fun invalidate() = Unit

    override fun toString() = "WryBrowser(" +
        "id=$id, " +
        "url='$currentUrl', " +
        "incognito=$isIncognito, " +
        "visible=$visible, " +
        "priority=$priority" +
        ")"

    /**
     * The web view is shown while a client screen owns the game, and hidden while the player is in the
     * world. [visible] only tells whether the screen that owns this tab asked for it; the main tab is
     * never asked, so it is shown whenever a client screen is open.
     */
    private fun pushVisible() {
        val clientScreen = ScreenManager.isClientScreen(mc.gui.screen())
        val show = if (priority > 0 || inputListener != null) {
            clientScreen && (visible || ScreenManager.mainBrowser === this)
        } else {
            visible && (clientScreen || mc.player != null)
        }
        if (show == shownScreen) {
            return
        }
        shownScreen = show
        onRenderThread { WryNative.setVisible(id, show) }
    }

    /**
     * Pushes what the game tells the web view this frame.
     */
    internal fun updateFrame() {
        pushVisible()
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
        WryNative.key(id, true, keyCode, scanCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int) = native {
        WryNative.key(id, false, keyCode, scanCode, modifiers)
    }

    override fun charTyped(codepoint: Int) = native {
        when (codepoint) {
            0x08 -> WryNative.text(id, "\b")
            0x0A, 0x0D -> WryNative.enter(id)
            else -> if (codepoint >= 0x20 && codepoint != 0x7F) {
                WryNative.text(id, String(Character.toChars(codepoint)))
            }
        }
    }

    private inline fun native(action: () -> Unit) {
        if (!isClosed && backend.isInitialized) {
            action()
        }
    }

    private fun onRenderThread(action: () -> Unit) {
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            action()
        } else {
            mc.execute(action)
        }
    }

}
