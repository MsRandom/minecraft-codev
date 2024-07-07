package net.msrandom.minecraftcodev.includes

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.Project
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import kotlin.io.path.copyTo
import kotlin.io.path.deleteExisting

abstract class ExtractIncludes : TransformAction<TransformParameters.None> {
    abstract val project: Project
        @Internal get

    abstract val inputArtifact: Provider<FileSystemLocation>
        @InputArtifact get

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile.toPath()

        val includeRules =
            project
                .extension<MinecraftCodevExtension>()
                .extension<IncludesExtension>().rules

        val handler =
            zipFileSystem(input).use {
                val root = it.base.getPath("/")

                val handler =
                    includeRules.get().firstNotNullOfOrNull { rule ->
                        rule.load(root)
                    } ?: run {
                        outputs.file(inputArtifact)
                        return
                    }

                for (includedJar in handler.list(root)) {
                    val path = it.base.getPath(includedJar.path)

                    path.copyTo(outputs.file(path.fileName.toString()).toPath())
                }

                handler
            }

        val output = outputs.file(input.fileName.toString()).toPath()

        zipFileSystem(output).use {
            val root = it.base.getPath("/")

            for (jar in handler.list(root)) {
                it.base.getPath(jar.path).deleteExisting()
            }

            handler.remove(root)
        }
    }
}
