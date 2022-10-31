package net.msrandom.minecraftcodev.runs.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.runs.MinecraftRunConfiguration
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.DefaultTask
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.tasks.TaskAction

abstract class GenerateRuns : DefaultTask() {
    init {
        group = ApplicationPlugin.APPLICATION_GROUP
    }

    @TaskAction
    fun generate() {
        generateRuns(project
            .extensions
            .getByType(MinecraftCodevExtension::class.java)
            .extensions
            .getByType(RunsContainer::class.java)
            .map { it.name to it.build(project) }
        )
    }

    protected abstract fun generateRuns(runs: List<Pair<String, MinecraftRunConfiguration>>)
}
