package net.ccbluex.liquidbounce.ultralight

import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.integration.task.type.Task
import net.ccbluex.liquidbounce.utils.client.env
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/**
 * The Ultralight runtime and the natives of Ultralight Java Reborn for this platform.
 *
 * The runtime is unpacked from the Ultralight SDK, which is downloaded from Ultralight and comes with the Ultralight
 * EULA and notices.
 */
internal class UltralightRuntime(folder: File) {

    /**
     * The platform in the naming of Ultralight Java Reborn, or null if Ultralight doesn't support it.
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

        "$osName-$archName".takeIf { it in PLATFORMS }
    }

    private val directory = folder.resolve(ULTRALIGHT_VERSION)

    /**
     * The runtime with `bin/`, `resources/`, `license/` and the GLSL shaders of the SDK.
     */
    val runtimeDirectory = directory.resolve("runtime")

    /**
     * The shaders of Ultralight's GPU drivers, as C++ headers. They may only be used with Ultralight, so they are
     * read from the SDK instead of being shipped with the add-on.
     */
    val shaderDirectory = runtimeDirectory.resolve("platform/shaders/generated/headers/glsl")

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
            "Ultralight $ULTRALIGHT_VERSION doesn't support ${System.getProperty("os.name")} on " +
                System.getProperty("os.arch")
        }

        directory.deleteRecursively()
        directory.mkdirs()

        // The download API names the platforms like windows-x64 and macos-arm64
        val sdkPlatform = platform.replace("win-", "windows-").replace("mac-", "macos-")
        val sdk = JsonParser.parseString(fetch(
            "$SDK_API?platform=${URLEncoder.encode(sdkPlatform, Charsets.UTF_8)}" +
                "&version=${URLEncoder.encode(ULTRALIGHT_VERSION, Charsets.UTF_8)}&format=json"
        )).asJsonObject

        val sdkArchive = directory.resolve("sdk.7z")
        downloadFile(task, "Ultralight", sdk.get("url").asString, sdkArchive)
        // Older releases have no checksum in the API
        sdk.get("sha256")?.takeUnless { it.isJsonNull }?.let { verify(sdkArchive, it.asString) }
        extractRuntime(sdkArchive, runtimeDirectory)
        sdkArchive.delete()

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

    /**
     * Unpacks what runs Ultralight from the SDK, which also holds headers, samples and tools.
     *
     * The Linux and Windows SDKs pack their libraries with BCJ2, which only 7-Zip itself decodes. 7-Zip-JBinding has no
     * build for Apple Silicon, whose SDK Commons Compress reads.
     */
    private fun extractRuntime(archive: File, target: File) {
        if (platform == "mac-arm64") {
            extractWithCommonsCompress(archive, target.canonicalFile)
        } else {
            extractWith7Zip(archive, target.canonicalFile)
        }
    }

    private fun extractWithCommonsCompress(archive: File, root: File) {
        SevenZFile.builder().setFile(archive).get().use { sevenZ ->
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (entry.isDirectory) continue

                val file = runtimeFile(root, entry.name) ?: continue
                file.parentFile.mkdirs()
                sevenZ.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun extractWith7Zip(archive: File, root: File) {
        // Named, as it would only look at the first of the bundled platforms otherwise. Unpacks its native library into
        // the given directory.
        SevenZip.initSevenZipFromPlatformJAR(if (platform == "win-x64") "Windows-amd64" else "Linux-amd64", directory)

        RandomAccessFile(archive, "r").use { file ->
            SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, RandomAccessFileInStream(file)).use { sevenZ ->
                val files = (0 until sevenZ.numberOfItems)
                    .filterNot { sevenZ.getProperty(it, PropID.IS_FOLDER) as Boolean }
                    .mapNotNull { index ->
                        val name = sevenZ.getStringProperty(index, PropID.PATH).replace('\\', '/')
                        runtimeFile(root, name)?.let { index to it }
                    }
                    .toMap()

                sevenZ.extract(files.keys.toIntArray(), false, object : IArchiveExtractCallback {

                    private var output: OutputStream? = null

                    // 7-Zip also asks for the entries it decodes on the way through a solid block
                    override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                        val file = files[index]?.takeIf { mode == ExtractAskMode.EXTRACT } ?: return null
                        file.parentFile.mkdirs()

                        val stream = file.outputStream()
                        output = stream
                        return ISequentialOutStream { data ->
                            stream.write(data)
                            data.size
                        }
                    }

                    override fun prepareOperation(mode: ExtractAskMode) = Unit

                    override fun setOperationResult(result: ExtractOperationResult) {
                        output?.close()
                        output = null
                        check(result == ExtractOperationResult.OK) { "Unpacking the Ultralight SDK failed: $result" }
                    }

                    override fun setTotal(total: Long) = Unit

                    override fun setCompleted(complete: Long) = Unit

                })
            }
        }
    }

    /**
     * Finds where an entry of the SDK goes, or null if the runtime doesn't need it.
     */
    private fun runtimeFile(root: File, name: String): File? {
        if (RUNTIME_ENTRIES.none { name.startsWith(it) }) {
            return null
        }

        val file = root.resolve(name).canonicalFile
        check(file.toPath().startsWith(root.toPath())) { "$name leaves the runtime directory" }
        return file
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

        const val ULTRALIGHT_VERSION = "2.0.0-beta.2"
        const val UJR_VERSION = "0.3.0"

        private val PLATFORMS = setOf("linux-x64", "mac-arm64", "win-x64")

        private val RUNTIME_ENTRIES = listOf(
            "bin/",
            "resources/",
            "license/",
            "platform/shaders/generated/LICENSE",
            "platform/shaders/generated/headers/glsl/"
        )

        private const val SDK_API = "https://ultralig.ht/api/v1/sdk/download"
        private const val NATIVES_URL =
            "https://maven.ccbluex.net/releases/net/ccbluex/ultralight/ultralight-java-reborn-platform-jni"
    }

}
