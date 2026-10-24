package liquidbounce.DroidBridge

import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendProvider
import org.apache.logging.log4j.LogManager

/**
 * Shows the client's interface with the web view of Android, on top of the game.
 */
class WryAddon : LiquidBounceAddon() {

    override fun onInitialize() {
        if (!WryNative.isSupported) {
            LogManager.getLogger("LiquidBounce/DroidBridge").warn(
                "DroidBridge has no library for ${System.getProperty("os.arch")}"
            )
            return
        }

        registerBrowserBackend(
            BrowserBackendProvider(
                "droidbridge",
                "DroidBridge",
                "The web view of Android, drawn on top of the game.",
                create = ::WryBrowserBackend
            )
        )
    }

}
