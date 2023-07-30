package net.msrandom.minecraftcodev.mixins

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createCompilationConfigurations
import net.msrandom.minecraftcodev.mixins.dependency.MixinIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.mixins.dependency.SKipMixinsIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.mixins.mixin.GradleMixinService
import net.msrandom.minecraftcodev.mixins.resolve.MixinComponentResolvers
import net.msrandom.minecraftcodev.mixins.resolve.SkipMixinsComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.service.MixinService

class MinecraftCodevMixinsPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        gradle.registerCustomDependency(
            "mixin",
            MixinIvyDependencyDescriptorFactory::class.java,
            MixinComponentResolvers::class.java
        )

        gradle.registerCustomDependency(
            "skip-mixins",
            SKipMixinsIvyDependencyDescriptorFactory::class.java,
            SkipMixinsComponentResolvers::class.java
        )
    }

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        createCompilationConfigurations(MIXINS_CONFIGURATION, true)

        extensions.getByType(MinecraftCodevExtension::class.java).extensions.create("mixins", MixinsExtension::class.java)

        MixinBootstrap.init()
        (MixinService.getService() as GradleMixinService).phaseConsumer.accept(MixinEnvironment.Phase.DEFAULT)
    }

    companion object {
        const val MIXINS_CONFIGURATION = "mixins"
    }
}