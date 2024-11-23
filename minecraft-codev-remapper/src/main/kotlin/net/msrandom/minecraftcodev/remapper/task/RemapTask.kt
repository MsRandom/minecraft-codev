package net.msrandom.minecraftcodev.remapper.task

import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.loadMappings
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class RemapTask : DefaultTask() {
    abstract val inputFile: RegularFileProperty
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val sourceNamespace: Property<String>
        @Input get

    abstract val targetNamespace: Property<String>
        @Input get

    abstract val mappings: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        @SkipWhenEmpty
        get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles
        @CompileClasspath
        get

    abstract val extraFiles: MapProperty<String, File>
        @Optional
        @Input
        get

    abstract val execOperations: ExecOperations
        @Inject get

    abstract val outputFile: RegularFileProperty
        @OutputFile get

    init {
        run {
            outputFile.convention(
                project.layout.file(
                    inputFile.flatMap { file ->
                        targetNamespace.map { namespace ->
                            temporaryDir.resolve("${file.asFile.nameWithoutExtension}-$namespace.${file.asFile.extension}")
                        }
                    },
                ),
            )

            sourceNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
        }
    }

    @TaskAction
    fun remap() {
        val mappings = loadMappings(mappings, execOperations, extraFiles.get())

        JarRemapper.remap(
            mappings,
            sourceNamespace.get(),
            targetNamespace.get(),
            inputFile.getAsPath(),
            outputFile.getAsPath(),
            classpath,
        )
    }
}
