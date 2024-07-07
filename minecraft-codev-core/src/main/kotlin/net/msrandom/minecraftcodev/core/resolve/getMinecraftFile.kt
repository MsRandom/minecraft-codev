package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.download
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.getCacheDirectory
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.nio.file.Path

enum class MinecraftDownloadVariant(val download: String) {
    Server("server"),
    Client("client"),
    ServerMappings("server_mappings"),
    ClientMappings("client_mappings"),
}

fun downloadMinecraftClient(
    project: Project,
    metadata: MinecraftVersionMetadata,
) = downloadMinecraftFile(
    project,
    metadata.id,
    MinecraftDownloadVariant.Client.download,
    metadata.downloads.getValue(MinecraftDownloadVariant.Client.download),
)

fun downloadMinecraftFile(
    project: Project,
    metadata: MinecraftVersionMetadata,
    variant: MinecraftDownloadVariant,
    output: Path? = null,
) = metadata.downloads[variant.download]?.let {
    downloadMinecraftFile(
        project,
        metadata.id,
        variant.download,
        it,
        output,
    )
}

private fun downloadMinecraftFile(
    project: Project,
    version: String,
    downloadName: String,
    variantDownload: MinecraftVersionMetadata.Download,
    output: Path? = null,
): Path {
    val downloadPath = output ?: minecraftFilePath(project, version, downloadName, variantDownload)

    download(
        project,
        variantDownload.url,
        variantDownload.sha1,
        downloadPath,
    )

    return downloadPath
}

fun minecraftFilePath(
    project: Project,
    version: String,
    variant: MinecraftDownloadVariant,
): Provider<RegularFile> {
    val versionList = project.extension<MinecraftCodevExtension>().versionList

    val fileProvider =
        project.provider {
            val variantDownload =
                versionList.version(version).downloads[variant.download]
                    ?: throw InvalidUserCodeException(
                        "Tried to access ${variant.download} download for version $version, " +
                            "but it does not have a ${variant.download} download.",
                    )

            minecraftFilePath(project, version, variant.download, variantDownload).toFile()
        }

    return project.layout.file(fileProvider)
}

private fun minecraftFilePath(
    project: Project,
    version: String,
    downloadName: String,
    variantDownload: MinecraftVersionMetadata.Download,
) = getCacheDirectory(project)
    .resolve("download-cache")
    .resolve(variantDownload.sha1)
    .resolve("$downloadName-$version.${variantDownload.url.path.substringAfterLast('.')}")
