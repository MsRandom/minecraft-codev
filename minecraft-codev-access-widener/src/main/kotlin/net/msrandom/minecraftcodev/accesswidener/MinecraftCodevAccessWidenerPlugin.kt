package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedDependencyMetadataConverter
import net.msrandom.minecraftcodev.accesswidener.resolve.AccessWidenedComponentResolvers
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevAccessWidenerPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) =
        gradle.registerCustomDependency(
            "access-widened",
            AccessWidenedDependencyMetadataConverter::class.java,
            AccessWidenedComponentResolvers::class.java,
        )

    override fun apply(target: T) =
        applyPlugin(target, ::applyGradle) {
            createSourceSetConfigurations(ACCESS_WIDENERS_CONFIGURATION)

            extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("accessWidener", AccessWidenerExtension::class.java)
        }

    companion object {
        const val ACCESS_WIDENERS_CONFIGURATION = "accessWideners"
    }
}
