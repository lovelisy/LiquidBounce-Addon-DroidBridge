package liquidbounce.DroidBridge

import liquidbounce.DroidBridge.cef.CefBrowserBackend
import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendProvider

class Addon : LiquidBounceAddon() {

    override fun onInitialize() {
        registerBrowserBackend(
            BrowserBackendProvider(
                "droidbridge-cef",
                "DroidBridge CEF",
                "Chromium through the Mcef engine plugin, drawn by the client.",
                create = ::CefBrowserBackend
            )
        )
    }

}
