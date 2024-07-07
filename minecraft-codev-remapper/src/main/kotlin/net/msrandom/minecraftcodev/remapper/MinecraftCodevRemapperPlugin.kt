package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware

class MinecraftCodevRemapperPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            extension<MinecraftCodevExtension>().extensions.create("remapper", RemapperExtension::class.java)

            createSourceSetConfigurations(MAPPINGS_CONFIGURATION)
        }

    companion object {
        const val NAMED_MAPPINGS_NAMESPACE = "named"
        const val MAPPINGS_CONFIGURATION = "mappings"
    }
}
