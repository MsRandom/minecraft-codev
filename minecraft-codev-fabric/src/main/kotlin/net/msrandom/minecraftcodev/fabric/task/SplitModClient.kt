package net.msrandom.minecraftcodev.fabric.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.InputChanges

@CacheableTask
abstract class SplitModClient : DefaultTask() {
    abstract val inputFile: RegularFileProperty
        @InputFile
        @Classpath
        get

    abstract val outputFile: RegularFileProperty
        @OutputFile get

    init {
        outputFile.convention(
            project.layout.file(
                inputFile.map {
                    temporaryDir.resolve("${it.asFile.nameWithoutExtension}-client.${it.asFile.extension}")
                },
            ),
        )
    }

    @TaskAction
    private fun split() {

    }
}
