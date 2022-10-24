package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension.Companion.json
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import java.io.File
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream

open class PatcherExtension(internal val project: Project) {
    companion object {
        val PATCHED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("net.msrandom.minecraftcodev.patched", Boolean::class.javaObjectType)
        val FORGE_TRANSFORMED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("net.msrandom.minecraftcodev.transformed", Boolean::class.javaObjectType)

        val ARTIFACT_TYPE: Attribute<String> = Attribute.of("artifactType", String::class.java)

        const val PATCHES_CONFIGURATION = "patches"

        internal fun userdevConfig(file: File, action: FileSystem.(config: UserdevConfig) -> Unit) = MinecraftCodevPlugin.zipFileSystem(file.toPath()).use { fs ->
            val configPath = fs.getPath("config.json")
            if (configPath.exists()) {
                fs.action(configPath.inputStream().use(json::decodeFromStream))
                true
            } else {
                false
            }
        }
    }
}
