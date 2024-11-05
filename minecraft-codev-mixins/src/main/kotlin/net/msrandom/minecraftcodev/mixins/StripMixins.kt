package net.msrandom.minecraftcodev.mixins

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.utils.toPath
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

    override fun transform(outputs: TransformOutputs) = runBlocking {
        val input = inputFile.get().toPath()

        val handler =
            zipFileSystem(input).use {
                val root = it.getPath("/")

                mixinListingRules.firstNotNullOfOrNull { rule ->
                    rule.load(root)
                }
            }

        if (handler == null) {
            outputs.file(inputFile)

            return@runBlocking
        }

        val output = outputs.file("${input.nameWithoutExtension}-no-mixins.${input.extension}").toPath()

        input.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)

        zipFileSystem(output).use {
            val root = it.getPath("/")
            handler.list(root).forEach { path -> root.resolve(path).deleteExisting() }
            handler.remove(root)
        }
    }
}
