package net.msrandom.minecraftcodev.fabric.jarinjar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import net.msrandom.minecraftcodev.includes.IncludedJar
import net.msrandom.minecraftcodev.includes.IncludedJarListingRule
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class FabricJarInJarRule : IncludedJarListingRule {
    override fun load(directory: Path): ListedFileHandler<IncludedJar>? {
        val mod = directory.resolve(MinecraftCodevFabricPlugin.MOD_JSON)

        if (!mod.exists()) {
            return null
        }

        mod.inputStream().use {
            val json = Json.decodeFromStream<JsonObject>(it)
            val mixinsElement = json["jars"]?.jsonArray

            return if (mixinsElement != null) {
                FabricJarInJarHandler(mixinsElement, json, MinecraftCodevFabricPlugin.MOD_JSON)
            } else {
                null
            }
        }
    }
}
