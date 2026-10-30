package liquidbounce.DroidBridge

import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
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

        registerModules(DroidBridgeSettings)
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

internal object DroidBridgeSettings :
    ClientModule("DroidBridge", ModuleCategories.RENDER, disableActivation = true) {

    private val clickgui by float("ClickGuiScale", 1f, 0.5f..2f).onChanged { apply() }

    private val hud by float("HudScale", 1f, 0.5f..2f).onChanged { apply() }

    private val settingsScale by float("SettingsScale", 1f, 0.5f..2f).onChanged { apply() }

    fun apply() {
        if (!WryNative.isSupported) {
            return
        }
        runCatching { WryNative.configure(clickgui, hud, settingsScale) }
    }

}
