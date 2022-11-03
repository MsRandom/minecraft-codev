package net.msrandom.minecraftcodev.core

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction

open class ResolveConfiguration(private val configuration: Configuration) : DefaultTask() {
    @TaskAction
    fun resolve() {
        configuration.resolve()
    }
}
