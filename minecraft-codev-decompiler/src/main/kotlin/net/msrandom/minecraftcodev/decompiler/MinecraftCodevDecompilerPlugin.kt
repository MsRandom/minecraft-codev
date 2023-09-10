package net.msrandom.minecraftcodev.decompiler

import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.decompiler.dependency.DecompiledIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.decompiler.resolve.DecompiledComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevDecompilerPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "decompiled",
        DecompiledIvyDependencyDescriptorFactory::class.java,
        DecompiledComponentResolvers::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {}
}
