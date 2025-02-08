package net.msrandom.minecraftcodev.forge.mixin

import com.electronwill.nightconfig.core.Config
import com.electronwill.nightconfig.core.file.FileConfig
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.NEOFORGE_MODS_TOML
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

internal const val MANIFEST_MIXINS_CONFIG = "MixinConfigs"
internal const val NEOFORGE_MIXINS_FIELD = "mixins"

class ForgeMixinConfigHandler(private val names: List<String>, private val inManifest: Boolean) : ListedFileHandler {
    override fun list(root: Path) = names

    override fun remove(root: Path) {
        if (inManifest) {
            val manifestPath = root.resolve(JarFile.MANIFEST_NAME)
            val manifest = manifestPath.inputStream().use(::Manifest)

            manifest.mainAttributes.remove(MANIFEST_MIXINS_CONFIG)

            manifestPath.outputStream().use(manifest::write)
        } else {
            FileConfig.of(root.resolve("META-INF").resolve(NEOFORGE_MODS_TOML)).remove<List<Config>>(listOf(NEOFORGE_MIXINS_FIELD))
        }
    }
}
