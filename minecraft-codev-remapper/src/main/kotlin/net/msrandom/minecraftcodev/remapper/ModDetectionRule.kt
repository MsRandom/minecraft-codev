package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.extension

fun interface ModDetectionRule {
    operator fun invoke(fileSystem: FileSystem): Boolean
}

class VanillaModDetectionRule : ModDetectionRule {
    override fun invoke(fileSystem: FileSystem) =
        fileSystem.getPath("pack.mcmeta").exists() ||
            fileSystem.getPath("version.json").exists() ||
            fileSystem.getPath("assets/lang/en_us.json").exists() ||
            fileSystem.getPath("assets/lang/en_us.lang").exists() ||
            fileSystem.getPath("assets/lang/en_US.lang").exists()
}

val modDetectionRules = ServiceLoader.load(ModDetectionRule::class.java).toList()

fun isMod(path: Path): Boolean {
    val extension = path.extension

    if (extension != "zip" && extension != "jar") {
        return false
    }

    return zipFileSystem(path).use { (fs) ->
        modDetectionRules.any { it(fs) }
    }
}
