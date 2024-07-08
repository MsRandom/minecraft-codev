package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.*
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

abstract class MinecraftRunConfiguration
@Inject
constructor(val project: Project) {
    abstract val mainClass: Property<String>
        @Input
        get

    abstract val jvmVersion: Property<Int>
        @Input
        get

    abstract val sourceSet: Property<SourceSet>
        @Input
        get

    abstract val beforeRun: ListProperty<Task>
        @Input
        get

    abstract val dependsOn: ListProperty<MinecraftRunConfigurationBuilder>
        @Input
        get

    abstract val arguments: SetProperty<Argument>
        @Input
        get

    abstract val jvmArguments: SetProperty<Argument>
        @Input
        get

    abstract val environment: MapProperty<String, Argument>
        @Input
        get

    abstract val executableDirectory: DirectoryProperty
        @InputDirectory
        get

    val modClasspaths = mutableMapOf<String, ConfigurableFileCollection>()

    init {
        run {
            mainClass.finalizeValueOnRead()
            jvmVersion.finalizeValueOnRead()

            sourceSet.convention(project.extension<SourceSetContainer>().named(SourceSet.MAIN_SOURCE_SET_NAME))

            beforeRun.finalizeValueOnRead()
            arguments.finalizeValueOnRead()
            jvmArguments.finalizeValueOnRead()
            environment.finalizeValueOnRead()

            executableDirectory
                .convention(project.layout.projectDirectory.dir("bin"))
                .finalizeValueOnRead()
        }
    }

    fun mod(name: String) =
        modClasspaths.computeIfAbsent(name) {
            project.objects.fileCollection().apply { finalizeValueOnRead() }
        }

    class Argument(val parts: List<Any?>) {
        constructor(vararg part: Any?) : this(listOf(*part))

        fun compile(): String =
            parts.joinToString("") {
                if (it is Provider<*>) {
                    map(it.get())
                } else {
                    map(it)
                }
            }

        private companion object {
            private fun map(part: Any?) =
                when (part) {
                    is Path -> part.toAbsolutePath().toString()
                    is File -> part.absolutePath
                    is Argument -> part.compile()
                    else -> part.toString()
                }
        }
    }
}
