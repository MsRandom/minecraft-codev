package net.msrandom.minecraftcodev.remapper

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*

@CacheableTransform
abstract class RemapSources : TransformAction<RemapSources.Parameters> {
    abstract class Parameters : TransformParameters {
        abstract val mappings: RegularFileProperty
            @InputFile
            @PathSensitive(PathSensitivity.NONE)
            get

        abstract val sourceNamespace: Property<String>
            @Input get

        abstract val targetNamespace: Property<String>
            @Input get

        abstract val classpath: ConfigurableFileCollection
            @CompileClasspath
            @InputFiles
            get

        init {
            apply {
                targetNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
            }
        }
    }

    abstract val inputFile: Provider<FileSystemLocation>
        @InputArtifact
        @PathSensitive(PathSensitivity.NONE)
        get

    override fun transform(outputs: TransformOutputs) {
        outputs.file(inputFile)
    }
}
