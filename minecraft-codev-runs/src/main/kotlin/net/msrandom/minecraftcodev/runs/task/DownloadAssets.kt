package net.msrandom.minecraftcodev.runs.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.AssetsIndex
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.RunnableBuildOperation
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.local.LazyLocallyAvailableResourceCandidates
import java.net.URI

abstract class DownloadAssets : DefaultTask() {
    abstract val assetIndex: Property<MinecraftVersionMetadata.AssetIndex>
        @Input get

    abstract val repositoriesSupplier: RepositoriesSupplier
        @Internal get

    abstract val checksumService: ChecksumService
        @Internal get

    abstract val buildOperationExecutor: BuildOperationExecutor
        @Internal get

    @TaskAction
    fun download() {
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
            LazyLocallyAvailableResourceCandidates({ listOf(output.asFile) }, checksumService)
        )!!.file

        val assetIndexJson = downloadFile(assetIndex.get().url, assetIndex.get().sha1, indexesDirectory.file("${assetIndex.get().id}.json"))

        val index = assetIndexJson.inputStream().use { Json.decodeFromStream<AssetsIndex>(it) }

        buildOperationExecutor.runAll<RunnableBuildOperation> {
            for ((name, asset) in index.objects) {
                val section = asset.hash.substring(0, 2)
                it.add(object : RunnableBuildOperation {
                    val file = if (index.mapToResources) {
                        resourcesDirectory.file(name)
                    } else {
                        objectsDirectory.dir(section).file(asset.hash)
                    }

                    override fun description() =
                        BuildOperationDescriptor.displayName("Download or Check cached $file")

                    override fun run(context: BuildOperationContext) {
                        downloadFile(
                            URI("https", "resources.download.minecraft.net", "$section/${asset.hash}", null),
                            asset.hash,
                            file
                        )
                    }
                })
            }
        }
    }
}
