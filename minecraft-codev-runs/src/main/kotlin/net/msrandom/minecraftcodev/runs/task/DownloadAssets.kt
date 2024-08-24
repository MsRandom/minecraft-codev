package net.msrandom.minecraftcodev.runs.task

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.AssetsIndex
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.checkHash
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.net.URI
import kotlin.io.path.inputStream

abstract class DownloadAssets : DefaultTask() {
    abstract val version: Property<String>
        @Input get

    @TaskAction
    private fun download() {
        val codev = project.extension<MinecraftCodevExtension>()
        val runs = codev.extension<RunsContainer>()
        val assetsDirectory = runs.assetsDirectory
        val resourcesDirectory = runs.resourcesDirectory.get()
        val indexesDirectory = assetsDirectory.dir("indexes").get()
        val objectsDirectory = assetsDirectory.dir("objects").get()

        suspend fun downloadFile(
            url: URI,
            sha1: String?,
            output: RegularFile,
        ) {
            val outputPath = output.asFile.toPath()

            net.msrandom.minecraftcodev.core.utils.download(
                project,
                url,
                sha1,
                outputPath,
            )
        }

        runBlocking {
            val metadata = codev.getVersionList().version(version.get())
            val assetIndex = metadata.assetIndex
            val assetIndexJson = indexesDirectory.file("${assetIndex.id}.json").asFile.toPath()

            net.msrandom.minecraftcodev.core.utils.download(
                project,
                assetIndex.url,
                assetIndex.sha1,
                assetIndexJson,
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
                            if (checkHash(file.asFile.toPath(), asset.hash)) {
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
