package net.ccbluex.liquidbounce.ultralight

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.integration.backend.BrowserAccelerationFlags
import net.ccbluex.liquidbounce.integration.backend.BrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.client.env
import net.ccbluex.liquidbounce.utils.client.error.ErrorHandler
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.sortedInsert
import net.janrupf.ujr.api.UltralightConfigBuilder
import net.janrupf.ujr.api.UltralightPlatform
import net.janrupf.ujr.api.UltralightRenderer
import net.janrupf.ujr.core.UltralightJavaReborn
import net.janrupf.ujr.core.platform.PlatformEnvironment
import net.janrupf.ujr.core.platform.option.PlatformEnvironmentOption
import net.janrupf.ujr.core.platform.option.PlatformEnvironmentOptionContainer
import net.janrupf.ujr.core.platform.option.std.CommonPlatformOptions
import net.janrupf.ujr.platform.jni.JniPlatformOptions
import net.janrupf.ujr.platform.jni.UJRJniPlatformProviderFactory
import org.apache.logging.log4j.LogManager
import java.io.File
import java.net.URLClassLoader

/**
 * A lightweight browser backend based on Ultralight, a WebKit based HTML renderer made for games.
 *
 * It renders on the CPU, runs in the game process and supports Apple Silicon and Linux ARM64.
 * Enabled with `LB_BROWSER_BACKEND=ultralight`, CEF stays the default.
 *
 * Ultralight is used under its end user license agreement, which comes with the downloaded runtime.
 *
 * @see <a href="https://ultralig.ht">Ultralight</a>
 * @see <a href="https://github.com/CCBlueX/ultralight-java-reborn">Ultralight Java Reborn</a>
 */
class UltralightBrowserBackend : BrowserBackend, EventListener {

    private val folder = ConfigSystem.rootFolder.resolve("ultralight")

    // The game tests keep it outside the game directory, which they wipe before every run
    private val librariesFolder = env("LB_BROWSER_LIBRARIES", "net.ccbluex.liquidbounce.browser.libraries")
        ?.let { File(it).resolve("ultralight") } ?: folder.resolve("libraries")

    private val runtime = UltralightRuntime(librariesFolder)
    private val logger = LogManager.getLogger("LiquidBounce/Ultralight")

    private var ujr: UltralightJavaReborn? = null
    private var rendererInstance: UltralightRenderer? = null
    private var gpuDriverInstance: UltralightGpuDriver? = null

    internal val renderer: UltralightRenderer
        get() = requireNotNull(rendererInstance) { "Ultralight is not started" }

    internal val gpuDriver: UltralightGpuDriver
        get() = requireNotNull(gpuDriverInstance) { "Ultralight is not started" }

    override val isInitialized: Boolean
        get() = rendererInstance != null
    override val browsers = mutableListOf<UltralightBrowser>()
    override var accelerationFlags = BrowserAccelerationFlags.UNSUPPORTED
    override val supportsIncognito = true

    override fun makeDependenciesAvailable(taskManager: TaskManager, whenAvailable: () -> Unit) {
        if (runtime.isAvailable) {
            whenAvailable()
            return
        }

        taskManager.launch("Ultralight") { task ->
            runCatching {
                runtime.download(task)
                mc.execute(whenAvailable)
            }.onFailure {
                ErrorHandler.fatal(error = it, additionalMessage = "Downloading Ultralight")
            }
        }
    }

    override fun start() {
        if (rendererInstance != null) {
            return
        }

        logger.info("Starting Ultralight ${UltralightRuntime.ULTRALIGHT_VERSION}, used under its license agreement " +
            "in ${runtime.eula.absolutePath}")

        val options = PlatformEnvironmentOptionContainer()
        options.addOption(PlatformEnvironmentOption(
            CommonPlatformOptions::class.java,
            // Removed again on cleanup, so it may only hold what Ultralight Java Reborn extracts
            CommonPlatformOptions().temporaryDirectory(folder.resolve("natives").toPath())
        ))
        options.addOption(PlatformEnvironmentOption(
            JniPlatformOptions::class.java,
            JniPlatformOptions()
                .ultralightDirectory(runtime.runtimeDirectory.toPath())
                .nativesClassLoader(URLClassLoader(arrayOf(runtime.nativesJar.toURI().toURL()), null))
        ))

        val environment = PlatformEnvironment.loadWith(UJRJniPlatformProviderFactory().create(options), options)
        ujr = UltralightJavaReborn(environment).apply { activate() }

        UltralightPipelines.compile()
        val gpuDriver = UltralightGpuDriver()
        gpuDriverInstance = gpuDriver

        UltralightPlatform.instance().apply {
            setLogger(UltralightLoggerBridge)
            usePlatformFontLoader()
            setFilesystem(UltralightFilesystemBridge)
            setClipboard(UltralightClipboardBridge)
            setGPUDriver(gpuDriver)
            setConfig(
                UltralightConfigBuilder()
                    .cachePath(folder.resolve("cache").absolutePath)
                    .resourcePathPrefix(UltralightFilesystemBridge.RESOURCE_PREFIX)
                    .build()
            )
        }

        rendererInstance = UltralightRenderer.getOrCreate()
    }

    override fun stop() {
        browsers.toList().forEach(UltralightBrowser::close)
        rendererInstance = null
        ujr?.cleanup()
        ujr = null
        gpuDriverInstance?.close()
        gpuDriverInstance = null
    }

    override fun update() {
        val renderer = rendererInstance ?: return

        try {
            renderer.update()
            renderer.refreshDisplay(0)
            // Draws the pages through the GPU driver
            renderer.render()
        } catch (e: Exception) {
            logger.error("Failed to render the browsers", e)
        }
    }

    override fun createBrowser(
        url: String,
        position: BrowserViewport,
        settings: BrowserSettings,
        priority: Short,
        incognito: Boolean,
        inputAcceptor: InputAcceptor?
    ) = UltralightBrowser(this, url, position, settings, priority, incognito, inputAcceptor)
        .apply { browsers.sortedInsert(this, UltralightBrowser::priority) }

    internal fun removeBrowser(browser: UltralightBrowser) {
        browsers.remove(browser)
    }

}
