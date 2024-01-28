package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.accesswidener.dependency.accesswidened.AccessWidenedDependencyMetadataConverter
import net.msrandom.minecraftcodev.accesswidener.dependency.intersection.AccessModifierIntersectionDependencyMetadataConverter
import net.msrandom.minecraftcodev.accesswidener.resolve.accesswidened.AccessWidenedComponentResolvers
import net.msrandom.minecraftcodev.accesswidener.resolve.intersection.AccessModifierIntersectionComponentResolvers
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createCompilationConfigurations
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevAccessWidenerPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        gradle.registerCustomDependency(
            "access-widened",
            AccessWidenedDependencyMetadataConverter::class.java,
            AccessWidenedComponentResolvers::class.java
        )

        gradle.registerCustomDependency(
            "access-modifier-intersection",
            AccessModifierIntersectionDependencyMetadataConverter::class.java,
            AccessModifierIntersectionComponentResolvers::class.java
        )
    }

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        createCompilationConfigurations(ACCESS_WIDENERS_CONFIGURATION)

        extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("accessWidener", AccessWidenerExtension::class.java)
    }

    companion object {
        const val ACCESS_WIDENERS_CONFIGURATION = "accessWideners"
    }
}
