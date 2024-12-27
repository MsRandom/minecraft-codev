package net.msrandom.minecraftcodev.core.task

import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.tryLink
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import kotlin.io.path.deleteIfExists

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
    fun download() {
        val versionList = cacheParameters.versionList()

        val version = versionList.version(version.get())
        val variant = if (server.get()) MinecraftDownloadVariant.ServerMappings else MinecraftDownloadVariant.ClientMappings

        val output = output.getAsPath()

        val downloadPath = downloadMinecraftFile(cacheParameters.directory.getAsPath(), version, variant, cacheParameters.getIsOffline().get())
            ?: throw IllegalArgumentException("${version.id} does not have variant $variant")

        output.deleteIfExists()
        output.tryLink(downloadPath)
    }
}
