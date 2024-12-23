package net.msrandom.minecraftcodev.decompiler.task

import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.decompiler.SourcesGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.io.File

@CacheableTask
abstract class Decompile : DefaultTask() {
    abstract val inputFile: RegularFileProperty
        @Classpath
        @InputFile
        get

    abstract val classpath: ConfigurableFileCollection
        @CompileClasspath
        @InputFiles
        get

    abstract val outputFile: RegularFileProperty
        @OutputFile get

    init {
        outputFile.convention(
            project.layout.file(
                inputFile.map {
                    temporaryDir.resolve("${it.asFile.nameWithoutExtension}-sources.${it.asFile.extension}")
                },
            ),
        )
    }

    @TaskAction
    fun decompile() {
        val input = inputFile.getAsPath()
        val output = outputFile.getAsPath()

        SourcesGenerator.decompile(input, output, classpath.map(File::toPath))
    }
}
