package net.msrandom.minecraftcodev.remapper

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
abstract class RemapAction : TransformAction<RemapAction.Parameters> {
    abstract class Parameters : TransformParameters {
        abstract val mappings: ConfigurableFileCollection
            @InputFiles
            @PathSensitive(PathSensitivity.NONE)
            get

        abstract val sourceNamespace: Property<String>
            @Input get

        abstract val targetNamespace: Property<String>
            @Input get

        abstract val extraClasspath: ConfigurableFileCollection
            @CompileClasspath
            @InputFiles
            get

        abstract val filterMods: Property<Boolean>
            @Input get

        abstract val extraFiles: MapProperty<String, File>
            @Optional
            @Input
            get

        init {
            apply {
                targetNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

                filterMods.convention(true)
            }
        }
    }

    abstract val execOperations: ExecOperations
        @Inject get

    abstract val inputFile: Provider<FileSystemLocation>
        @InputArtifact
        @PathSensitive(PathSensitivity.NONE)
        get

    abstract val classpath: FileCollection
        @Classpath
        @InputArtifactDependencies
        get

    override fun transform(outputs: TransformOutputs) {
        val input = inputFile.get().asFile

        if (parameters.mappings.isEmpty || parameters.filterMods.get() && !isMod(input.toPath())) {
            outputs.file(inputFile)

            return
        }

        val sourceNamespace = parameters.sourceNamespace.get()
        val targetNamespace = parameters.targetNamespace.get()

        println("Remapping mod $input from $sourceNamespace to $targetNamespace")

        val output = outputs.file("${input.nameWithoutExtension}-$targetNamespace.${input.extension}")

        val mappings = loadMappings(parameters.mappings, execOperations, parameters.extraFiles.getOrElse(emptyMap()))

        JarRemapper.remap(
            mappings,
            sourceNamespace,
            targetNamespace,
            input.toPath(),
            output.toPath(),
            classpath + parameters.extraClasspath,
        )
    }
}
