package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.*
import javax.inject.Inject

fun Project.integrateIdeaRuns() {
    if (project != rootProject) return

    plugins.apply(IdeaExtPlugin::class.java)

    val runConfigurations = extensions.getByType(IdeaModel::class.java).project.settings.runConfigurations

    allprojects { otherProject ->
        otherProject.plugins.withType(MinecraftCodevPlugin::class.java) {
            otherProject.plugins.withType(MinecraftCodevRunsPlugin::class.java) {
                otherProject.extensions
                    .getByType(MinecraftCodevExtension::class.java)
                    .extensions
                    .getByType(RunsContainer::class.java)
                    .all { builder ->
                        runConfigurations.register(builder.friendlyName, Application::class.java) { application ->
                            val config = builder.build()

                            application.mainClass = config.mainClass.get()
                            application.workingDirectory = config.executableDirectory.get().asFile.absolutePath
                            application.envs = config.environment.get().mapValues { it.value.compile() }
                            application.programParameters =
                                config.arguments.get().joinToString(
                                    " ",
                                    transform = MinecraftRunConfiguration.Argument::compile,
                                )
                            application.jvmArgs =
                                config.jvmArguments.get().joinToString(
                                    " ",
                                    transform = MinecraftRunConfiguration.Argument::compile,
                                )

                            if (config.sourceSet.isPresent) {
                                application.moduleRef(otherProject, config.sourceSet.get())
                            } else {
                                application.moduleRef(otherProject)
                            }

                            // TODO Make this transitive
                            for (other in config.dependsOn.get()) {
                                application.beforeRun.add(
                                    objects.newInstance(RunConfigurationBeforeRunTask::class.java, other.name).apply {
                                        configuration.set(
                                            provider {
                                                "Application.${other.friendlyName}"
                                            },
                                        )
                                    },
                                )
                            }

                            for (task in config.beforeRun.get()) {
                                application.beforeRun.register(task.name, GradleTask::class.java) {
                                    it.task = task
                                }
                            }
                        }
                    }
            }
        }
    }
}

// Relies on IntelliJ plugin to import
abstract class RunConfigurationBeforeRunTask
@Inject
constructor(name: String) : BeforeRunTask() {
    abstract val configuration: Property<String>

    init {
        super.name = name
        type = "runConfiguration"
    }

    override fun toMap() = mapOf(*super.toMap().toList().toTypedArray(), "configuration" to configuration.get())
}
