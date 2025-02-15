package net.msrandom.minecraftcodev.forge.mappings

import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.FORGE_MODS_TOML
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.NEOFORGE_MODS_TOML
import net.msrandom.minecraftcodev.remapper.ModDetectionRule
import java.nio.file.FileSystem
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class ForgeModDetectionRule : ModDetectionRule {
    override fun invoke(fileSystem: FileSystem): Boolean {
        val hasMetadata = fileSystem.getPath("META-INF", FORGE_MODS_TOML).exists() ||
                fileSystem.getPath("META-INF", NEOFORGE_MODS_TOML).exists() ||
                fileSystem.getPath("mcmod.info").exists()

        if (hasMetadata) {
            return true
        }

        val manifestPath = fileSystem.getPath(JarFile.MANIFEST_NAME)

        if (manifestPath.notExists()) {
            return false
        }

        val manifest = manifestPath.inputStream().use(::Manifest)

        return manifest.mainAttributes.keys.any {
            "fml" in it.toString().lowercase() || "forge" in it.toString().lowercase()
        }
    }
}
