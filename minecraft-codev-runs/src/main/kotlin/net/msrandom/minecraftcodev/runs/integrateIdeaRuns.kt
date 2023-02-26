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

    container.all { builder ->
        extensions.getByType(IdeaModel::class.java)
            .project
            .settings
            .runConfigurations
            .register("${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(builder.name)}", Application::class.java) { application ->
                val config = builder.build(project)

                application.mainClass = config.mainClass.get()
                application.workingDirectory = config.workingDirectory.get().asFile.absolutePath
                application.envs = config.environment.get().mapValues { it.value.compile() }
                application.programParameters = config.arguments.get().joinToString(" ", transform = MinecraftRunConfiguration.Argument::compile)
                application.jvmArgs = config.jvmArguments.get().joinToString(" ", transform = MinecraftRunConfiguration.Argument::compile)

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

                for (other in config.dependsOn.get()) {
                    application.beforeRun.add(objects.newInstance(RunConfigurationBeforeRunTask::class.java, other.name).apply {
                        configuration.set(provider {
                            "Application.${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(other.name)}"
                        })
                    })
                }

                for (task in config.beforeRun.get()) {
                    application.beforeRun.register(task.name, GradleTask::class.java) {
                        it.task = task
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
