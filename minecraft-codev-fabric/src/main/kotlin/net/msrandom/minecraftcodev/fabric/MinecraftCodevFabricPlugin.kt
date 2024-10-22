package net.msrandom.minecraftcodev.fabric

import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.fabric.runs.setupFabricRunsIntegration
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware

class MinecraftCodevFabricPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            setupFabricRunsIntegration()
        }

    companion object {
        const val INTERMEDIARY_MAPPINGS_NAMESPACE = "intermediary"

        const val MOD_JSON = "fabric.mod.json"
    }
}
