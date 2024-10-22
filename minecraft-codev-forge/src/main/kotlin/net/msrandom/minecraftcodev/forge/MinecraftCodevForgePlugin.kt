package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.forge.runs.setupForgeRunsIntegration
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.SourceSet
import java.io.File
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream

val SourceSet.patchesConfigurationName get() = disambiguateName(MinecraftCodevForgePlugin.PATCHES_CONFIGURATION)

open class MinecraftCodevForgePlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            createSourceSetConfigurations(PATCHES_CONFIGURATION)

            setupForgeRunsIntegration()
        }

    companion object {
        const val SRG_MAPPINGS_NAMESPACE = "srg"
        const val PATCHES_CONFIGURATION = "patches"

        internal const val FORGE_MODS_TOML = "mods.toml"
        internal const val NEOFORGE_MODS_TOML = "neoforge.mods.toml"

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
