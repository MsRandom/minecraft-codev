package net.msrandom.minecraftcodev.runs.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.LibraryData
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
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
    abstract val natives: Property<Configuration>
        @Input get

    val destinationDirectory: Provider<Directory>
        @OutputDirectory
        get() = project.layout.dir(project.provider { temporaryDir })

    init {
        run {
            natives.finalizeValueOnRead()
        }
    }

    @TaskAction
    fun extract() {
        val natives = natives.get()
        val output = destinationDirectory.get().asFile.toPath()

        val rules = mutableListOf<LibraryData.Native>()

        for (file in natives) {
            if (file.extension == "json") {
                val native = file.inputStream().use {
                    Json.decodeFromStream<LibraryData.Native>(it)
                }

                rules.add(native)
            }
        }

        for (rule in rules) {
            val artifacts = natives.resolvedConfiguration.resolvedArtifacts.filter {
                it.moduleVersion.id.group == rule.library.group && it.moduleVersion.id.name == rule.library.module && it.moduleVersion.id.version == rule.library.version
            }

            val exclude = rule.extractData?.exclude.orEmpty()

            for (artifact in artifacts) {
                val extension = artifact.extension
                if (extension == "jar" || extension == "zip") {
                    zipFileSystem(artifact.file.toPath()).use {
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

                    artifact.file.toPath().copyTo(outputPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
