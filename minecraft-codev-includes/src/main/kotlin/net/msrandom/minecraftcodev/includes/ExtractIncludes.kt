package net.msrandom.minecraftcodev.includes

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTask
abstract class ExtractIncludes : DefaultTask() {
    abstract val inputFiles: ConfigurableFileCollection
        @InputFiles
        @Classpath
        @SkipWhenEmpty
        get

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    val outputFiles: FileCollection
        @OutputFiles get() = project.fileTree(outputDirectory)

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    @TaskAction
    private fun extractIncludes(inputChanges: InputChanges) {
        val includeRules =
            project
                .extension<MinecraftCodevExtension>()
                .extension<IncludesExtension>()
                .rules

        val inputHashes =
            runBlocking {
                inputFiles.map {
                    async {
                        hashFile(it.toPath())
                    }
                }.awaitAll().toHashSet()
            }

        CHANGE@for (fileChange in inputChanges.getFileChanges(inputFiles)) {
            val input = fileChange.file.toPath()
            val outputDirectory = outputDirectory.asFile.get().toPath().resolve(input.nameWithoutExtension)

            if (fileChange.changeType == ChangeType.MODIFIED || fileChange.changeType == ChangeType.REMOVED) {
                outputDirectory.walk {
                    filter(Files::isRegularFile).forEach(Files::delete)
                }

                if (fileChange.changeType == ChangeType.REMOVED) {
                    continue
                }
            }

            outputDirectory.createDirectories()

            val output = outputDirectory.resolve(input.fileName)

            val handler =
                zipFileSystem(input).use {
                    val root = it.base.getPath("/")

                    val handler =
                        includeRules.get().firstNotNullOfOrNull { rule ->
                            rule.load(root)
                        } ?: run {
                            output.deleteIfExists()
                            output.createSymbolicLinkPointingTo(input)

                            return@use null
                        }

                    runBlocking {
                        handler.list(root).map { includedJar ->
                            async {
                                val path = it.base.getPath(includedJar.path)
                                val includeOutput = outputDirectory.resolve(path.fileName.toString())

                                val hash = hashFile(path)

                                if (hash !in inputHashes) {
                                    path.copyTo(includeOutput, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                                }
                            }
                        }.awaitAll()
                    }

                    handler
                } ?: continue

            input.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)

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
