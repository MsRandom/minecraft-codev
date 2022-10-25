package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.applyPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.createSourceSetConfigurations
import net.msrandom.minecraftcodev.remapper.dependency.RemappedIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.remapper.resolve.RemappedComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import kotlin.io.path.inputStream

class MinecraftCodevRemapperPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        MinecraftCodevPlugin.registerCustomDependency("remapped", gradle, RemappedIvyDependencyDescriptorFactory::class.java, RemappedComponentResolvers::class.java)
    }

    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        applyPlugin(target, ::applyGradle) {
            val remapper = extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("remapper", RemapperExtension::class.java)

            createSourceSetConfigurations(MAPPINGS_CONFIGURATION)

            remapper.mappingsResolution { path, extension, visitor, decorate, _ ->
                if (extension == "txt") {
                    path.inputStream().decorate().reader().use {
                        ProGuardReader.read(it, NAMED_MAPPINGS_NAMESPACE, MappingsNamespace.OBF, MappingSourceNsSwitch(visitor, MappingsNamespace.OBF))
                    }

                    true
                } else {
                    false
                }
            }
        }
    }

    companion object {
        const val NAMED_MAPPINGS_NAMESPACE = "named"
        const val MAPPINGS_CONFIGURATION = "mappings"
    }
}
