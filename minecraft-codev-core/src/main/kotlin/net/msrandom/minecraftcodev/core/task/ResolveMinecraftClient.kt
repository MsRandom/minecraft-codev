package net.msrandom.minecraftcodev.core.task

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.resolve.setupClient
import net.msrandom.minecraftcodev.core.utils.getAsPath
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ResolveMinecraftClient : CachedMinecraftTask() {
    abstract val version: Property<String>
        @Input get

    abstract val output: RegularFileProperty
        @OutputFile get

    init {
        output.convention(
            project.layout.file(
                version.map {
                    temporaryDir.resolve("minecraft-client-$it.jar")
                },
            ),
        )
    }

    @TaskAction
    private fun extract() {
        runBlocking {
            val versionList = cacheParameters.versionList()

            val version = versionList.version(version.get())

            setupClient(
                cacheParameters.directory.asFile
                    .get()
                    .toPath(),
                output.getAsPath(),
                version,
                cacheParameters.isOffline.get(),
            )
        }
    }
}
