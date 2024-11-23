package net.msrandom.minecraftcodev.core.resolve

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.msrandom.minecraftcodev.core.resolve.bundled.ServerExtractor
import net.msrandom.minecraftcodev.core.resolve.legacy.ServerFixer
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

fun getExtractionState(
    cacheDirectory: Path,
    manifest: MinecraftVersionMetadata,
    isOffline: Boolean,
): ServerExtractionResult? {
    val extractedServerData =
        cacheDirectory
            .resolve("extracted-servers")
            .resolve(manifest.id)

    val extractedJar = extractedServerData.resolve("server.jar")
    val libraries = extractedServerData.resolve("libraries.txt")
    val bundledMark = extractedServerData.resolve("bundle")

    if (extractedJar.exists() && libraries.exists()) {
        val commonLibraries = libraries.readLines()
        val isBundled = bundledMark.exists()

        return ServerExtractionResult(extractedJar, isBundled, commonLibraries)
    }

    val serverJar =
        downloadMinecraftFile(
            cacheDirectory,
            manifest,
            MinecraftDownloadVariant.Server,
            isOffline,
        ) ?: return null

    val temporaryServer = Files.createTempFile("server-", ".tmp.jar")

    val commonLibraries: List<String>
    val isBundled: Boolean

    zipFileSystem(serverJar).use { serverFs ->
        val librariesPath = serverFs.getPath("META-INF/libraries.list")

        if (librariesPath.exists()) {
            // New server Jar, just extract it and populate the library list
            isBundled = true
            commonLibraries =
                ServerExtractor.extract(
                    manifest.id,
                    temporaryServer,
                    serverFs,
                    librariesPath,
                )
        } else {
            // Old server Jar, strip the libraries out manually
            isBundled = false
            serverJar.copyTo(
                temporaryServer,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            commonLibraries =
                ServerFixer.removeLibraries(
                    manifest,
                    temporaryServer,
                    serverFs,
                    downloadMinecraftClient(cacheDirectory, manifest, isOffline),
                )
        }
    }

    extractedServerData.createDirectories()

    temporaryServer.copyTo(
        extractedJar,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )

    libraries.writeLines(commonLibraries)

    if (isBundled) {
        Files.createFile(bundledMark)
    }

    return ServerExtractionResult(extractedJar, isBundled, commonLibraries)
}

data class ServerExtractionResult(val result: Path, val isBundled: Boolean, val libraries: List<String>)
