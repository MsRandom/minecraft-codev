package net.msrandom.minecraftcodev.includes

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.deleteExisting

@CacheableTask
abstract class ExtractIncludes : DefaultTask() {
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
    private fun extractIncludes() {
        val includeRules =
            project
                .extension<MinecraftCodevExtension>()
                .extension<IncludesExtension>()
                .rules

        for (input in inputFiles) {
            val input = input.toPath()

            val output = outputDirectory.asFile.get().toPath().resolve(input.fileName)

            val handler =
                zipFileSystem(input).use {
                    val root = it.base.getPath("/")

                    val handler =
                        includeRules.get().firstNotNullOfOrNull { rule ->
                            rule.load(root)
                        } ?: run {
                            Files.createSymbolicLink(input, output)
                            outputs.file(output)

                            return
                        }

                    for (includedJar in handler.list(root)) {
                        val path = it.base.getPath(includedJar.path)
                        val includeOutput = outputDirectory.asFile.get().toPath().resolve(path.fileName)

                        outputs.file(includeOutput)

                        path.copyTo(includeOutput, StandardCopyOption.REPLACE_EXISTING)
                    }

                    handler
                }

            outputs.file(output)

            input.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)

            zipFileSystem(output).use { (fs) ->
                val root = fs.getPath("/")

                for (jar in handler.list(root)) {
                    fs.getPath(jar.path).deleteExisting()
                }

                handler.remove(root)
            }
        }
    }
}
