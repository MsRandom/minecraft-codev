package net.msrandom.minecraftcodev.mixins.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import kotlin.io.path.deleteExisting

@CacheableTask
abstract class RemoveJarMixins : DefaultTask() {
    abstract val inputFiles: ConfigurableFileCollection
        @InputFiles
        @Classpath
        get

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    @TaskAction
    private fun removeMixins() {
        for (input in inputFiles) {
            val input = input.toPath()

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
                outputs.file(input)

                continue
            }

            val output = outputDirectory.asFile.get().toPath().resolve(input.fileName)

            outputs.file(output)

            zipFileSystem(output).use {
                val root = it.base.getPath("/")
                handler.list(root).forEach { path -> root.resolve(path).deleteExisting() }
                handler.remove(root)
            }
        }
    }
}
