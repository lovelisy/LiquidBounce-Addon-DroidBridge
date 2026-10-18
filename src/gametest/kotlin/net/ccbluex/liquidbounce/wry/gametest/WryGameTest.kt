package net.ccbluex.liquidbounce.wry.gametest

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManager
import net.ccbluex.liquidbounce.integration.backend.BrowserSelectionScreen
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.integration.theme.ThemeManager
import net.ccbluex.liquidbounce.wry.WryBrowserBackend
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen

/**
 * Starts the client with the add-on, picks Wry on the selection screen and checks that it shows the client's pages and
 * takes clicks, taking the screenshots the README shows.
 */
class WryGameTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.input.resizeWindow(1600, 900)

        context.waitFor({ it.gui.screen() is BrowserSelectionScreen }, 20 * 120)
        context.waitTicks(20)
        context.takeScreenshot("Selection")

        context.client { client ->
            val screen = client.gui.screen() as BrowserSelectionScreen
            screen.setFocused(screen.children().filterIsInstance<Button>().single { it.message.string == "Wry" })
        }
        context.input.pressKey(InputConstants.KEY_RETURN)

        context.waitFor({ ScreenManager.mainBrowser?.state is BrowserState.Success }, 20 * 180)
        check(BrowserBackendManager.backend is WryBrowserBackend) {
            "The client uses ${BrowserBackendManager.backend} instead of Wry"
        }
        context.waitFor({ ScreenManager.mainBrowser?.texture != null }, 20 * 60)
        context.waitTicks(60)
        context.takeScreenshot("Title")

        // The page's own Singleplayer button, see docs/title.png. Minecraft drops the first move of the cursor.
        context.input.setCursorPos(270.0, 220.0)
        context.input.setCursorPos(275.0, 225.0)
        context.waitTicks(5)
        context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
        context.waitFor({ ScreenManager.screen?.type == CustomScreenType.SINGLEPLAYER }, 20 * 30)
        context.waitTicks(60)
        context.takeScreenshot("Singleplayer")

        context.client { client ->
            ThemeManager.basicMode = true
            client.gui.setScreen(TitleScreen())
        }
        context.waitFor { it.gui.screen() is TitleScreen }
    }

    private fun ClientGameTestContext.client(block: (Minecraft) -> Unit) = runOnClient<RuntimeException> { block(it) }

}
