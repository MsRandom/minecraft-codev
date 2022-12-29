package net.msrandom.minecraftcodev.runs

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import javax.inject.Inject

abstract class MinecraftRunConfigurationBuilder @Inject constructor(private val name: String, private val runsContainer: RunsContainer) : Named {
    private val setupActions = mutableListOf<Action<MinecraftRunConfiguration>>()
    private val configurationActions = mutableListOf<Action<MinecraftRunConfiguration>>()

    val defaults: RunConfigurationDefaultsContainer
        get() = runsContainer.extensions
            .getByType(RunConfigurationDefaultsContainer::class.java)
            .also { it.builder = this }

    override fun getName() = name

    fun setup(action: Action<MinecraftRunConfiguration>) {
        setupActions += action
    }

    fun setup(action: MinecraftRunConfiguration.() -> Unit) = setup(Action(action))

    fun action(action: Action<MinecraftRunConfiguration>) {
        configurationActions += action
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
        setup { this.sourceSet.set(project.extensions.getByType(SourceSetContainer::class.java).named(sourceSet)) }
    }

    fun sourceSet(sourceSet: Provider<SourceSet>) = apply {
        setup { this.sourceSet.set(sourceSet) }
    }

    fun sourceSet(sourceSet: SourceSet) = apply {
        setup { this.sourceSet.set(sourceSet) }
    }

    fun kotlinSourceSet(sourceSet: String) = apply {
        setup { this.kotlinSourceSet.set(project.extensions.getByType(KotlinSourceSetContainer::class.java).sourceSets.named(sourceSet)) }
    }

    fun kotlinSourceSet(sourceSet: Provider<KotlinSourceSet>) = apply {
        setup { this.kotlinSourceSet.set(sourceSet) }
    }

    fun kotlinSourceSet(sourceSet: KotlinSourceSet) = apply {
        setup { this.kotlinSourceSet.set(sourceSet) }
    }

    fun beforeRun(vararg taskNames: String) = apply {
        action { beforeRunTasks.addAll(taskNames.toList()) }
    }

    fun dependsOn(vararg runConfigurations: MinecraftRunConfigurationBuilder) = apply {
        action { beforeRunConfigs.addAll(runConfigurations.toList()) }
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
        for (action in setupActions) {
            action.execute(it)
        }

        for (action in configurationActions) {
            action.execute(it)
        }
    }
}
