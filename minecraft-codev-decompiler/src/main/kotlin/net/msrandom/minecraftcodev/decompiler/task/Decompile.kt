package net.msrandom.minecraftcodev.decompiler.task

import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.getLocalCacheDirectoryProvider
import net.msrandom.minecraftcodev.decompiler.SourcesGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.io.File

const val DECOMPILE_OPERATION_VERSION = 1

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

    abstract val cacheDirectory: DirectoryProperty
        @Internal get

    init {
        outputFile.convention(
            project.layout.file(
                inputFile.map {
                    temporaryDir.resolve("${it.asFile.nameWithoutExtension}-sources.${it.asFile.extension}")
                },
            ),
        )

        cacheDirectory.set(getLocalCacheDirectoryProvider(project))
    }

    @TaskAction
    fun decompile() {
        val input = inputFile.getAsPath()

        cacheExpensiveOperation(cacheDirectory.getAsPath(), "decompile-$DECOMPILE_OPERATION_VERSION", listOf(input.toFile()), outputFile.getAsPath()) { (output) ->
            SourcesGenerator.decompile(input, output, classpath.map(File::toPath))
        }
    }
}
