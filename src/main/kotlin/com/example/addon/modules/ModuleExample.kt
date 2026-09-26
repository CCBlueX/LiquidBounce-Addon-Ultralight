package com.example.addon.modules

import com.example.addon.ExampleCategories
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat

/**
 * An ordinary [ClientModule]: settings, event handlers and the ClickGUI entry all work exactly as
 * they do for a built-in module.
 */
object ModuleExample : ClientModule("Example", ExampleCategories.EXAMPLE) {

    private val interval by int("Interval", 100, 20..600, "ticks")
    private val greeting by text("Greeting", "Hello from the example add-on")

    @Suppress("unused")
    private val tickHandler = tickHandler {
        waitTicks(interval)
        chat(greeting)
    }

}
