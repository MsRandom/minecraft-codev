package net.msrandom.minecraftcodev.forge.mixin

import com.electronwill.nightconfig.core.Config
import com.electronwill.nightconfig.core.file.FileConfig
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.NEOFORGE_MODS_TOML
import net.msrandom.minecraftcodev.mixins.MixinListingRule
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class ForgeMixinListingRule : MixinListingRule {
    private fun loadFromToml(directory: Path): ListedFileHandler? {
        val tomlPath = directory.resolve("META-INF").resolve(NEOFORGE_MODS_TOML)

        if (tomlPath.notExists()) {
            return null
        }

        val mixins = FileConfig.of(tomlPath).getOrElse<List<Config>>(listOf(NEOFORGE_MIXINS_FIELD), ::ArrayList)

        if (mixins.isNotEmpty()) {
            val mixinConfigs = mixins.map {
                it.get<String>(listOf("config"))
            }

            return ForgeMixinConfigHandler(mixinConfigs, false)
        }

        return null
    }

    override fun load(directory: Path): ListedFileHandler<String>? {
        val manifestPath = directory.resolve(JarFile.MANIFEST_NAME)

        if (manifestPath.notExists()) {
            return loadFromToml(directory)
        }

        val manifest = manifestPath.inputStream().use(::Manifest)

        val mixinConfigsString = manifest.mainAttributes.getValue(MANIFEST_MIXINS_CONFIG)
            ?: return loadFromToml(directory)

        val mixinConfigs = mixinConfigsString.split(",").map(String::trim)

        return ForgeMixinConfigHandler(mixinConfigs, true)
    }
}
