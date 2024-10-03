package net.msrandom.minecraftcodev.remapper.task

import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.loadMappings
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

@CacheableTransform
abstract class Remap : TransformAction<Remap.Parameters> {
    abstract class Parameters : TransformParameters {
        abstract val mappings: ConfigurableFileCollection
            @InputFiles
            @PathSensitive(PathSensitivity.NONE)
            get

        abstract val sourceNamespace: Property<String>
            @Input get

        abstract val targetNamespace: Property<String>
            @Input get

        abstract val extraFiles: MapProperty<String, File>
            @InputFiles get

        init {
            apply {
                targetNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
            }
        }
    }

    abstract val execOperations: ExecOperations
        @Inject get

    abstract val inputFile: Provider<FileSystemLocation>
        @InputArtifact get

    abstract val classpath: FileCollection
        @Classpath
        @InputArtifactDependencies
        get

    override fun transform(outputs: TransformOutputs) {
        val input = inputFile.get().asFile

        val sourceNamespace = parameters.sourceNamespace.get()
        val targetNamespace = parameters.targetNamespace.get()

        val output = outputs.file("${input.nameWithoutExtension}-$targetNamespace.${input.extension}")

        val mappings = loadMappings(parameters.mappings, execOperations, parameters.extraFiles.get())

        JarRemapper.remap(
            mappings,
            sourceNamespace,
            targetNamespace,
            input.toPath(),
            output.toPath(),
            classpath,
        )
    }
}
