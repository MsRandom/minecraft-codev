package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.remapper.resolve.RemappedComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

class MinecraftCodevRemapperPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        MinecraftCodevPlugin.registerCustomDependency("remapped", gradle, RemappedIvyDependencyDescriptorFactory::class.java, RemappedComponentResolvers::class.java)
    }

    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        when (target) {
            is Gradle -> {
                applyGradle(target)

                target.allprojects {
                    it.plugins.apply(javaClass)
                }
            }
            is Settings -> target.gradle.plugins.apply(javaClass)
            is Project -> {
                applyGradle(target.gradle)

                target.extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("remapper", RemapperExtension::class.java)
            }
        }
    }

    companion object {
        const val NAMED_MAPPINGS_NAMESPACE = "named"
    }
}
