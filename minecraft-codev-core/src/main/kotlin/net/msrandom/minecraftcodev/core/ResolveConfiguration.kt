package net.msrandom.minecraftcodev.core

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

open class ResolveConfiguration @Inject constructor(private val configuration: Configuration) : DefaultTask() {
    init {
        apply {
            dependsOn(configuration)
        }
    }

    @TaskAction
    fun resolve() {
        configuration.resolve()
    }
}
