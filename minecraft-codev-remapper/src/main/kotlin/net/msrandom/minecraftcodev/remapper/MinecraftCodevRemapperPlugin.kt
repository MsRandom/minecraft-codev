package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createTargetConfigurations
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadataConverter
import net.msrandom.minecraftcodev.remapper.resolve.RemappedComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevRemapperPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "remapped",
        RemappedDependencyMetadataConverter::class.java,
        RemappedComponentResolvers::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("remapper", RemapperExtension::class.java)
        createTargetConfigurations(MAPPINGS_CONFIGURATION)
    }

    companion object {
        const val NAMED_MAPPINGS_NAMESPACE = "named"
        const val MAPPINGS_CONFIGURATION = "mappings"
    }
}
