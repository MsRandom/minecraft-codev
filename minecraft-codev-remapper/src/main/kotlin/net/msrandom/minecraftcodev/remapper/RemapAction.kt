package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.format.Tiny2Reader
import net.fabricmc.mappingio.format.Tiny2Writer
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.task.CachedMinecraftParameters
import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.remapper.task.LOAD_MAPPINGS_OPERATION_VERSION
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.nio.file.Files
import javax.inject.Inject
import kotlin.io.path.bufferedWriter
import kotlin.io.path.reader

@CacheableTransform
abstract class RemapAction : TransformAction<RemapAction.Parameters> {
    abstract class Parameters : TransformParameters {
        abstract val mappings: RegularFileProperty
            @InputFile
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

        abstract val modFiles: ConfigurableFileCollection
            @PathSensitive(PathSensitivity.NONE)
            @InputFiles
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

        if (parameters.filterMods.get() && input !in parameters.modFiles && !isMod(input.toPath())) {
            outputs.file(inputFile)

            return
        }

        val sourceNamespace = parameters.sourceNamespace.get()
        val targetNamespace = parameters.targetNamespace.get()

        val output = outputs.file("${input.nameWithoutExtension}-$targetNamespace.${input.extension}")

        val cacheKey = objectFactory.fileCollection()

        cacheKey.from(classpath)
        cacheKey.from(parameters.mappings)
        cacheKey.from(inputFile.get().asFile)

        cacheExpensiveOperation(parameters.cacheDirectory.getAsPath(), "remap-$REMAP_OPERATION_VERSION", cacheKey, output.toPath()) { (output) ->
            println("Remapping mod $input from $sourceNamespace to $targetNamespace")

            val mappings = MemoryMappingTree()

            Tiny2Reader.read(parameters.mappings.getAsPath().reader(), mappings)

            JarRemapper.remap(
                mappings,
                sourceNamespace,
                targetNamespace,
                input.toPath(),
                output,
                classpath + parameters.extraClasspath,
            )
        }
    }
}
