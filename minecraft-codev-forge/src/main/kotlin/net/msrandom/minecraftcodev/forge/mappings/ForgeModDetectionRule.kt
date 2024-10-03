package net.msrandom.minecraftcodev.forge.mappings

import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.FORGE_MODS_TOML
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.NEOFORGE_MODS_TOML
import net.msrandom.minecraftcodev.remapper.ModDetectionRule
import java.nio.file.FileSystem
import kotlin.io.path.exists

class ForgeModDetectionRule : ModDetectionRule {
    override fun invoke(fileSystem: FileSystem) =
        fileSystem.getPath("META-INF", FORGE_MODS_TOML).exists() ||
            fileSystem.getPath("META-INF", NEOFORGE_MODS_TOML).exists() ||
            fileSystem.getPath("mcmod.info").exists()
}
