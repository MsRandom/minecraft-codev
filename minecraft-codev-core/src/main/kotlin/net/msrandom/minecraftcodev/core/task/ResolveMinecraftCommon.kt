package net.msrandom.minecraftcodev.core.task

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.setupCommon
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class ResolveMinecraftCommon : DefaultTask() {
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
        runBlocking {
            val versionList = project.extension<MinecraftCodevExtension>().getVersionList()

            val version = versionList.version(version.get())

            setupCommon(project, version, output.asFile.get().toPath())
        }
    }
}
