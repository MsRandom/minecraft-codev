package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.PluginAware

open class MinecraftCodevPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            plugins.apply(JavaPlugin::class.java)
        }

    companion object {
        val json =
            Json {
                ignoreUnknownKeys = true
            }
    }
}
