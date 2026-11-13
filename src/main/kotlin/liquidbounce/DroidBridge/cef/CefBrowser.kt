package liquidbounce.DroidBridge.cef

import com.cinemamod.mcef.GlfwToAndroidKey
import com.cinemamod.mcef.nativebridge.McefNative
import liquidbounce.DroidBridge.Input
import net.ccbluex.liquidbounce.integration.backend.BrowserTexture
import net.ccbluex.liquidbounce.integration.backend.browser.Browser as LbBrowser
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserRenderer
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.browser.GlobalBrowserSettings
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.backend.input.InputHandler
import net.ccbluex.liquidbounce.integration.backend.input.InputListener
import org.apache.logging.log4j.LogManager
import org.joml.component1
import org.joml.component2
import java.nio.ByteBuffer

@Suppress("TooManyFunctions")
class CefBrowser internal constructor(
    private val backend: CefBrowserBackend,
    url: String,
    viewport: BrowserViewport,
    val settings: BrowserSettings,
    override var priority: Short = 0,
    override val isIncognito: Boolean = false,
    inputAcceptor: InputAcceptor? = null
) : LbBrowser, InputHandler {

    private val logger = LogManager.getLogger("LiquidBounce/DroidBridge/CEF/${System.identityHashCode(this)}")

    private var currentUrl = url
    private var closed = false
    private var lastVersion = 0
    private var frame: ByteBuffer? = null
    private val cefTexture = CefTexture()

    private val renderer = BrowserRenderer(this)
    private val inputListener: InputListener? = inputAcceptor?.let { InputListener(this, this, it) }

    private val handle: Long = run {
        val (width, height) = viewport.getScaledDimensions(GlobalBrowserSettings.quality)
        val created = McefNative.createBrowser(url, width, height, 1f, true)
        check(created > 0) {
            "CEF 浏览器创建失败：${McefNative.getLastError() ?: "未知错误"}"
        }
        logger.info("Initialized CEF browser (url='$url', {}x{})", width, height)
        created
    }

    override val isInitialized: Boolean
        get() = handle > 0 && !closed

    override var state: BrowserState = BrowserState.Loading
        private set

    override var viewport: BrowserViewport = viewport
        set(value) {
            field = value
            if (!closed) {
                val (width, height) = value.getScaledDimensions(GlobalBrowserSettings.quality)
                McefNative.resize(handle, width, height)
            }
        }

    override var visible = true

    override var url: String
        get() = currentUrl
        set(value) {
            currentUrl = value
            state = BrowserState.Loading
            if (!closed) {
                McefNative.loadUrl(handle, value)
            }
        }

    override val texture: BrowserTexture?
        get() {
            val setup = cefTexture.setup ?: return null
            if (!cefTexture.painted) {
                return null
            }
            return BrowserTexture(setup, viewport.width, viewport.height, true)
        }

    override fun forceReload() = reload()

    override fun reload() {
        if (!closed) {
            state = BrowserState.Loading
            McefNative.loadUrl(handle, currentUrl)
        }
    }

    override fun goForward() = Unit

    override fun goBack() = Unit

    override fun update(width: Int, height: Int) {
        if (!viewport.fullScreen) {
            return
        }
        viewport = viewport.copy(width = width, height = height)
    }

    override fun invalidate() = Unit

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        renderer.close()
        inputListener?.close()
        backend.removeBrowser(this)
        cefTexture.close()
        McefNative.destroyBrowser(handle)
    }

    internal fun tick() {
        if (closed || handle <= 0) {
            return
        }

        val info = McefNative.getFrameInfo(handle) ?: return
        if (info.size < 5 || info[0] != McefNative.OK) {
            return
        }

        val width = info[1]
        val height = info[2]
        val stride = info[3]
        val version = info[4]
        if (width <= 0 || height <= 0 || stride < width * 4) {
            return
        }
        if (version <= 0 || version == lastVersion) {
            return
        }

        val bytes = stride.toLong() * height
        if (bytes <= 0 || bytes > Int.MAX_VALUE) {
            return
        }

        var buffer = frame
        if (buffer == null || buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes.toInt())
            frame = buffer
        }
        buffer.clear()
        buffer.limit(bytes.toInt())

        if (McefNative.copyFrame(handle, buffer) != McefNative.OK) {
            return
        }

        buffer.position(0)
        buffer.limit(bytes.toInt())
        cefTexture.upload(buffer, width, height)
        lastVersion = version
        if (state !is BrowserState.Success) {
            state = BrowserState.Success(200)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int) {
        val button = Input.mouseButton(mouseButton) ?: return
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        send(McefNative.INPUT_MOUSE_DOWN, x, y, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, mouseButton: Int) {
        val button = Input.mouseButton(mouseButton) ?: return
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        send(McefNative.INPUT_MOUSE_UP, x, y, button)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        send(McefNative.INPUT_MOUSE_MOVE, x, y, 0)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double) {
        val (x, y) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        if (closed) {
            return
        }
        McefNative.sendInput(
            handle, McefNative.INPUT_MOUSE_WHEEL, x, y, 0,
            0, 0, 0, 0.0, delta, 0
        )
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int) {
        val key = GlfwToAndroidKey.map(keyCode)
        if (key == 0) {
            return
        }
        send(McefNative.INPUT_KEY_DOWN, 0, 0, 0, key, scanCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int) {
        val key = GlfwToAndroidKey.map(keyCode)
        if (key == 0) {
            return
        }
        send(McefNative.INPUT_KEY_UP, 0, 0, 0, key, scanCode, modifiers)
    }

    override fun charTyped(codepoint: Int) {
        if (closed) {
            return
        }
        McefNative.sendInput(
            handle, McefNative.INPUT_CHAR, 0, 0, 0,
            0, 0, 0, 0.0, 0.0, codepoint
        )
    }

    private fun send(type: Int, x: Int, y: Int, button: Int) =
        send(type, x, y, button, 0, 0, 0)

    private fun send(type: Int, x: Int, y: Int, button: Int, key: Int, scanCode: Int, modifiers: Int) {
        if (closed) {
            return
        }
        McefNative.sendInput(
            handle, type, x, y, button,
            key, scanCode, modifiers, 0.0, 0.0, 0
        )
    }

    override fun toString() = "CefBrowser(handle=$handle, url='$currentUrl')"

}
