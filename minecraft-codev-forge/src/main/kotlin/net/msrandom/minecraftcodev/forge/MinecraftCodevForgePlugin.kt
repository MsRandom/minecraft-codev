package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.ModMappingNamespaceInfo
import net.msrandom.minecraftcodev.core.ModPlatformInfo
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createTargetConfigurations
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.forge.mappings.setupForgeRemapperIntegration
import net.msrandom.minecraftcodev.forge.resolve.PatchedMinecraftComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import java.io.File
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream

open class MinecraftCodevForgePlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "patched",
        PatchedMinecraftIvyDependencyDescriptorFactory::class.java,
        PatchedMinecraftComponentResolvers::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        createTargetConfigurations(PATCHES_CONFIGURATION)

        val codev = extensions.getByType(MinecraftCodevExtension::class.java)
        codev.extensions.create("patched", PatchedMinecraftCodevExtension::class.java)

        setupForgeRemapperIntegration()
        setupForgeRunsIntegration()

        fun isForge(fs: FileSystem) = fs.getPath("META-INF", "mods.toml").exists() || fs.getPath("mcmod.info").exists()

        codev.modInfoDetectionRules.add {
            if (isForge(it)) {
                ModPlatformInfo("forge", 3)
            } else {
                null
            }
        }

        codev.modInfoDetectionRules.add {
            if (isForge(it)) {
                ModMappingNamespaceInfo(SRG_MAPPINGS_NAMESPACE, 2)
            } else {
                null
            }
        }
    }

    companion object {
        const val SRG_MAPPINGS_NAMESPACE = "srg"
        const val PATCHES_CONFIGURATION = "patches"

        internal fun userdevConfig(file: File, action: FileSystem.(config: UserdevConfig) -> Unit) = zipFileSystem(file.toPath()).use { fs ->
            val configPath = fs.base.getPath("config.json")
            if (configPath.exists()) {
                fs.base.action(configPath.inputStream().use(json::decodeFromStream))
                true
            } else {
                false
            }
        }
    }
}
