package net.msrandom.minecraftcodev.core.resolve

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.download
import org.gradle.api.InvalidUserCodeException
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.inputStream

class MinecraftVersionList(private val cacheDirectory: Path, manifest: MinecraftVersionManifest, private val isOffline: Boolean) {
    private val versions = manifest.versions.associateBy(MinecraftVersionManifest.VersionInfo::id)
    private val versionInfoCache = hashMapOf<String, MinecraftVersionMetadata>()

    suspend fun version(version: String): MinecraftVersionMetadata {
        val it = versionInfoCache[version]

        if (it != null) {
            return it
        }

        val versionInfo = versions[version] ?: throw InvalidUserCodeException("Minecraft version $version not found")

        val path =
            cacheDirectory
                .resolve("version-metadata")
                .resolve("${versionInfo.id}.json")

        download(
            versionInfo.url,
            versionInfo.sha1,
            path,
            isOffline,
        )

        val metadata: MinecraftVersionMetadata = path.inputStream().use(json::decodeFromStream)

        versionInfoCache[version] = metadata

        return metadata
    }

    companion object {
        suspend fun load(
            cacheDirectory: Path,
            metadataUrl: String,
            isOffline: Boolean,
        ): MinecraftVersionList {
            val versionMetadataUrl = URI.create(metadataUrl)

            val path = cacheDirectory.resolve("version-manifest.json")

            download(versionMetadataUrl, null, path, isOffline = isOffline, alwaysRefresh = true)

            return MinecraftVersionList(cacheDirectory, path.inputStream().use(json::decodeFromStream), isOffline)
        }
    }
}
