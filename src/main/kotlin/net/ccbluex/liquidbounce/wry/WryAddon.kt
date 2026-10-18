package net.ccbluex.liquidbounce.wry

import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendProvider
import org.apache.logging.log4j.LogManager

/**
 * Offers the web view of the system, through Wry, as the browser of the client's interface, next to Chromium.
 */
class WryAddon : LiquidBounceAddon() {

    override fun onInitialize() {
        if (!WryNative.isSupported) {
            LogManager.getLogger("LiquidBounce/Wry").warn(
                "Wry has no library for ${System.getProperty("os.name")} on ${System.getProperty("os.arch")}"
            )
            return
        }

        val missing = WryNative.missingDependency
        registerBrowserBackend(
            BrowserBackendProvider(
                "wry",
                "Wry",
                if (missing == null) {
                    "The web view of your system, added by the Wry add-on."
                } else {
                    "Needs $missing"
                },
                create = ::WryBrowserBackend
            )
        )
    }

}
