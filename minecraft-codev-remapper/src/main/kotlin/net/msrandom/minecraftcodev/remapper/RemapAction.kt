package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.core.utils.getAsPath
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.model.ObjectFactory
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

        abstract val cacheDirectory: DirectoryProperty
            @Internal get

        init {
            apply {
                targetNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

                filterMods.convention(true)
            }
        }
    }

    abstract val execOperations: ExecOperations
        @Inject get

    abstract val objectFactory: ObjectFactory
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

        val output = outputs.file("${input.nameWithoutExtension}-$targetNamespace.${input.extension}")

        val extraFiles = parameters.extraFiles.getOrElse(emptyMap())

        val cacheKey = objectFactory.fileCollection()

        cacheKey.from(classpath)
        cacheKey.from(extraFiles.values)
        cacheKey.from(parameters.mappings)
        cacheKey.from(inputFile.get().asFile)

        cacheExpensiveOperation(parameters.cacheDirectory.getAsPath(), "remap", cacheKey, output.toPath()) {
            println("Remapping mod $input from $sourceNamespace to $targetNamespace")

            val mappings = loadMappings(parameters.mappings, execOperations, extraFiles)

            JarRemapper.remap(
                mappings,
                sourceNamespace,
                targetNamespace,
                input.toPath(),
                it,
                classpath + parameters.extraClasspath,
            )
        }
    }
}
