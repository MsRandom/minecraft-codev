package net.msrandom.minecraftcodev.fabric.mappings

import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin.Companion.MOD_JSON
import net.msrandom.minecraftcodev.remapper.ModDetectionRule
import java.nio.file.FileSystem
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class FabricModDetectionRule : ModDetectionRule {
    override fun invoke(fileSystem: FileSystem): Boolean {
        if (fileSystem.getPath(MOD_JSON).exists()) {
            return true
        }

        val manifestPath = fileSystem.getPath(JarFile.MANIFEST_NAME)

        if (manifestPath.notExists()) {
            return false
        }

        return manifestPath
            .inputStream()
            .use(::Manifest)
            .mainAttributes
            .keys
            .any {
                it.toString().startsWith("Fabric")
            }
    }
}
