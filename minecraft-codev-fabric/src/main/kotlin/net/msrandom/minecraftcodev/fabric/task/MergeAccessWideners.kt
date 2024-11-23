package net.msrandom.minecraftcodev.fabric.task

import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerWriter
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class MergeAccessWideners : DefaultTask() {
    abstract val input: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val accessWidenerName: Property<String>
        @Input
        get

    abstract val output: RegularFileProperty
        @OutputFile
        get

    init {
        apply {
            output.convention(
                project.layout.dir(project.provider { temporaryDir }).flatMap {
                    accessWidenerName.map { name ->
                        it.file("$name.accessWidener")
                    }
                },
            )
        }
    }

    @TaskAction
    private fun generate() {
        output.get().asFile.bufferedWriter().use {
            val writer = AccessWidenerWriter()
            val reader = AccessWidenerReader(writer)

            for (accessWidener in input) {
                if (accessWidener.extension.lowercase() != "accesswidener") {
                    // Implies that this is supposed to have specific handling, for example mod Jars to enable transitive Access Wideners in
                    continue
                }

                accessWidener.bufferedReader().use(reader::read)
            }

            it.write(writer.writeString())
        }
    }
}
