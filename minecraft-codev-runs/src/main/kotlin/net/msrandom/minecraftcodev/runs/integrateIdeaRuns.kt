package net.msrandom.minecraftcodev.runs

import org.apache.commons.lang3.StringUtils
import org.gradle.api.Project
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.provider.Property
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.*
import javax.inject.Inject

fun Project.integrateIdeaRuns(container: RunsContainer) {
    plugins.apply(IdeaExtPlugin::class.java)

    val builtConfigs = hashMapOf<MinecraftRunConfigurationBuilder, MinecraftRunConfiguration>()

    container.all { builder ->
        fun configName(gradleName: String, run: MinecraftRunConfiguration) = when {
            run.name.isPresent -> run.name.get()
            project == project.rootProject -> "${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(gradleName)}"
            else -> "${project.path}:${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(gradleName)}"
        }

        val config = builtConfigs.computeIfAbsent(builder) { it.build(project) }

        extensions
            .getByType(IdeaModel::class.java)
            .project
            .settings
            .runConfigurations
            .register(configName(builder.name, config), Application::class.java) { application ->
                application.mainClass = config.mainClass.get()
                application.workingDirectory = config.workingDirectory.get().asFile.absolutePath
                application.envs.putAll(config.environment.get().mapValues { it.value.parts.joinToString("") })
                application.programParameters = config.arguments.get().joinToString(" ") { it.parts.joinToString("") }
                application.jvmArgs = config.jvmArguments.get().joinToString(" ") { it.parts.joinToString("") }

                if (config.sourceSet.isPresent) {
                    application.moduleRef(project, config.sourceSet.get())
                } else if (config.kotlinSourceSet.isPresent) {
                    fun addSourceSetName(moduleName: String) = moduleName + '.' + config.kotlinSourceSet.get().name

                    application.moduleName = if (project.path == ":") {
                        addSourceSetName(project.rootProject.name)
                    } else {
                        addSourceSetName(project.rootProject.name + project.path.replace(':', '.'))
                    }
                } else {
                    application.moduleRef(project)
                }

                for (other in config.beforeRunConfigs.get()) {
                    application.beforeRun.add(objects.newInstance(RunConfigurationBeforeRunTask::class.java, other.name).apply {
                        configuration.set(provider {
                            "Application.${configName(other.name, builtConfigs.computeIfAbsent(other) { it.build(project) })}"
                        })
                    })
                }

                for (task in config.beforeRunTasks.get()) {
                    application.beforeRun.register(task, GradleTask::class.java) {
                        it.task = tasks.getByName(task)
                    }
                }
            }
    }
}

// Relies on IntelliJ plugin to import
abstract class RunConfigurationBeforeRunTask @Inject constructor(name: String) : BeforeRunTask() {
    abstract val configuration: Property<String>

    init {
        super.name = name
        type = "runConfiguration"
    }

    override fun toMap() = mapOf(*super.toMap().toList().toTypedArray(), "configuration" to configuration.get())
}
