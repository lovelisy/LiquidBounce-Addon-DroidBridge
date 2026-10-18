package net.ccbluex.liquidbounce.wry

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.integration.backend.BrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.client.env
import net.ccbluex.liquidbounce.utils.client.error.ErrorHandler
import net.ccbluex.liquidbounce.utils.kotlin.sortedInsert
import org.apache.logging.log4j.LogManager

private val isBrowserAccelerationDisabled = env("LB_BROWSER_DISABLE_ACCELERATION",
    "net.ccbluex.liquidbounce.browser.disableAcceleration")?.toBoolean() ?: false

/**
 * Shows the client's pages in the web view of the system: WebView2 on Windows, WKWebView on macOS and WebKitGTK on
 * Linux, through Wry.
 *
 * The web views draw off-screen in the native library, which hands their frames to [update] on the render thread.
 */
class WryBrowserBackend : BrowserBackend, EventListener {

    private val folder = ConfigSystem.rootFolder.resolve("wry")
    private val logger = LogManager.getLogger("LiquidBounce/Wry")

    override var isInitialized = false
        private set
    override val browsers = mutableListOf<WryBrowser>()
    override val supportsIncognito = true

    internal var gpuImporter: WryGpuImporter? = null
        private set

    override fun makeDependenciesAvailable(taskManager: TaskManager, whenAvailable: () -> Unit) {
        WryNative.missingDependency?.let {
            ErrorHandler.fatal(
                error = IllegalStateException(
                    "Wry needs $it. Hold Shift while the client starts to choose another browser."
                ),
                needToReport = false
            )
        }
        runCatching {
            WryNative.load(folder.resolve("natives"))
        }.onFailure {
            ErrorHandler.fatal(error = it, additionalMessage = "Loading Wry")
        }
        whenAvailable()
    }

    override fun start() {
        if (isInitialized) {
            return
        }

        val importer = if (isBrowserAccelerationDisabled) null else WryGpuImporter.create()
        gpuImporter = importer
        runCatching {
            WryNative.start(
                folder.resolve("data").absolutePath,
                importer != null,
                importer?.renderNode,
                importer?.formats ?: LongArray(0)
            )
        }.onFailure {
            // The client's startup task would drop it silently
            ErrorHandler.fatal(error = it, additionalMessage = "Starting Wry")
        }
        isInitialized = true
        pollEvents()
        logger.info(
            if (importer != null) {
                "Pages stay on the GPU (${importer.javaClass.simpleName})"
            } else {
                "Pages reach the game through memory"
            }
        )
    }

    override fun stop() {
        browsers.toList().forEach(WryBrowser::close)
        if (isInitialized) {
            WryNative.stop()
            isInitialized = false
        }
        gpuImporter?.close()
        gpuImporter = null
    }

    override fun update() {
        if (!isInitialized) {
            return
        }

        try {
            WryNative.update()
            pollEvents()
            for (browser in browsers) {
                browser.updateFrame()
            }
            gpuImporter?.collect(browsers.mapNotNullTo(HashSet()) { it.shownBuffer })
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
                WryNative.EVENT_BUFFER_GONE -> gpuImporter?.forget(event.value)
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
