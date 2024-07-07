package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.SourceSet

val SourceSet.accessWidenersConfigurationName get() = disambiguateName(MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION)

class MinecraftCodevAccessWidenerPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            createSourceSetConfigurations(ACCESS_WIDENERS_CONFIGURATION)

            extension<MinecraftCodevExtension>().extensions.create("accessWidener", AccessWidenerExtension::class.java)
        }

    companion object {
        const val ACCESS_WIDENERS_CONFIGURATION = "accessWideners"
    }
}
