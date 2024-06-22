package net.msrandom.minecraftcodev.fabric.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.runs.MinecraftCodevRunsPlugin
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.Project

fun Project.setupFabricRunsIntegration() {
    plugins.withType(MinecraftCodevRunsPlugin::class.java) {
        val defaults = extensions.getByType(MinecraftCodevExtension::class.java)
            .extensions.getByType(RunsContainer::class.java)
            .extensions.getByType(RunConfigurationDefaultsContainer::class.java)

        defaults.extensions.create("fabric", FabricRunsDefaultsContainer::class.java, defaults)
    }
}
