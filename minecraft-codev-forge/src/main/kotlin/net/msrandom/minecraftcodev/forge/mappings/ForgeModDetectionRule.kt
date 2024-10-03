package net.msrandom.minecraftcodev.forge.mappings

import net.msrandom.minecraftcodev.remapper.ModDetectionRule
import java.nio.file.FileSystem
import kotlin.io.path.exists

class ForgeModDetectionRule : ModDetectionRule {
    override fun invoke(fileSystem: FileSystem) =
        fileSystem.getPath("META-INF", "mods.toml").exists() ||
            fileSystem.getPath("META-INF", "neoforge.mods.toml").exists() ||
            fileSystem.getPath("mcmod.info").exists()
}
