package net.msrandom.minecraftcodev.runs

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import javax.inject.Inject

abstract class MinecraftRunConfigurationBuilder @Inject constructor(objectFactory: ObjectFactory) : Named, ExtensionAware {
    val defaults = apply { objectFactory.newInstance(RunConfigurationDefaultsContainer::class.java, this) }
    private val actions = mutableListOf<Action<MinecraftRunConfiguration>>()

    fun action(action: Action<MinecraftRunConfiguration>) {
        actions += action
    }

    fun action(action: MinecraftRunConfiguration.() -> Unit) = action(Action(action))

    fun name(name: String) = apply {
        action { this.name.set(name) }
    }

    fun mainClass(mainClass: String) = apply {
        action { this.mainClass.set(mainClass) }
    }

    fun jvmVersion(jvmVersion: Int) = apply {
        action { this.jvmVersion.set(jvmVersion) }
    }

    fun sourceSet(sourceSet: String) = apply {
        action { this.sourceSet.set(project.extensions.getByType(SourceSetContainer::class.java).named(sourceSet)) }
    }

    fun sourceSet(sourceSet: Provider<SourceSet>) = apply {
        action { this.sourceSet.set(sourceSet) }
    }

    fun sourceSet(sourceSet: SourceSet) = apply {
        action { this.sourceSet.set(sourceSet) }
    }

    fun beforeRun(vararg taskNames: String) = apply {
        action { beforeRunTasks.addAll(taskNames.toList()) }
    }

    fun args(vararg args: Any?) = arguments(*args)

    fun arguments(vararg args: Any?) = apply {
        action { arguments.addAll(mapArgs(*args)) }
    }

    fun jvmArgs(vararg args: Any?) = jvmArguments(*args)

    fun jvmArguments(vararg args: Any?) = apply {
        action { jvmArguments.addAll(mapArgs(*args)) }
    }

    fun env(variables: Map<String, Any>) = environment(variables)

    fun env(vararg variables: Pair<String, Any>) = environment(*variables)

    fun env(key: String, value: Any) = environment(key, value)

    fun environment(variables: Map<String, Any>) = apply {
        action {
            environment.putAll(variables.mapValues { MinecraftRunConfiguration.Argument(it) })
        }
    }

    fun environment(vararg variables: Pair<String, Any>) = apply {
        action {
            environment.putAll(variables.associate { it.first to MinecraftRunConfiguration.Argument(it.second) })
        }
    }

    fun environment(key: String, value: Any) = apply {
        action {
            environment.put(key, MinecraftRunConfiguration.Argument(value))
        }
    }

    fun workingDirectory(path: Any) = apply {
        action { workingDirectory.set(project.file(path)) }
    }

    private fun mapArgs(vararg args: Any?) = args.map {
        if (it is MinecraftRunConfiguration.Argument) it else MinecraftRunConfiguration.Argument(it)
    }

    internal fun build(project: Project) = project.objects.newInstance(MinecraftRunConfiguration::class.java).also {
        for (action in actions) {
            action.execute(it)
        }
    }
}
