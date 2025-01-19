package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.task.CachedMinecraftParameters
import net.msrandom.minecraftcodev.core.task.convention
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

abstract class MinecraftRunConfiguration @Inject constructor(private val name: String, val project: Project) : Named {
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

    abstract val dependsOn: ListProperty<MinecraftRunConfiguration>
        @Input
        get

    abstract val arguments: ListProperty<String>
        @Input
        get

    abstract val jvmArguments: ListProperty<String>
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

    val friendlyName
        get() = if (project == project.rootProject) {
            "Run :$name"
        } else {
            "Run ${project.path}:$name"
        }

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

    fun mainClass(mainClass: String) = apply {
        this.mainClass.set(mainClass)
    }

    fun jvmVersion(jvmVersion: Int) = apply {
        this.jvmVersion.set(jvmVersion)
    }

    fun sourceSet(sourceSet: String) = apply {
        this.sourceSet.set(project.extension<SourceSetContainer>().named(sourceSet))
    }

    fun sourceSet(sourceSet: Provider<SourceSet>) = apply {
        this.sourceSet.set(sourceSet)
    }

    fun sourceSet(sourceSet: SourceSet) = apply {
        this.sourceSet.set(sourceSet)
    }

    fun beforeRun(task: Provider<Task>) = apply {
        beforeRun.add(task)
    }

    fun beforeRun(vararg tasks: Task) = apply {
        beforeRun.addAll(tasks.toList())
    }

    fun beforeRun(vararg taskNames: String) = apply {
        for (taskName in taskNames) {
            beforeRun.add(project.tasks.named(taskName))
        }
    }

    fun dependsOn(vararg runConfigurations: MinecraftRunConfiguration) = apply {
        dependsOn.addAll(runConfigurations.toList())
    }

    fun args(vararg args: Any?) = arguments(*args)

    fun arguments(vararg args: Any?) = apply {

        arguments.addAll(args.map(Any?::toString))
    }

    fun jvmArgs(vararg args: Any?) = jvmArguments(*args)

    fun jvmArguments(vararg args: Any?) = apply {

        jvmArguments.addAll(args.map(Any?::toString))
    }

    fun env(variables: Map<String, Any>) = environment(variables)

    fun env(vararg variables: Pair<String, Any>) = environment(*variables)

    fun env(
        key: String,
        value: Any,
    ) = environment(key, value)

    fun environment(variables: Map<String, Any>) = apply {

        environment.putAll(variables.mapValues(Any::toString))
    }

    fun environment(vararg variables: Pair<String, Any>) = environment(variables.toMap())

    fun environment(
        key: String,
        value: Any,
    ) = apply {

        environment.put(key, value.toString())
    }

    fun executableDir(path: Any) = apply {
        executableDirectory.set(project.file(path))
    }

    fun executableDirectory(path: Any) = apply {
        executableDirectory.set(project.file(path))
    }

    fun defaults(action: Action<RunConfigurationDefaultsContainer>) {
        val defaults = project.extension<RunsContainer>().extension<RunConfigurationDefaultsContainer>()

        defaults.configuration = this

        action.execute(defaults)
    }

    private fun mapArgumentPart(part: Any?) = when (part) {
        is Path -> part.toAbsolutePath().toString()
        is File -> part.absolutePath
        is FileSystemLocation -> part.toString()
        else -> part.toString()
    }

    fun compileArguments(arguments: Iterable<Any?>): ListProperty<String> =
        project.objects.listProperty(String::class.java).apply {
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

    override fun getName() = name
}
