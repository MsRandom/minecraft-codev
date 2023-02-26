package net.msrandom.minecraftcodev.runs

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.provider.*
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

abstract class MinecraftRunConfiguration @Inject constructor(val project: Project) {
    abstract val mainClass: Property<String>
    abstract val jvmVersion: Property<Int>

    abstract val sourceSet: Property<SourceSet>
    abstract val kotlinSourceSet: Property<KotlinSourceSet>

    val beforeRun: ListProperty<Task> = project.objects.listProperty(Task::class.java)
    val dependsOn: ListProperty<MinecraftRunConfigurationBuilder> = project.objects.listProperty(MinecraftRunConfigurationBuilder::class.java)
    val arguments: SetProperty<Argument> = project.objects.setProperty(Argument::class.java)
    val jvmArguments: SetProperty<Argument> = project.objects.setProperty(Argument::class.java)
    val environment: MapProperty<String, Argument> = project.objects.mapProperty(String::class.java, Argument::class.java)

    abstract val workingDirectory: DirectoryProperty

    val modClasspaths = mutableMapOf<String, ConfigurableFileCollection>()

    init {
        run {
            mainClass.finalizeValueOnRead()
            jvmVersion.finalizeValueOnRead()

            project.plugins.withType(JvmEcosystemPlugin::class.java) {
                sourceSet.convention(project.extensions.getByType(SourceSetContainer::class.java).named(SourceSet.MAIN_SOURCE_SET_NAME))
            }

            beforeRun.finalizeValueOnRead()
            arguments.finalizeValueOnRead()
            jvmArguments.finalizeValueOnRead()
            environment.finalizeValueOnRead()

            workingDirectory
                .convention(project.layout.projectDirectory.dir("run"))
                .finalizeValueOnRead()
        }
    }

    fun mod(name: String) = modClasspaths.computeIfAbsent(name) {
        project.objects.fileCollection().apply { finalizeValueOnRead() }
    }

    class Argument(val parts: List<Any?>) {
        constructor(vararg part: Any?) : this(listOf(*part))

        fun compile() = parts.joinToString("") {
            if (it is Provider<*>) {
                map(it.get())
            } else {
                map(it)
            }
        }

        private companion object {
            private fun map(part: Any?) = when (part) {
                is Path -> part.toAbsolutePath().toString()
                is File -> part.absolutePath
                else -> part.toString()
            }
        }
    }
}
