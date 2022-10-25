package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.accesswidener.resolve.AccessWidenedComponentResolvers
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.applyPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.createSourceSetConfigurations
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevAccessWidenerPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        MinecraftCodevPlugin.registerCustomDependency("accessWidened", gradle, AccessWidenedIvyDependencyDescriptorFactory::class.java, AccessWidenedComponentResolvers::class.java)
    }

    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        applyPlugin(target, ::applyGradle) {
            createSourceSetConfigurations(ACCESS_WIDENERS_CONFIGURATION)
        }
    }

    companion object {
        const val ACCESS_WIDENERS_CONFIGURATION = "accessWideners"
    }
}
