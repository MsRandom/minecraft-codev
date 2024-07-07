package net.msrandom.minecraftcodev.core.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.setupClient
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class ResolveMinecraftClient : DefaultTask() {
    abstract val version: Property<String>
        @Input get

    abstract val output: RegularFileProperty
        @OutputFile get

    init {
        output.convention(
            project.layout.file(
                version.map {
                    temporaryDir.resolve("$it.jar")
                },
            ),
        )
    }

    @TaskAction
    private fun extract() {
        val versionList = project.extension<MinecraftCodevExtension>().versionList

        val version = versionList.version(version.get())

        setupClient(project, output.asFile.get().toPath(), version)
    }
}
