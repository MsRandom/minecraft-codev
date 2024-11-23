package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.task.CachedMinecraftParameters
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

abstract class MinecraftRunConfiguration @Inject constructor(val project: Project) {
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

    abstract val arguments: SetProperty<String>
        @Input
        get

    abstract val jvmArguments: SetProperty<String>
        @Input
        get

    abstract val environment: MapProperty<String, String>
        @Input
        get

    abstract val executableDirectory: DirectoryProperty
        @InputDirectory
        get

    abstract val cacheParameters: CachedMinecraftParameters
        @Nested
        get

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
                .convention(project.layout.projectDirectory.dir("run"))
                .finalizeValueOnRead()

            cacheParameters.convention(project)
        }
    }

    private fun mapArgumentPart(part: Any?) = when (part) {
        is Path -> part.toAbsolutePath().toString()
        is File -> part.absolutePath
        is FileSystemLocation -> part.toString()
        else -> part.toString()
    }

    fun compileArguments(arguments: Iterable<Any?>): ListProperty<String> = project.objects.listProperty(String::class.java).apply {
        for (argument in arguments) {
            if (argument is Provider<*>) {
                add(argument.map(::mapArgumentPart))
            } else {
                add(mapArgumentPart(argument))
            }
        }
    }

    fun compileArgument(vararg parts: Any?): Provider<String> = compileArguments(parts.toList()).map {
        it.joinToString("")
    }
}
