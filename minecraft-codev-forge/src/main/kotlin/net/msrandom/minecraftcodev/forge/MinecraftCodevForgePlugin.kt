package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.ModInfo
import net.msrandom.minecraftcodev.core.ModInfoType
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.accesswidener.setupForgeAccessWidenerIntegration
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependencyMetadataConverter
import net.msrandom.minecraftcodev.forge.mappings.setupForgeRemapperIntegration
import net.msrandom.minecraftcodev.forge.resolve.PatchedMinecraftComponentResolvers
import net.msrandom.minecraftcodev.forge.runs.setupForgeRunsIntegration
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.SourceSet
import java.io.File
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream

val SourceSet.patchesConfigurationName get() = disambiguateName(MinecraftCodevForgePlugin.PATCHES_CONFIGURATION)

open class MinecraftCodevForgePlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) =
        gradle.registerCustomDependency(
            "patched",
            PatchedMinecraftDependencyMetadataConverter::class.java,
            PatchedMinecraftComponentResolvers::class.java,
        )

    override fun apply(target: T) =
        applyPlugin(target, ::applyGradle) {
            createSourceSetConfigurations(PATCHES_CONFIGURATION)

            val codev = extensions.getByType(MinecraftCodevExtension::class.java)
            codev.extensions.create("patched", PatchedMinecraftCodevExtension::class.java)

            setupForgeAccessWidenerIntegration()
            setupForgeRemapperIntegration()
            setupForgeRunsIntegration()

            fun isForge(fs: FileSystem) = fs.getPath("META-INF", "mods.toml").exists() || fs.getPath("mcmod.info").exists()

            codev.modInfoDetectionRules.add {
                if (isForge(it)) {
                    ModInfo(ModInfoType.Platform, "forge", 3)
                } else {
                    null
                }
            }

            codev.modInfoDetectionRules.add {
                if (isForge(it)) {
                    ModInfo(ModInfoType.Namespace, SRG_MAPPINGS_NAMESPACE, 2)
                } else {
                    null
                }
            }
        }

    companion object {
        const val SRG_MAPPINGS_NAMESPACE = "srg"
        const val PATCHES_CONFIGURATION = "patches"

        internal fun userdevConfig(
            file: File,
            action: FileSystem.(config: UserdevConfig) -> Unit,
        ) = zipFileSystem(file.toPath()).use { fs ->
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
