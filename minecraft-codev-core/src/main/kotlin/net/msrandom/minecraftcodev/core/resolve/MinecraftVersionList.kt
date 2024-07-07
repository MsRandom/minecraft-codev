package net.msrandom.minecraftcodev.core.resolve

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.download
import net.msrandom.minecraftcodev.core.utils.getCacheDirectory
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Project
import java.net.URI
import kotlin.io.path.inputStream

class MinecraftVersionList(private val project: Project, manifest: MinecraftVersionManifest) {
    private val versions = manifest.versions.associateBy(MinecraftVersionManifest.VersionInfo::id)
    private val versionInfoCache = hashMapOf<String, MinecraftVersionMetadata>()

    fun version(version: String) =
        versionInfoCache.computeIfAbsent(version) {
            val versionInfo = versions[version] ?: throw InvalidUserCodeException("Minecraft version $version not found")

            val path =
                getCacheDirectory(project)
                    .resolve("version-metadata")
                    .resolve("${versionInfo.id}.json")

            download(
                project,
                versionInfo.url,
                versionInfo.sha1,
                path,
            )

            path.inputStream().use(json::decodeFromStream)
        }

    companion object {
        fun load(
            project: Project,
            metadataUrl: String,
        ): MinecraftVersionList {
            val versionMetadataUrl = URI.create(metadataUrl)

            val path = getCacheDirectory(project).resolve("version-manifest.json")

            download(project, versionMetadataUrl, null, path, true)

            return MinecraftVersionList(project, path.inputStream().use(json::decodeFromStream))
        }
    }
}
