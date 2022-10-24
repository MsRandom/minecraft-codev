package net.msrandom.minecraftcodev.core.task.runs

import net.msrandom.minecraftcodev.core.minecraftCodev
import net.msrandom.minecraftcodev.core.runs.MinecraftRunConfiguration
import org.gradle.api.DefaultTask
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.tasks.TaskAction

abstract class GenerateRuns : DefaultTask() {
    init {
        group = ApplicationPlugin.APPLICATION_GROUP
    }

    @TaskAction
    fun generate() {
        generateRuns(project.minecraftCodev.runs.map { it.name to it.build(project) })
    }

    protected abstract fun generateRuns(runs: List<Pair<String, MinecraftRunConfiguration>>)
}
