package net.ccbluex.liquidbounce.ultralight

import net.ccbluex.liquidbounce.integration.task.type.Task
import net.ccbluex.liquidbounce.utils.client.env
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/**
 * The Ultralight runtime and the natives of Ultralight Java Reborn for this platform.
 *
 * The Ultralight license only allows us to pass Ultralight on to our users as part of LiquidBounce, so the runtime
 * is downloaded from our API, just like the natives of CEF. It comes with the Ultralight EULA and notices.
 */
internal class UltralightRuntime(folder: File) {

    /**
     * The platform in the naming of Ultralight, or null if Ultralight doesn't support it.
     */
    val platform: String? = run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        val osName = when {
            "win" in os -> "win"
            "mac" in os -> "mac"
            "linux" in os -> "linux"
            else -> return@run null
        }

        val archName = when (arch) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return@run null
        }

        // Ultralight has no build for Windows on ARM
        if (osName == "win" && archName == "arm64") null else "$osName-$archName"
    }

    private val directory = folder.resolve(ULTRALIGHT_VERSION)

    /**
     * The runtime with `bin/`, `resources/` and `license/`.
     */
    val runtimeDirectory = directory.resolve("runtime")

    /**
     * The natives jar of Ultralight Java Reborn, which can be replaced for development.
     */
    val nativesJar = env("LB_ULTRALIGHT_NATIVES", "net.ccbluex.liquidbounce.ultralight.natives")?.let(::File)
        ?: directory.resolve("ultralight-java-reborn-platform-jni-$UJR_VERSION-$platform.jar")

    val eula: File
        get() = runtimeDirectory.resolve("license/EULA.txt")

    private val completeMarker = directory.resolve(".complete")
    private val completeContent = "$ULTRALIGHT_VERSION $UJR_VERSION"

    val isAvailable: Boolean
        get() = completeMarker.isFile && completeMarker.readText() == completeContent &&
            runtimeDirectory.isDirectory && nativesJar.isFile

    fun download(task: Task) {
        val platform = requireNotNull(platform) {
            "Ultralight doesn't support ${System.getProperty("os.name")} on ${System.getProperty("os.arch")}"
        }

        directory.deleteRecursively()
        directory.mkdirs()

        val runtimeUrl = "$RUNTIME_URL/$ULTRALIGHT_VERSION/$platform"
        val runtimeArchive = directory.resolve("runtime.tar.gz")
        downloadFile(task, "Ultralight", runtimeUrl, runtimeArchive)
        verify(runtimeArchive, fetch("$runtimeUrl/checksum"))
        extractTarGz(runtimeArchive, runtimeDirectory)
        runtimeArchive.delete()

        if (!nativesJar.isFile) {
            val nativesUrl = "$NATIVES_URL/$UJR_VERSION/${nativesJar.name}"
            downloadFile(task, "Ultralight Java Reborn", nativesUrl, nativesJar)
            verify(nativesJar, fetch("$nativesUrl.sha256"))
        }

        completeMarker.writeText(completeContent)
    }

    private fun downloadFile(task: Task, name: String, url: String, file: File) {
        val progress = task.getOrCreateFileTask(name)
        val response = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == HTTP_OK) { "Downloading $url failed with ${response.statusCode()}" }

        val length = response.headers().firstValueAsLong("Content-Length").orElse(-1)
        response.body().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    read += count
                    progress.update(read, length)
                }
            }
        }
        progress.isCompleted = true
    }

    private fun fetch(url: String): String {
        val response = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) { "Fetching $url failed with ${response.statusCode()}" }
        return response.body().trim()
    }

    private fun extractTarGz(archive: File, target: File) {
        val root = target.canonicalFile
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val file = root.resolve(entry.name).canonicalFile
                check(file.toPath().startsWith(root.toPath())) { "${entry.name} leaves the runtime directory" }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile.mkdirs()
                    file.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    private fun verify(file: File, expectedSha256: String) {
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
        check(actual.equals(expectedSha256, ignoreCase = true)) {
            "Checksum mismatch for ${file.name}: expected $expectedSha256, got $actual"
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

        const val ULTRALIGHT_VERSION = "1.4.0"
        const val UJR_VERSION = "0.2.0"

        private const val RUNTIME_URL = "https://api.liquidbounce.net/api/v3/resource/ultralight"
        private const val NATIVES_URL =
            "https://maven.ccbluex.net/releases/net/ccbluex/ultralight/ultralight-java-reborn-platform-jni"
    }

}
