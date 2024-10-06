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
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo

@CacheableTask
abstract class ResolveMinecraftMappings : DefaultTask() {
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
            val versionList = project.extension<MinecraftCodevExtension>().getVersionList()

            val version = versionList.version(version.get())
            val variant = if (server.get()) MinecraftDownloadVariant.ServerMappings else MinecraftDownloadVariant.ClientMappings

            val output = output.asFile.get().toPath()

            val downloadPath = downloadMinecraftFile(project, version, variant)
                ?: throw IllegalArgumentException("${version.id} does not have variant $variant")

            downloadPath.copyTo(output, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
