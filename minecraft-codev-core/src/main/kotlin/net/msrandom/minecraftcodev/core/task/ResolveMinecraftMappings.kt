package net.msrandom.minecraftcodev.core.task

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.utils.getAsPath
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo

@CacheableTask
abstract class ResolveMinecraftMappings : CachedMinecraftTask() {
    abstract val version: Property<String>
        @Input get

    abstract val server: Property<Boolean>
        @Input get

    abstract val output: RegularFileProperty
        @OutputFile get

    init {
        output.convention(
            project.layout.file(
                version.zip(server, ::Pair).map { (v, s) ->
                    val variant = if (s) "server" else "client"

                    temporaryDir.resolve("minecraft-$variant-mappings-$v.txt")
                },
            ),
        )
    }

    @TaskAction
    private fun download() {
        runBlocking {
            val versionList = cacheParameters.versionList()

            val version = versionList.version(version.get())
            val variant = if (server.get()) MinecraftDownloadVariant.ServerMappings else MinecraftDownloadVariant.ClientMappings

            val output = output.getAsPath()

            val downloadPath = downloadMinecraftFile(cacheParameters.directory.getAsPath(), version, variant, cacheParameters.isOffline.get())
                ?: throw IllegalArgumentException("${version.id} does not have variant $variant")

            downloadPath.copyTo(output, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
