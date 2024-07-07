package net.msrandom.minecraftcodev.mixins

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
import kotlin.io.path.deleteExisting

abstract class JarMixinRemover : TransformAction<TransformParameters.None> {
    abstract val project: Project
        @Internal get

    abstract val input: Provider<FileSystemLocation>
        @InputArtifact get

    override fun transform(outputs: TransformOutputs) {
        val input = input.get().asFile.toPath()

        val handler =
            zipFileSystem(input).use {
                val root = it.base.getPath("/")

                project
                    .extension<MinecraftCodevExtension>()
                    .extension<MixinsExtension>()
                    .rules
                    .get()
                    .firstNotNullOfOrNull { rule ->
                        rule.load(root)
                    }
            }

        if (handler == null) {
            throw UnsupportedOperationException(
                "Couldn't find mixin configs for $input unsupported format.\n" +
                    "You can register new mixin loading rules with minecraft.mixins.rules",
            )
        }

        zipFileSystem(outputs.file(input.fileName.toString()).toPath()).use {
            val root = it.base.getPath("/")
            handler.list(root).forEach { path -> root.resolve(path).deleteExisting() }
            handler.remove(root)
        }
    }
}
