package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.JavaExec
import org.gradle.configurationcache.extensions.capitalized

class MinecraftCodevRunsPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        applyPlugin(target) {
            val runs = project.container(MinecraftRunConfigurationBuilder::class.java)

            extensions.getByType(MinecraftCodevExtension::class.java).extensions.add("runs", runs)

            runs.all { builder ->
                tasks.register("${ApplicationPlugin.TASK_RUN_NAME}${builder.name.capitalized()}", JavaExec::class.java) { javaExec ->
                    val configuration = builder.build(project)

                    javaExec.args(configuration.arguments.get().map { it.parts.joinToString("") })
                    javaExec.jvmArgs(configuration.jvmArguments.get().map { it.parts.joinToString("") })
                    javaExec.environment(configuration.environment.get().mapValues { it.value.parts.joinToString("") })
                    javaExec.classpath = configuration.sourceSet.get().runtimeClasspath
                    javaExec.workingDir(configuration.workingDirectory)
                    javaExec.mainClass.set(configuration.mainClass)
                    javaExec.dependsOn(*configuration.beforeRunTasks.get().toTypedArray())

                    javaExec.group = ApplicationPlugin.APPLICATION_GROUP
                }
            }
        }
    }
}
