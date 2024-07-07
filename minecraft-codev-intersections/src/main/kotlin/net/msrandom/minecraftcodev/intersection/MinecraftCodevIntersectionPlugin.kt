package net.msrandom.minecraftcodev.intersection

import net.msrandom.minecraftcodev.core.utils.applyPlugin
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware

class MinecraftCodevIntersectionPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) = applyPlugin(target)
}
