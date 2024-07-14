package net.msrandom.minecraftcodev.core.task

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class DownloadMinecraftMappings : DefaultTask() {
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

                    temporaryDir.resolve("$variant-mappings-$v.txt")
                },
            ),
        )
    }

    @TaskAction
    private fun download() {
        runBlocking {
            val versionList = project.extension<MinecraftCodevExtension>().getVersionList()

            val version = versionList.version(version.get())
            val variant = if (server.get()) MinecraftDownloadVariant.ServerMappings else MinecraftDownloadVariant.ClientMappings

            downloadMinecraftFile(project, version, variant, output.asFile.get().toPath())
        }
    }
}
