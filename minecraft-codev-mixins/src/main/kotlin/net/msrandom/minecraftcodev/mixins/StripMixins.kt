package net.msrandom.minecraftcodev.mixins

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@CacheableTransform
abstract class StripMixins : TransformAction<TransformParameters.None> {
    abstract val inputFile: Provider<FileSystemLocation>
        @InputArtifact
        @PathSensitive(PathSensitivity.NONE)
        get

    abstract val classpath: FileCollection
        @Classpath
        @InputArtifactDependencies
        get

    override fun transform(outputs: TransformOutputs) {
        val input = inputFile.get().asFile.toPath()

        val handler =
            zipFileSystem(input).use {
                val root = it.base.getPath("/")

                mixinListingRules.firstNotNullOfOrNull { rule ->
                    rule.load(root)
                }
            }

        if (handler == null) {
            outputs.file(inputFile)

            return
        }

        val output = outputs.file("${input.nameWithoutExtension}-no-mixins.${input.extension}").toPath()

        input.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)

        zipFileSystem(output).use {
            val root = it.base.getPath("/")
            handler.list(root).forEach { path -> root.resolve(path).deleteExisting() }
            handler.remove(root)
        }
    }
}
