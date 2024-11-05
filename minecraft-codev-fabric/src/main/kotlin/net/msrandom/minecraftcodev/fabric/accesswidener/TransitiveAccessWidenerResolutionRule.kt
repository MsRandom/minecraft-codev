package net.msrandom.minecraftcodev.fabric.accesswidener

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.accesswidener.AccessWidenerReader
import net.msrandom.minecraftcodev.accesswidener.AccessModifierResolutionData
import net.msrandom.minecraftcodev.accesswidener.ZipAccessModifierResolutionRule
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class TransitiveAccessWidenerResolutionRule : ZipAccessModifierResolutionRule {
    override suspend fun load(path: Path, fileSystem: FileSystem, isJar: Boolean, data: AccessModifierResolutionData): Boolean {
        val mod = fileSystem.getPath(MinecraftCodevFabricPlugin.MOD_JSON)

        if (!mod.exists()) {
            return false
        }

        mod.inputStream().use {
            val json = Json.decodeFromStream<JsonObject>(it)
            val accessWidenerPath = json["accessWidener"]?.jsonPrimitive?.content ?: return false

            fileSystem.getPath(accessWidenerPath).inputStream().use {
                AccessWidenerReader(data.visitor.onlyTransitives()).read(it.bufferedReader(), data.namespace)
            }

            return true
        }
    }
}
