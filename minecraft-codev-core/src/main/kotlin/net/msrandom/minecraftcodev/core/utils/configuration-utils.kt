package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.ResolveConfiguration
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.internal.tasks.TaskDependencyResolveContext

fun Project.addConfigurationResolutionDependencies(context: TaskDependencyResolveContext, configuration: Configuration) {
    val name = "resolve" + StringUtils.capitalize(configuration.name)

    context.add(tasks.findByName(name) ?: tasks.create(name, ResolveConfiguration::class.java, configuration))
}

fun ConfigurationContainer.createIfAbsent(name: String, setup: (configuration: Configuration) -> Unit): Configuration =
    findByName(name) ?: create(name).also(setup)
