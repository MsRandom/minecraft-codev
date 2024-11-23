package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import javax.inject.Inject

abstract class MinecraftRunConfigurationBuilder @Inject constructor(private val name: String, val project: Project) : Named {
    private val setupActions = mutableListOf<Action<MinecraftRunConfiguration>>()
    private val configurationActions = mutableListOf<Action<MinecraftRunConfiguration>>()

    val defaults: RunConfigurationDefaultsContainer
        get() = project.extension<RunsContainer>().extension<RunConfigurationDefaultsContainer>().also { it.builder = this }

    val friendlyName
        get() = if (project == project.rootProject) {
            "Run :$name"
        } else {
            "Run ${project.path}:$name"
        }

    override fun getName() = name

    fun setup(action: Action<MinecraftRunConfiguration>) {
        setupActions += action
    }

    fun setup(action: MinecraftRunConfiguration.() -> Unit) = setup(Action(action))

    fun action(action: Action<MinecraftRunConfiguration>) {
        configurationActions += action
    }

    fun action(action: MinecraftRunConfiguration.() -> Unit) = action(Action(action))

    fun mainClass(mainClass: String) = apply {
        action { this.mainClass.set(mainClass) }
    }

    fun jvmVersion(jvmVersion: Int) = apply {
        action { this.jvmVersion.set(jvmVersion) }
    }

    fun sourceSet(sourceSet: String) = apply {
        setup { this.sourceSet.set(project.extension<SourceSetContainer>().named(sourceSet)) }
    }

    fun sourceSet(sourceSet: Provider<SourceSet>) = apply {
        setup { this.sourceSet.set(sourceSet) }
    }

    fun sourceSet(sourceSet: SourceSet) = apply {
        setup { this.sourceSet.set(sourceSet) }
    }

    fun beforeRun(task: Provider<Task>) = apply {
        action { beforeRun.add(task) }
    }

    fun beforeRun(vararg tasks: Task) = apply {
        action { beforeRun.addAll(tasks.toList()) }
    }

    fun beforeRun(vararg taskNames: String) = apply {
        action {
            for (taskName in taskNames) {
                beforeRun.add(project.tasks.named(taskName))
            }
        }
    }

    fun dependsOn(vararg runConfigurations: MinecraftRunConfigurationBuilder) = apply {
        action { dependsOn.addAll(runConfigurations.toList()) }
    }

    fun args(vararg args: Any?) = arguments(*args)

    fun arguments(vararg args: Any?) = apply {
        action {
            arguments.addAll(args.map(Any?::toString))
        }
    }

    fun jvmArgs(vararg args: Any?) = jvmArguments(*args)

    fun jvmArguments(vararg args: Any?) = apply {
        action {
            jvmArguments.addAll(args.map(Any?::toString))
        }
    }

    fun env(variables: Map<String, Any>) = environment(variables)

    fun env(vararg variables: Pair<String, Any>) = environment(*variables)

    fun env(
        key: String,
        value: Any,
    ) = environment(key, value)

    fun environment(variables: Map<String, Any>) = apply {
        action {
            environment.putAll(variables.mapValues(Any::toString))
        }
    }

    fun environment(vararg variables: Pair<String, Any>) = environment(variables.toMap())

    fun environment(
        key: String,
        value: Any,
    ) = apply {
        action {
            environment.put(key, value.toString())
        }
    }

    fun executableDir(path: Any) = apply {
        action { executableDirectory.set(project.file(path)) }
    }

    fun executableDirectory(path: Any) = apply {
        action { executableDirectory.set(project.file(path)) }
    }

    internal fun build() = project.objects.newInstance(MinecraftRunConfiguration::class.java).also {
        for (action in setupActions) {
            action.execute(it)
        }

        for (action in configurationActions) {
            action.execute(it)
        }
    }
}
