package net.msrandom.minecraftcodev.forge.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.runs.MinecraftCodevRunsPlugin
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.Project

internal fun Project.setupForgeRunsIntegration() {
    plugins.withType(MinecraftCodevRunsPlugin::class.java) {
        val defaults = extension<MinecraftCodevExtension>().extension<RunsContainer>().extension<RunConfigurationDefaultsContainer>()

        defaults.extensions.create("forge", ForgeRunsDefaultsContainer::class.java, defaults)
    }
}
