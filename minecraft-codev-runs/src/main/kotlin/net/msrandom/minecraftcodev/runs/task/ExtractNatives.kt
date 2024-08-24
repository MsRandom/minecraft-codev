package net.msrandom.minecraftcodev.runs.task

import kotlinx.coroutines.*
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.rulesMatch
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.osName
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

@CacheableTask
abstract class ExtractNatives : DefaultTask() {
    abstract val version: Property<String>
        @Input get

    val destinationDirectory: Provider<Directory>
        @OutputDirectory
        get() = project.layout.dir(project.provider { temporaryDir })

    @TaskAction
    private fun extract() {
        val output = destinationDirectory.get().asFile.toPath()

        runBlocking {
            val metadata = project.extension<MinecraftCodevExtension>().getVersionList().version(version.get())

            val libs =
                metadata.libraries.filter { library ->
                    if (library.natives.isEmpty()) {
                        return@filter false
                    }

                    rulesMatch(library.rules)
                }.associate {
                    val classifier = it.natives.getValue(osName())

                    project.dependencies.create("${it.name}:$classifier") to it.extract
                }

            withContext(Dispatchers.IO) {
                val config = project.configurations.detachedConfiguration(*libs.keys.toTypedArray())

                val fileResolution = libs.flatMap { (dependency, extractionRules) ->
                    val artifactView = config.incoming.artifactView { view ->
                        view.componentFilter {
                            it is ModuleComponentIdentifier &&
                                    it.group == dependency.group &&
                                    it.module == dependency.name &&
                                    it.version == dependency.version
                        }
                    }

                    val exclude = extractionRules?.exclude.orEmpty()

                    artifactView.artifacts.artifactFiles.map { artifact ->
                        async {
                            val extension = artifact.extension
                            if (extension == "jar" || extension == "zip") {
                                zipFileSystem(artifact.toPath()).use {
                                    val root = it.base.getPath("/")
                                    root.walk {
                                        for (path in filter(Path::isRegularFile)) {
                                            val name = root.relativize(path).toString()
                                            if (exclude.none(name::startsWith)) {
                                                val outputPath = output.resolve(name)
                                                outputPath.parent?.createDirectories()

                                                path.copyTo(outputPath, StandardCopyOption.REPLACE_EXISTING)
                                            }
                                        }
                                    }
                                }
                            } else if (extension != "json") {
                                val outputPath = output.resolve(artifact.name)
                                outputPath.parent?.createDirectories()

                                artifact.toPath().copyTo(outputPath, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }

                fileResolution.awaitAll()
            }
        }
    }
}
