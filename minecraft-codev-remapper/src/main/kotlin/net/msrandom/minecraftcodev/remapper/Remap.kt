package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Project
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

abstract class Remap : TransformAction<Remap.Parameters> {
    interface Parameters : TransformParameters {
        val mappings: ConfigurableFileCollection
            @InputFiles get

        val sourceNamespace: Property<String>
            @Input get

        val targetNamespace: Property<String>
            @Input get

        val extraClasspath: ConfigurableFileCollection
            @CompileClasspath get
    }

    abstract val project: Project
        @Internal get

    abstract val input: Provider<FileSystemLocation>
        @InputArtifact
        get

    abstract val dependencies: FileCollection
        @InputArtifactDependencies
        @CompileClasspath
        get

    override fun transform(outputs: TransformOutputs) {
        val remapperExtension = project.extension<RemapperExtension>()

        val input = input.get().asFile.toPath()
        val mappings = remapperExtension.loadMappings(parameters.mappings, project.objects).tree
        val sourceNamespace = parameters.sourceNamespace.get()
        val targetNamespace = parameters.targetNamespace.get()

        val classpath = dependencies + parameters.extraClasspath

        val output = outputs.file("${input.nameWithoutExtension}-${parameters.targetNamespace}.${input.extension}").toPath()

        JarRemapper.remap(
            remapperExtension,
            mappings,
            sourceNamespace,
            targetNamespace,
            input,
            output,
            classpath,
        )
    }
}
