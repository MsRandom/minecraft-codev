package net.msrandom.minecraftcodev.forge

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.forge.runs.ForgeRunsDefaultsContainer
import net.msrandom.minecraftcodev.runs.MinecraftCodevRunsPlugin
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

private inline fun <reified T> ExtensionAware.extension() = extensions.getByType(T::class.java)

internal fun Project.setupForgeRunsIntegration() {
    plugins.withType(MinecraftCodevRunsPlugin::class.java) {
        val defaults = extension<MinecraftCodevExtension>().extension<RunsContainer>().extension<RunConfigurationDefaultsContainer>()

        defaults.extensions.create("forge", ForgeRunsDefaultsContainer::class.java, defaults)
    }
}
