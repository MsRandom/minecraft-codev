package net.msrandom.minecraftcodev.mixins

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.mixins.mixin.GradleMixinService
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.SourceSet
import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.service.MixinService

val SourceSet.mixinsConfigurationName get() = disambiguateName(MinecraftCodevMixinsPlugin.MIXINS_CONFIGURATION)

class MinecraftCodevMixinsPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            createSourceSetConfigurations(MIXINS_CONFIGURATION, true)

            extension<MinecraftCodevExtension>().extensions.create("mixins", MixinsExtension::class.java)

            MixinBootstrap.init()
            (MixinService.getService() as GradleMixinService).phaseConsumer.accept(MixinEnvironment.Phase.DEFAULT)
        }

    companion object {
        const val MIXINS_CONFIGURATION = "mixins"
    }
}
