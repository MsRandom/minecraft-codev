package net.msrandom.minecraftcodev.core.task

import com.google.common.hash.Hashing
import com.google.common.util.concurrent.AtomicDouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.AssetsIndex
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import com.google.common.io.Files as GuavaFiles

abstract class DownloadAssets : DefaultTask() {
    abstract val assetIndex: MinecraftVersionMetadata.AssetIndex
        @Input get

    private val sizeCategories = arrayOf(" Bytes", "KB", "MB", "GB", "TB", "PB", "EB")
    private val decimalFormat = DecimalFormat("#.##")

    private fun formatSize(size: Long): String {
        var sizeCategory = 0
        var current = size
        while (current >= 1024) {
            current /= 1024
            sizeCategory++
        }

        var minimumSizeForCategory = 1L
        repeat(sizeCategory) {
            minimumSizeForCategory *= 1024
        }

        val formattedSize = size / minimumSizeForCategory.toDouble()
        return decimalFormat.format(formattedSize) + (sizeCategories.getOrNull(sizeCategory) ?: throw UnsupportedOperationException("Invalid size: $size"))
    }

    private fun downloadToFile(path: Path, url: () -> URL): InputStream {
        val input = if (path.exists()) {
            path.inputStream()
        } else {
            val stream = url().openStream().buffered()
            try {
                stream.mark(Int.MAX_VALUE)
                path.parent.createDirectories()
                Files.copy(stream, path)
                stream.reset()
            } catch (exception: IOException) {
                stream.close()
                throw exception
            }
            stream
        }

        return input
    }

    @TaskAction
    fun download() {
        val codev = project.plugins.getPlugin(MinecraftCodevPlugin::class.java)
        val assetsDirectory = codev.assets
        val resourcesDirectory = codev.resources
        val indexesDirectory = assetsDirectory.resolve("indexes")
        val objectsDirectory = assetsDirectory.resolve("objects")
        val toDownload = assetIndex.totalSize.toDouble()
        val downloaded = AtomicLong()
        val lastLogPercentage = AtomicDouble()
        val started = AtomicBoolean()
        val totalDownload = formatSize(assetIndex.totalSize)

        logger.debug("Testing assets for asset index ${assetIndex.id}")

        val assetIndexJson = downloadToFile(indexesDirectory.resolve("${assetIndex.id}.json")) {
            assetIndex.url.toURL()
        }

        val index = assetIndexJson.use { Json.decodeFromStream<AssetsIndex>(it) }
        runBlocking {
            for ((name, asset) in index.objects) {
                val section = asset.hash.substring(0, 2)
                val file = if (index.mapToResources) {
                    resourcesDirectory.resolve(name)
                } else {
                    objectsDirectory.resolve(section).resolve(asset.hash)
                }

                withContext(Dispatchers.IO) {
                    logger.debug("Testing asset $name")

                    val exists = file.exists()

                    @Suppress("DEPRECATION")
                    val valid = exists && GuavaFiles.asByteSource(file.toFile()).hash(Hashing.sha1()).toString() == asset.hash
                    if (!valid) {
                        if (!exists && !started.get()) {
                            logger.lifecycle("")
                            logger.lifecycle("Downloading necessary assets from asset index ${assetIndex.id}, which is approximately $totalDownload in total, this could take a while...")
                            started.set(true)
                        }

                        logger.debug("Downloading $name")

                        URL("https://resources.download.minecraft.net/$section/${asset.hash}").openStream().use {
                            file.parent?.createDirectories()
                            Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING)

                            val downloadedSize = downloaded.addAndGet(Files.size(file))
                            val newPercentage = downloadedSize / toDownload
                            if (newPercentage - lastLogPercentage.get() > 0.01) {
                                lastLogPercentage.set(newPercentage)

                                if (started.get()) {
                                    logger.lifecycle("${formatSize(downloadedSize)}/$totalDownload downloaded, ${decimalFormat.format(newPercentage * 100)}%")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
