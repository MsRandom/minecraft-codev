package net.msrandom.minecraftcodev.fabric

import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.fabric.accesswidener.setupFabricAccessWidenerIntegration
import net.msrandom.minecraftcodev.fabric.jarinjar.setupFabricIncludesIntegration
import net.msrandom.minecraftcodev.fabric.mappings.setupFabricRemapperIntegration
import net.msrandom.minecraftcodev.fabric.mixin.setupFabricMixinsIntegration
import net.msrandom.minecraftcodev.fabric.runs.setupFabricRunsIntegration
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware

class MinecraftCodevFabricPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            setupFabricAccessWidenerIntegration()
            setupFabricMixinsIntegration()
            setupFabricRemapperIntegration()
            setupFabricIncludesIntegration()
            setupFabricRunsIntegration()
        }

    companion object {
        const val INTERMEDIARY_MAPPINGS_NAMESPACE = "intermediary"

        const val MOD_JSON = "fabric.mod.json"
    }
}
