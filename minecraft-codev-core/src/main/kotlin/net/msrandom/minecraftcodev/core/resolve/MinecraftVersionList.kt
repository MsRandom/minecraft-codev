package net.msrandom.minecraftcodev.core.resolve

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.download
import org.gradle.api.InvalidUserCodeException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.ThreadSafe
import kotlin.io.path.inputStream

@ThreadSafe
class MinecraftVersionList(private val cacheDirectory: Path, manifest: MinecraftVersionManifest, private val isOffline: Boolean) {
    private val versions = manifest.versions.associateBy(MinecraftVersionManifest.VersionInfo::id)
    private val versionInfoCache = ConcurrentHashMap<String, MinecraftVersionMetadata>()

    fun version(version: String) = versionInfoCache.computeIfAbsent(version) {
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

        path.inputStream().use(json::decodeFromStream)
    }

    companion object {
        fun load(
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
