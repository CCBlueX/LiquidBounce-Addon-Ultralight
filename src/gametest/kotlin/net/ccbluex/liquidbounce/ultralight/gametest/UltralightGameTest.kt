package net.ccbluex.liquidbounce.ultralight.gametest

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManager
import net.ccbluex.liquidbounce.integration.backend.BrowserSelectionScreen
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.integration.theme.ThemeManager
import net.ccbluex.liquidbounce.ultralight.UltralightBrowserBackend
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen

/**
 * Starts the client with the add-on, picks Ultralight on the selection screen and checks that it shows the
 * client's pages, taking the screenshots the README and the Marketplace show.
 */
class UltralightGameTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.input.resizeWindow(1600, 900)

        // Nothing was picked yet, so the client asks before it loads either browser
        context.waitFor({ it.gui.screen() is BrowserSelectionScreen }, 20 * 120)
        context.waitTicks(20)
        context.takeScreenshot("Selection")

        // Picks Ultralight like a player would with the keyboard
        context.client { client ->
            val screen = client.gui.screen() as BrowserSelectionScreen
            screen.setFocused(screen.children().filterIsInstance<Button>().single { it.message.string == "Ultralight" })
        }
        context.input.pressKey(InputConstants.KEY_RETURN)

        context.waitFor({ ScreenManager.mainBrowser?.state is BrowserState.Success }, 20 * 180)
        check(BrowserBackendManager.backend is UltralightBrowserBackend) {
            "The client uses ${BrowserBackendManager.backend} instead of Ultralight"
        }
        // The title page animates in
        context.waitTicks(60)
        context.takeScreenshot("Title")

        // The test has to end on the game's own title screen, which the client only keeps in basic mode
        context.client { client ->
            ThemeManager.basicMode = true
            client.gui.setScreen(TitleScreen())
        }
        context.waitFor { it.gui.screen() is TitleScreen }
    }

    private fun ClientGameTestContext.client(block: (Minecraft) -> Unit) = runOnClient<RuntimeException> { block(it) }

}
