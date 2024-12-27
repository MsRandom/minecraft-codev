package net.msrandom.minecraftcodev.runs.task

import net.msrandom.minecraftcodev.core.resolve.rulesMatch
import net.msrandom.minecraftcodev.core.task.CachedMinecraftTask
import net.msrandom.minecraftcodev.core.task.versionList
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.osName
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

@CacheableTask
abstract class ExtractNatives : CachedMinecraftTask() {
    abstract val version: Property<String>
        @Input get

    abstract val destinationDirectory: DirectoryProperty
        @OutputDirectory get

    abstract val configurationContainer: ConfigurationContainer
        @Inject get

    abstract val dependencyHandler: DependencyHandler
        @Inject get

    init {
        destinationDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    @TaskAction
    fun extract() {
        val output = destinationDirectory.getAsPath()

        val metadata = cacheParameters.versionList().version(version.get())

        val libs =
            metadata.libraries.filter { library ->
                if (library.natives.isEmpty()) {
                    return@filter false
                }

                rulesMatch(library.rules)
            }.associate {
                val classifier = it.natives.getValue(osName())

                dependencyHandler.create("${it.name}:$classifier") to it.extract
            }

        val config = configurationContainer.detachedConfiguration(*libs.keys.toTypedArray())

        val artifactView = config.incoming.artifactView { view ->
            view.componentFilter {
                it is ModuleComponentIdentifier && libs.any { (dependency, _) -> it.group == dependency.group && it.module == dependency.name }
            }
        }

        for (artifact in artifactView.artifacts.artifacts) {
            val file = artifact.file
            val id = artifact.variant.owner as ModuleComponentIdentifier

            val exclude = libs.entries.firstOrNull { (dependency, _) ->
                id.group == dependency.group && id.module == dependency.name
            }
                ?.value
                ?.exclude
                ?: emptyList()

            val extension = file.extension
            if (extension == "jar" || extension == "zip") {
                zipFileSystem(file.toPath()).use {
                    val root = it.getPath("/")
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
            } else {
                val outputPath = output.resolve(file.name)
                outputPath.parent?.createDirectories()

                file.toPath().copyTo(outputPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
