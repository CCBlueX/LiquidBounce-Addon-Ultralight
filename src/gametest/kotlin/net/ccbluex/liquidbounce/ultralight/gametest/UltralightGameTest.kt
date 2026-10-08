package net.ccbluex.liquidbounce.ultralight.gametest

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManager
import net.ccbluex.liquidbounce.integration.backend.BrowserSelectionScreen
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.integration.screen.impl.CustomStandaloneMinecraftScreen
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

        // The real ClickGUI keybind opens a standalone screen with its own browser, next to the still-alive
        // main menu browser, not the shared one the title page ran in.
        context.client { client ->
            client.gui.setScreen(CustomStandaloneMinecraftScreen(CustomScreenType.CLICK_GUI))
        }
        context.waitFor({ (it.gui.screen() as? CustomStandaloneMinecraftScreen)?.browser?.state is BrowserState.Success }, 20 * 60)
        context.waitTicks(60)
        context.takeScreenshot("ClickGui")

        // Expand the Combat category through its "+" toggle button, Minecraft drops the first move of the cursor
        context.input.setCursorPos(248.0, 89.0)
        context.input.setCursorPos(249.0, 90.0)
        context.waitTicks(5)
        context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
        context.waitTicks(20)
        context.takeScreenshot("CombatExpanded")

        // Right click the first module row to open its settings, like Module.svelte's contextmenu listener expects
        context.input.setCursorPos(144.0, 128.0)
        context.input.setCursorPos(145.0, 129.0)
        context.waitTicks(5)
        context.input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT)
        context.waitTicks(20)
        context.takeScreenshot("ModuleSettings")

        // The test has to end on the game's own title screen, which the client only keeps in basic mode
        context.client { client ->
            ThemeManager.basicMode = true
            client.gui.setScreen(TitleScreen())
        }
        context.waitFor { it.gui.screen() is TitleScreen }
    }

    private fun ClientGameTestContext.client(block: (Minecraft) -> Unit) = runOnClient<RuntimeException> { block(it) }

}
