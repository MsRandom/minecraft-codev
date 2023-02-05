package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedDependencyFactory
import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedDependencyMetadata
import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.accesswidener.resolve.AccessWidenedComponentResolvers
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevAccessWidenerPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        gradle.registerCustomDependency(
            "accessWidened",
            AccessWidenedIvyDependencyDescriptorFactory::class.java,
            AccessWidenedDependencyFactory::class.java,
            AccessWidenedComponentResolvers::class.java,
            AccessWidenedDependencyMetadata::class.java
        )
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
