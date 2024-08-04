package net.msrandom.minecraftcodev.decompiler.task

import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.decompiler.SourcesGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import java.io.File
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

abstract class Decompile : DefaultTask() {
    abstract val libraryFiles: ConfigurableFileCollection
        @SkipWhenEmpty
        @Classpath
        @InputFiles
        get

    abstract val classpath: ConfigurableFileCollection
        @SkipWhenEmpty
        @CompileClasspath
        @InputFiles
        get

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    val outputFiles: FileCollection
        @OutputFiles get() = project.fileTree(outputDirectory)

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    @TaskAction
    private fun decompile(inputChanges: InputChanges) {
        for (fileChange in inputChanges.getFileChanges(libraryFiles)) {
            val input = fileChange.file.toPath()

            val output = outputDirectory.asFile.get().toPath().resolve("${input.nameWithoutExtension}-sources.${input.extension}")

            if (fileChange.changeType == ChangeType.REMOVED) {
                output.deleteIfExists()

                continue
            } else if (fileChange.changeType == ChangeType.MODIFIED) {
                output.deleteExisting()
            }

            project.cacheExpensiveOperation("decompiled-sources", classpath + project.files(input), output) {
                SourcesGenerator.decompile(input, it, classpath.map(File::toPath))
            }
        }
    }
}
