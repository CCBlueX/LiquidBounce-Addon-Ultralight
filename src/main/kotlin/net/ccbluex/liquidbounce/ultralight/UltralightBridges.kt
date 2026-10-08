package net.ccbluex.liquidbounce.ultralight

import net.ccbluex.liquidbounce.utils.client.mc
import net.janrupf.ujr.api.UltralightResources
import net.janrupf.ujr.api.clipboard.UltralightClipboard
import net.janrupf.ujr.api.filesystem.UltralightFilesystem
import net.janrupf.ujr.api.logger.UltralightLogLevel
import net.janrupf.ujr.api.logger.UltralightLogger
import net.janrupf.ujr.api.util.NioUltralightBuffer
import net.janrupf.ujr.api.util.UltralightBuffer
import org.apache.logging.log4j.LogManager
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Serves the resources of the Ultralight runtime, such as the ICU data and the CA certificates.
 *
 * Pages are loaded over HTTP, so nothing else is needed.
 */
internal object UltralightFilesystemBridge : UltralightFilesystem {

    const val RESOURCE_PREFIX = "\$built-in/resources/"

    override fun fileExists(path: String) = resource(path) != null

    override fun getFileMimeType(path: String) = "application/octet-stream"

    override fun getFileCharset(path: String) = "utf-8"

    override fun openFile(path: String): UltralightBuffer? {
        val resource = resource(path) ?: return null
        val bytes = Files.readAllBytes(Paths.get(resource))

        return NioUltralightBuffer(ByteBuffer.allocateDirect(bytes.size).put(bytes).flip())
    }

    private fun resource(path: String) = if (path.startsWith(RESOURCE_PREFIX)) {
        UltralightResources.getResource(path.removePrefix(RESOURCE_PREFIX))
    } else {
        null
    }

}

internal object UltralightClipboardBridge : UltralightClipboard {

    override fun clear() {
        mc.keyboardHandler.clipboard = ""
    }

    override fun readPlainText(): String = mc.keyboardHandler.clipboard

    override fun writePlainText(text: String) {
        mc.keyboardHandler.clipboard = text
    }

}

internal object UltralightLoggerBridge : UltralightLogger {

    private val logger = LogManager.getLogger("LiquidBounce/Ultralight")

    override fun logMessage(logLevel: UltralightLogLevel, message: String) {
        when (logLevel) {
            UltralightLogLevel.ERROR -> logger.error(message)
            UltralightLogLevel.WARNING -> logger.warn(message)
            else -> logger.info(message)
        }
    }

}
