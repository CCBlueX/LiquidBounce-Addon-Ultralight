package net.ccbluex.liquidbounce.ultralight

import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendProvider

/**
 * Offers Ultralight as the browser of the client's interface, next to Chromium.
 */
class UltralightAddon : LiquidBounceAddon() {

    override fun onInitialize() {
        registerBrowserBackend(
            BrowserBackendProvider(
                "ultralight",
                "Ultralight",
                "An alternative browser engine, added by the Ultralight add-on.",
                create = ::UltralightBrowserBackend
            )
        )
    }

}
