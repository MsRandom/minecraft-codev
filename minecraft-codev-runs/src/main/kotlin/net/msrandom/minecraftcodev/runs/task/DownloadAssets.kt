package net.msrandom.minecraftcodev.runs.task

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import net.msrandom.minecraftcodev.core.AssetsIndex
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.checkHash
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.net.URI
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

abstract class DownloadAssets : DefaultTask() {
    abstract val assetIndexFile: RegularFileProperty
        @InputFile get

    fun useAssetIndex(assetIndex: MinecraftVersionMetadata.AssetIndex) {
        val path = assetIndexFile.asFile.get().toPath()

        path.parent.createDirectories()

        path.outputStream().use { output ->
            Json.encodeToStream(assetIndex, output)
        }
    }

    @TaskAction
    private fun download() {
        val assetIndex =
            assetIndexFile.asFile.get().inputStream().use {
                Json.decodeFromStream<MinecraftVersionMetadata.AssetIndex>(it)
            }

        val codev = project.extension<MinecraftCodevExtension>().extension<RunsContainer>()
        val assetsDirectory = codev.assetsDirectory
        val resourcesDirectory = codev.resourcesDirectory.get()
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

        val assetIndexJson = indexesDirectory.file("${assetIndex.id}.json").asFile.toPath()

        runBlocking {
            net.msrandom.minecraftcodev.core.utils.download(
                project,
                assetIndex.url,
                assetIndex.sha1,
                assetIndexJson,
            )

            val index = assetIndexJson.inputStream().use { Json.decodeFromStream<AssetsIndex>(it) }

            coroutineScope {
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
}
