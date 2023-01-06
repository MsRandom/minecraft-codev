package net.msrandom.minecraftcodev.runs.task

import net.msrandom.minecraftcodev.runs.MinecraftRunConfiguration
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.tasks.SourceSet
import org.w3c.dom.Node
import java.io.File
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

open class GenerateIdeaRuns : GenerateRuns() {
    private val rootPath = project.rootProject.layout.projectDirectory.asFile.toPath()

    private fun configName(gradleName: String, run: MinecraftRunConfiguration) = when {
        run.name.isPresent -> run.name.get()
        project == project.rootProject -> "${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(gradleName)}"
        else -> "${project.path}:${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(gradleName)}"
    }

    private fun moduleName(sourceSet: SourceSet) = if (project == project.rootProject) {
        "${project.rootProject.name}.${sourceSet.name}"
    } else {
        "${project.rootProject.name}${project.path.replace(':', '.')}.${sourceSet.name}"
    }

    private fun relativize(path: Path): String {
        val projectRelative = rootPath.relativize(path)
        if (!projectRelative.startsWith("..")) {
            return Path("\$PROJECT_DIR$").resolve(projectRelative).toString()
        }

        val homeRelative = Path(SystemUtils.USER_HOME).relativize(path)
        if (!homeRelative.startsWith("..")) {
            return Path("\$USER_HOME$").resolve(homeRelative).toString()
        }

        return path.toAbsolutePath().toString()
    }

    private fun relativize(potentialPath: MinecraftRunConfiguration.Argument) = potentialPath.parts.joinToString("") {
        when (it) {
            is File -> relativize(it.toPath())
            is Path -> relativize(it)
            else -> it.toString()
        }
    }

    private fun formatArguments(arguments: Collection<MinecraftRunConfiguration.Argument>) = arguments.joinToString(" ") { relativize(it) }

    override fun generateRuns(runs: List<Pair<String, MinecraftRunConfiguration>>) {
        // The main Intellij project could probably be something other than the root project, but that'd pain a pain to handle, so we don't.
        var workspacePath = rootPath.resolve(".idea").resolve("workspace.xml")

        if (workspacePath.notExists()) {
            workspacePath = rootPath.resolve("${project.rootProject.name}.iws")

            if (workspacePath.notExists()) {
                throw UnsupportedOperationException("${project.rootProject.name} is not an Intellij project")
            }
        }

        if (runs.isNotEmpty()) {
            val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val document = documentBuilder.parse(workspacePath.inputStream())

            val project = document.documentElement
            val components = project.getElementsByTagName(COMPONENT)
            var runManager: Node? = null

            for (i in 0 until components.length) {
                val component = components.item(i)
                val name = component.attributes.getNamedItem(NAME)

                if (name.nodeValue == RUN_MANAGER) {
                    runManager = component
                    break
                }
            }

            if (runManager == null) {
                val (gradleName, selected) = runs[0]
                runManager = document.createElement(COMPONENT)
                runManager.setAttribute(NAME, RUN_MANAGER)
                runManager.setAttribute("selected", configName(gradleName, selected))
                project.appendChild(runManager)
            }

            // I don't know why the compiler thinks this is nullable by this point but whatever
            runManager!!

            val madeChildren = buildMap {
                val configurations = project.getElementsByTagName(CONFIGURATION)
                for (i in 0 until configurations.length) {
                    val configuration = configurations.item(i)
                    val name = configuration.attributes.getNamedItem(NAME)

                    if (name != null) {
                        put(name.nodeValue, configuration)
                    }
                }
            }

            for ((gradleName, run) in runs) {
                val name = configName(gradleName, run)
                val old = madeChildren[name]

                if (old != null) {
                    runManager.removeChild(old)
                    logger.info("Removing existing $name run configuration. If this is not intended, please rename your run configurations to ensure they're unique.")
                }

                val configuration = document.createElement(CONFIGURATION)
                configuration.setAttribute(NAME, name)
                configuration.setAttribute("type", APPLICATION)
                configuration.setAttribute("factoryName", APPLICATION)

                fun appendOption(name: String, value: String) = configuration.appendChild(
                    document.createElement(OPTION).apply {
                        setAttribute(NAME, name)
                        setAttribute(VALUE, value)
                    }
                )

                appendOption("MAIN_CLASS_NAME", run.mainClass.get())

                configuration.appendChild(
                    document.createElement("module").apply { setAttribute(NAME, moduleName(run.sourceSet.get())) }
                )

                fun appendArguments(name: String, arguments: Collection<MinecraftRunConfiguration.Argument>) {
                    if (arguments.isNotEmpty()) {
                        appendOption(name, formatArguments(arguments))
                    }
                }

                val workingDirectory = run.workingDirectory.asFile.get().toPath()

                workingDirectory.createDirectories()

                appendArguments("PROGRAM_PARAMETERS", run.arguments.get())
                appendArguments("VM_PARAMETERS", run.jvmArguments.get())
                appendOption("WORKING_DIRECTORY", relativize(workingDirectory))

                configuration.appendChild(
                    document.createElement("envs").apply {
                        for (environment in run.environment.get()) {
                            appendChild(
                                document.createElement("env").apply {
                                    setAttribute(NAME, environment.key)
                                    setAttribute(VALUE, relativize(environment.value))
                                }
                            )
                        }
                    }
                )

                configuration.appendChild(
                    document.createElement("method").apply {
                        appendChild(
                            document.createElement(OPTION).apply {
                                setAttribute(NAME, "make")
                                setAttribute(ENABLED, true.toString())
                            }
                        )

                        if (run.beforeRunTasks.get().isNotEmpty()) {
                            appendChild(
                                document.createElement(OPTION).apply {
                                    setAttribute(NAME, "Gradle.BeforeRunTask")
                                    setAttribute(ENABLED, true.toString())
                                    setAttribute("tasks", run.beforeRunTasks.get().joinToString(" "))
                                    setAttribute("externalProjectPath", relativize(this@GenerateIdeaRuns.project.layout.projectDirectory.asFile.toPath()))
                                }
                            )
                        }

                        if (run.beforeRunConfigs.get().isNotEmpty()) {
                            for (config in run.beforeRunConfigs.get()) {
                                val dependency = runs.firstOrNull { it.first == config.name }

                                if (dependency != null) {
                                    appendChild(
                                        document.createElement(OPTION).apply {
                                            setAttribute(NAME, "RunConfigurationTask")
                                            setAttribute(ENABLED, true.toString())
                                            setAttribute("run_configuration_name", configName(dependency.first, dependency.second))
                                            setAttribute("run_configuration_type", "Application")
                                        }
                                    )
                                }
                            }
                        }
                    }
                )

                runManager.appendChild(configuration)
            }

            TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(workspacePath.toFile()))
        }
    }

    private companion object {
        const val COMPONENT = "component"
        const val CONFIGURATION = "configuration"
        const val OPTION = "option"

        const val NAME = "name"
        const val VALUE = "value"
        const val ENABLED = "enabled"

        const val RUN_MANAGER = "RunManager"
        const val APPLICATION = "Application"
    }
}
