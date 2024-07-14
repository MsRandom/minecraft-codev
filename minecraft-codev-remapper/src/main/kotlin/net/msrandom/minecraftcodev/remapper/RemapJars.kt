package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

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
        get

    abstract val classpath: ConfigurableFileCollection
        @CompileClasspath
        @InputFiles
        get

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    init {
        run {
            targetNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
        }
    }

    @TaskAction
    private fun remapJars() {
        val remapperExtension = project.extension<RemapperExtension>()

        for (input in inputFiles) {
            val input = input.toPath()

            if (!remapperExtension.isMod(input)) {
                outputs.file(input)

                return
            }

            val sourceNamespace = sourceNamespace.get()
            val targetNamespace = targetNamespace.get()

            val mappings = remapperExtension.loadMappings(mappings)

            val output = outputDirectory.asFile.get().toPath().resolve("${input.nameWithoutExtension}-${targetNamespace}.${input.extension}")

            outputs.file(output)

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
