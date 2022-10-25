package net.msrandom.minecraftcodev.runs.task

import net.msrandom.minecraftcodev.core.minecraftCodev
import net.msrandom.minecraftcodev.runs.MinecraftRunConfiguration
import net.msrandom.minecraftcodev.runs.MinecraftRunConfigurationBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.reflect.TypeOf
import org.gradle.api.tasks.TaskAction

abstract class GenerateRuns : DefaultTask() {
    init {
        group = ApplicationPlugin.APPLICATION_GROUP
    }

    @TaskAction
    fun generate() {
        generateRuns(project.minecraftCodev
            .extensions
            .getByType(object : TypeOf<NamedDomainObjectContainer<MinecraftRunConfigurationBuilder>>() {})
            .map { it.name to it.build(project) }
        )
    }

    protected abstract fun generateRuns(runs: List<Pair<String, MinecraftRunConfiguration>>)
}
