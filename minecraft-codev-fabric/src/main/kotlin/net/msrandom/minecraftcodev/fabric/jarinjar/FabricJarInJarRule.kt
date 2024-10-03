package net.msrandom.minecraftcodev.fabric.jarinjar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import net.msrandom.minecraftcodev.includes.IncludedJarListingRule
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class FabricJarInJarRule : IncludedJarListingRule {
    override fun load(directory: Path): ListedFileHandler? {
        val mod = directory.resolve(MinecraftCodevFabricPlugin.MOD_JSON)

        if (mod.notExists()) {
            return null
        }

        mod.inputStream().use {
            val json = Json.decodeFromStream<JsonObject>(it)
            val jarsElement = json["jars"]?.jsonArray

            return if (jarsElement != null) {
                FabricJarInJarHandler(jarsElement, json, MinecraftCodevFabricPlugin.MOD_JSON)
            } else {
                null
            }
        }
    }
}
