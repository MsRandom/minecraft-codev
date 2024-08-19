package net.msrandom.minecraftcodev.mixins.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTask
abstract class StripMixins : DefaultTask() {
    abstract val inputFiles: ConfigurableFileCollection
        @InputFiles
        @Classpath
        @SkipWhenEmpty
        get

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    val outputFiles: FileCollection
        @Internal get() = project.fileTree(outputDirectory)

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    @TaskAction
    private fun removeMixins(inputChanges: InputChanges) {
        for (fileChange in inputChanges.getFileChanges(inputFiles)) {
            val input = fileChange.file.toPath()
            val output = outputDirectory.asFile.get().toPath().resolve("${input.nameWithoutExtension}-no-mixins.${input.extension}")

            if (fileChange.changeType == ChangeType.MODIFIED) {
                output.deleteExisting()
            } else if (fileChange.changeType == ChangeType.REMOVED) {
                output.deleteExisting()

                continue
            }

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
                output.deleteIfExists()
                output.createSymbolicLinkPointingTo(input)

                continue
            }

            input.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)

            zipFileSystem(output).use {
                val root = it.base.getPath("/")
                handler.list(root).forEach { path -> root.resolve(path).deleteExisting() }
                handler.remove(root)
            }
        }
    }
}
