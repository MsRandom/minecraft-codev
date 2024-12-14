package net.msrandom.minecraftcodev.core.task

import net.msrandom.minecraftcodev.core.resolve.setupCommon
import net.msrandom.minecraftcodev.core.utils.getAsPath
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ResolveMinecraftCommon : CachedMinecraftTask() {
    abstract val version: Property<String>
        @Input get

    abstract val output: RegularFileProperty
        @OutputFile get

    init {
        output.convention(
            project.layout.file(
                version.map {
                    temporaryDir.resolve("minecraft-common-$it.jar")
                },
            ),
        )
    }

    @TaskAction
    fun extract() {
        val versionList = cacheParameters.versionList()

        val version = versionList.version(version.get())

        setupCommon(cacheParameters.directory.getAsPath(), version, cacheParameters.isOffline.get(), output.getAsPath())
    }
}
