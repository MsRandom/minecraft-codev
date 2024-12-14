package net.msrandom.minecraftcodev.runs.task

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.AssetsIndex
import net.msrandom.minecraftcodev.core.task.CachedMinecraftTask
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.net.URI
import kotlin.io.path.inputStream

abstract class DownloadAssets : CachedMinecraftTask() {
    abstract val version: Property<String>
        @Input get

    abstract val assetsDirectory: DirectoryProperty
        @Internal get

    abstract val resourcesDirectory: DirectoryProperty
        @Internal get

    init {
        assetsDirectory.convention(project.extension<RunsContainer>().assetsDirectory)
        resourcesDirectory.convention(project.extension<RunsContainer>().resourcesDirectory)
    }

    @TaskAction
    fun download() {
        val resourcesDirectory = resourcesDirectory.get()
        val indexesDirectory = assetsDirectory.dir("indexes").get()
        val objectsDirectory = assetsDirectory.dir("objects").get()

        suspend fun downloadFile(
            url: URI,
            sha1: String?,
            output: RegularFile,
        ) {
            val outputPath = output.toPath()

            downloadSuspend(
                url,
                sha1,
                outputPath,
                cacheParameters.isOffline.get(),
            )
        }

        runBlocking {
            val metadata = cacheParameters.versionList().version(version.get())
            val assetIndex = metadata.assetIndex
            val assetIndexJson = indexesDirectory.file("${assetIndex.id}.json").toPath()

            downloadSuspend(
                assetIndex.url,
                assetIndex.sha1,
                assetIndexJson,
                cacheParameters.isOffline.get(),
            )

            val index = assetIndexJson.inputStream().use { Json.decodeFromStream<AssetsIndex>(it) }

            val downloads =
                index.objects.mapNotNull { (name, asset) ->
                    val section = asset.hash.substring(0, 2)

                    val file =
                        if (index.mapToResources) {
                            resourcesDirectory.file(name)
                        } else {
                            objectsDirectory.dir(section).file(asset.hash)
                        }

                    async {
                        if (file.asFile.exists()) {
                            if (checkHashSuspend(file.toPath(), asset.hash)) {
                                return@async
                            } else {
                                file.asFile.delete()
                            }
                        }

                        downloadFile(
                            URI("https", "resources.download.minecraft.net", "/$section/${asset.hash}", null),
                            asset.hash,
                            file,
                        )
                    }
                }

            downloads.awaitAll()
        }
    }
}
