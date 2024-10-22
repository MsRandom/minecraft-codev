package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.utils.download
import java.nio.file.Path

enum class MinecraftDownloadVariant(val download: String) {
    Server("server"),
    Client("client"),
    ServerMappings("server_mappings"),
    ClientMappings("client_mappings");

    override fun toString() = download
}

suspend fun downloadMinecraftClient(
    cacheDirectory: Path,
    metadata: MinecraftVersionMetadata,
    isOffline: Boolean,
) = downloadMinecraftFile(
    cacheDirectory,
    metadata.id,
    MinecraftDownloadVariant.Client.download,
    metadata.downloads.getValue(MinecraftDownloadVariant.Client.download),
    isOffline,
)

suspend fun downloadMinecraftFile(
    cacheDirectory: Path,
    metadata: MinecraftVersionMetadata,
    variant: MinecraftDownloadVariant,
    isOffline: Boolean,
) = metadata.downloads[variant.download]?.let {
    downloadMinecraftFile(
        cacheDirectory,
        metadata.id,
        variant.download,
        it,
        isOffline,
    )
}

private suspend fun downloadMinecraftFile(
    cacheDirectory: Path,
    version: String,
    downloadName: String,
    variantDownload: MinecraftVersionMetadata.Download,
    isOffline: Boolean,
): Path {
    val downloadPath = minecraftFilePath(cacheDirectory, version, downloadName, variantDownload)

    download(
        variantDownload.url,
        variantDownload.sha1,
        downloadPath,
        isOffline,
    )

    return downloadPath
}

private fun minecraftFilePath(
    cacheDirectory: Path,
    version: String,
    downloadName: String,
    variantDownload: MinecraftVersionMetadata.Download,
) = cacheDirectory
    .resolve("download-cache")
    .resolve(variantDownload.sha1)
    .resolve("$downloadName-$version.${variantDownload.url.path.substringAfterLast('.')}")
