package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import kotlin.io.path.*

@CacheableTask
abstract class RemapJars : DefaultTask() {
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

    abstract val filterMods: Property<Boolean>
        @Input get

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    val outputFiles: FileCollection
        @OutputFiles get() = project.fileTree(outputDirectory)

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    init {
        run {
            targetNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

            filterMods.convention(true)
        }
    }

    @TaskAction
    private fun remapJars(inputs: InputChanges) {
        val remapperExtension = project.extension<MinecraftCodevExtension>().extension<RemapperExtension>()

        for (fileChange in inputs.getFileChanges(inputFiles)) {
            val input = fileChange.file.toPath()

            val output =
                outputDirectory.asFile.get().toPath().resolve(
                    "${input.nameWithoutExtension}-${targetNamespace.get()}.${input.extension}",
                )

            if (fileChange.changeType == ChangeType.REMOVED) {
                output.deleteIfExists()

                continue
            } else if (fileChange.changeType == ChangeType.MODIFIED) {
                output.deleteExisting()
            }

            if (filterMods.get() && !remapperExtension.isMod(input)) {
                output.createSymbolicLinkPointingTo(input)

                return
            }

            project.cacheExpensiveOperation("remapped", mappings + inputFiles + classpath, output) {
                val sourceNamespace = sourceNamespace.get()
                val targetNamespace = targetNamespace.get()

                val mappings = remapperExtension.loadMappings(mappings)

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
    }
}
