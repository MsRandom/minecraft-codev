package net.msrandom.minecraftcodev.intersection

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.intersection.dependency.IntersectionDependencyMetadataConverter
import net.msrandom.minecraftcodev.intersection.resolve.IntersectionComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevIntersectionPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) =
        gradle.registerCustomDependency(
            "intersection",
            IntersectionDependencyMetadataConverter::class.java,
            IntersectionComponentResolvers::class.java,
        )

    override fun apply(target: T) =
        applyPlugin(target, ::applyGradle) {
            extensions.getByType(
                MinecraftCodevExtension::class.java,
            ).extensions.create("intersection", IntersectionMinecraftCodevExtension::class.java)
        }
}
