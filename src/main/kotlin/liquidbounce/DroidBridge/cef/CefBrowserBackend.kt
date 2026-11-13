package liquidbounce.DroidBridge.cef

import net.ccbluex.liquidbounce.integration.backend.BrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.client.error.ErrorHandler
import net.ccbluex.liquidbounce.utils.kotlin.sortedInsert

class CefBrowserBackend : BrowserBackend {

    override val isInitialized: Boolean
        get() = CefRuntime.isInitialized

    override val browsers = mutableListOf<CefBrowser>()

    override val supportsIncognito = false

    override fun makeDependenciesAvailable(taskManager: TaskManager, whenAvailable: () -> Unit) {
        runCatching {
            CefRuntime.start()
        }.onFailure {
            ErrorHandler.fatal(
                error = it,
                additionalMessage = "Starting the CEF browser. Install the Mcef engine plugin and start its host service."
            )
        }
        whenAvailable()
    }

    override fun start() = Unit

    override fun stop() {
        browsers.toList().forEach(CefBrowser::close)
        CefRuntime.stop()
    }

    override fun update() {
        browsers.toList().forEach(CefBrowser::tick)
    }

    override fun createBrowser(
        url: String,
        position: BrowserViewport,
        settings: BrowserSettings,
        priority: Short,
        incognito: Boolean,
        inputAcceptor: InputAcceptor?
    ) = CefBrowser(this, url, position, settings, priority, incognito, inputAcceptor)
        .apply { browsers.sortedInsert(this, CefBrowser::priority) }

    internal fun removeBrowser(browser: CefBrowser) {
        browsers.remove(browser)
    }

}
