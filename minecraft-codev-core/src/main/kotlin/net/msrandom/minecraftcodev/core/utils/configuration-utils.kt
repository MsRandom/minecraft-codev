package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.ResolveConfiguration
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.taskfactory.ITaskFactory
import org.gradle.api.internal.project.taskfactory.TaskIdentity
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.configurationcache.extensions.serviceOf

fun Project.addConfigurationResolutionDependencies(context: TaskDependencyResolveContext, configuration: Configuration) {
    context.add(configuration.buildDependencies)
    context.add(
        serviceOf<ITaskFactory>().create(
            TaskIdentity.create(
                "resolve" + StringUtils.capitalize(configuration.name),
                ResolveConfiguration::class.java,
                project as ProjectInternal
            ),
            arrayOf(configuration)
        )
    )
}

fun ConfigurationContainer.computeByNameIfAbsent(name: String, setup: (configuration: Configuration) -> Unit): Configuration =
    findByName(name) ?: create(name).also(setup)
