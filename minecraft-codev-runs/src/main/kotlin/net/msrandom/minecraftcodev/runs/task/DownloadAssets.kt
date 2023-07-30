package net.msrandom.minecraftcodev.runs.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import net.msrandom.minecraftcodev.core.AssetsIndex
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.minecraft.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.RunnableBuildOperation
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.local.LazyLocallyAvailableResourceCandidates
import java.net.URI
import javax.inject.Inject
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

abstract class DownloadAssets : DefaultTask() {
    abstract val assetIndexFile: RegularFileProperty
        @InputFile get

    abstract val repositoriesSupplier: RepositoriesSupplier
        @Inject get

    abstract val checksumService: ChecksumService
        @Inject get

    abstract val buildOperationExecutor: BuildOperationExecutor
        @Inject get

    fun useAssetIndex(assetIndex: MinecraftVersionMetadata.AssetIndex) {
        val path = assetIndexFile.asFile.get().toPath()

        path.parent.createDirectories()

        path.outputStream().use { output ->
            Json.encodeToStream(assetIndex, output)
        }
    }

    @TaskAction
    fun download() {
        val assetIndex = assetIndexFile.asFile.get().inputStream().use {
            Json.decodeFromStream<MinecraftVersionMetadata.AssetIndex>(it)
        }

        val codev = project.extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RunsContainer::class.java)
        val assetsDirectory = codev.assetsDirectory
        val resourcesDirectory = codev.resourcesDirectory.get()
        val indexesDirectory = assetsDirectory.dir("indexes").get()
        val objectsDirectory = assetsDirectory.dir("objects").get()

        val resourceAccessor = repositoriesSupplier
            .get()
            .filterIsInstance<MinecraftRepositoryImpl>()
            .map(MinecraftRepositoryImpl::createResolver)
            .firstOrNull()
            ?.resourceAccessor ?: throw UnsupportedOperationException("No minecraft repository defined")

        fun downloadFile(url: URI, sha1: String?, output: RegularFile) = resourceAccessor.getResource(
            ExternalResourceName(url),
            sha1,
            output.asFile.toPath(),
            LazyLocallyAvailableResourceCandidates({
                if (output.asFile.exists()) {
                    listOf(output.asFile)
                } else {
                    emptyList()
                }
            }, checksumService)
        )!!.file

        val assetIndexJson = downloadFile(assetIndex.url, assetIndex.sha1, indexesDirectory.file("${assetIndex.id}.json"))

        val index = assetIndexJson.inputStream().use { Json.decodeFromStream<AssetsIndex>(it) }

        buildOperationExecutor.runAll<RunnableBuildOperation> {
            for ((name, asset) in index.objects) {
                val section = asset.hash.substring(0, 2)

                val file = if (index.mapToResources) {
                    resourcesDirectory.file(name)
                } else {
                    objectsDirectory.dir(section).file(asset.hash)
                }

                if (file.asFile.exists()) {
                    if (checksumService.sha1(file.asFile).toString() == asset.hash) {
                        // Early guard to avoid flooding the build queue
                        continue
                    } else {
                        // So the resource accessor doesn't check hash again
                        file.asFile.delete()
                    }

                    continue
                }

                it.add(object : RunnableBuildOperation {
                    override fun description() =
                        BuildOperationDescriptor.displayName("Download or Check cached $file")

                    override fun run(context: BuildOperationContext) {
                        downloadFile(
                            URI("https", "resources.download.minecraft.net", "/$section/${asset.hash}", null),
                            asset.hash,
                            file
                        )
                    }
                })
            }
        }
    }
}
