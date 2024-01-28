package net.msrandom.minecraftcodev.includes

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.includes.dependency.ExtractIncludesDependencyMetadataConverter
import net.msrandom.minecraftcodev.includes.resolve.ExtractIncludesComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevIncludesPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "includes-extracted",
        ExtractIncludesDependencyMetadataConverter::class.java,
        ExtractIncludesComponentResolvers::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("includes", IncludesExtension::class.java)
    }
}
