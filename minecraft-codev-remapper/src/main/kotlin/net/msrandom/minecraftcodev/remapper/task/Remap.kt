package net.msrandom.minecraftcodev.remapper.task

import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.loadMappings
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import kotlin.io.path.*

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

        abstract val clientMappings: RegularFileProperty
            @Optional
            @InputFile
            @PathSensitive(PathSensitivity.NONE)
            get

        abstract val javaExecutable: Property<String>
            @Optional
            @Input
            get

        init {
            apply {
                targetNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
            }
        }
    }

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

        val mappings = loadMappings(parameters.mappings/*, parameters.clientMappings, parameters.javaExecutable*/)

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
