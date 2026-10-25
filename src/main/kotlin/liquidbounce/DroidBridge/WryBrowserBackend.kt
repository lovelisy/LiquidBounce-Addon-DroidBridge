package liquidbounce.DroidBridge

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.backend.BrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.client.error.ErrorHandler
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.READ_FINAL_STATE
import net.ccbluex.liquidbounce.utils.kotlin.sortedInsert
import org.apache.logging.log4j.LogManager

/**
 * Shows the client's pages in the web view of Android, drawn on top of the game by the system.
 */
class WryBrowserBackend : BrowserBackend, EventListener {

    private val folder = ConfigSystem.rootFolder.resolve("droidbridge")
    private val logger = LogManager.getLogger("LiquidBounce/DroidBridge")

    override var isInitialized = false
        private set
    override val browsers = mutableListOf<WryBrowser>()
    override val supportsIncognito = true

    private var dirtyVisible = false
    private val dirtyVisibleBrowsers = mutableListOf<WryBrowser>()

    override fun makeDependenciesAvailable(taskManager: TaskManager, whenAvailable: () -> Unit) {
        runCatching {
            WryNative.load(folder.resolve("natives"))
        }.onFailure {
            ErrorHandler.fatal(error = it, additionalMessage = "Loading DroidBridge")
        }
        whenAvailable()
    }

    override fun start() {
        if (isInitialized) {
            return
        }

        runCatching {
            WryNative.start(folder.resolve("data").absolutePath, false, null, LongArray(0))
        }.onFailure {
            // The client's startup task would drop it silently
            ErrorHandler.fatal(error = it, additionalMessage = "Starting DroidBridge")
        }
        isInitialized = true
        pollEvents()
        logger.info("Pages are drawn by the web view of the system")
    }

    override fun stop() {
        browsers.toList().forEach(WryBrowser::close)
        if (isInitialized) {
            WryNative.stop()
            isInitialized = false
        }
    }

    /**
     * Marks every browser so its visibility is reevaluated on the next screen or world change,
     * mirroring how the vanilla backend lets the renderer decide each frame while we only decide on
     * events.
     */
    internal fun markVisibleDirty(browser: WryBrowser? = null) {
        if (browser == null) {
            dirtyVisible = true
            return
        }

        dirtyVisibleBrowsers.add(browser)
    }

    private fun updateVisibleBrowsers() {
        if (!dirtyVisible && dirtyVisibleBrowsers.isEmpty()) {
            return
        }

        val clientScreen = ScreenManager.isClientScreen(mc.gui.screen())
        val inGame = mc.player != null
        val mainBrowser = ScreenManager.mainBrowser
        val targets = if (dirtyVisible) {
            dirtyVisible = false
            dirtyVisibleBrowsers.clear()
            browsers.toList()
        } else {
            val pending = dirtyVisibleBrowsers.toList()
            dirtyVisibleBrowsers.clear()
            pending
        }

        for (browser in targets) {
            if (browser in browsers) {
                browser.updateVisibility(clientScreen, inGame, mainBrowser === browser)
            }
        }
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent>(priority = READ_FINAL_STATE) {
        markVisibleDirty()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        markVisibleDirty()
    }

    override fun update() {
        if (!isInitialized) {
            return
        }

        try {
            WryNative.update()
            pollEvents()
            updateVisibleBrowsers()
        } catch (e: Exception) {
            logger.error("Failed to update the browsers", e)
        }
    }

    private fun pollEvents() {
        val events = WryNative.pollEvents() ?: return
        for (event in events) {
            when (event.kind) {
                WryNative.EVENT_LOG -> when (event.code) {
                    0 -> logger.debug(event.text)
                    1 -> logger.info(event.text)
                    2 -> logger.warn(event.text)
                    else -> logger.error(event.text)
                }
                else -> browsers.firstOrNull { it.id == event.browser }?.onEvent(event)
            }
        }
    }

    override fun createBrowser(
        url: String,
        position: BrowserViewport,
        settings: BrowserSettings,
        priority: Short,
        incognito: Boolean,
        inputAcceptor: InputAcceptor?
    ) = WryBrowser(this, url, position, settings, priority, incognito, inputAcceptor)
        .apply { browsers.sortedInsert(this, WryBrowser::priority) }

    internal fun removeBrowser(browser: WryBrowser) {
        browsers.remove(browser)
    }

}
