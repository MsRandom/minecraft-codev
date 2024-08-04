package net.msrandom.minecraftcodev.remapper.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

abstract class RemapSources : DefaultTask() {
    abstract val mappings: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val sourceNamespace: Property<String>
        @Input get

    abstract val targetNamespace: Property<String>
        @Input get

    abstract val inputFiles: ConfigurableFileCollection
        @InputFiles
        @Classpath
        @SkipWhenEmpty
        get

    abstract val classpath: ConfigurableFileCollection
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
    private fun remap() {
    }
}
