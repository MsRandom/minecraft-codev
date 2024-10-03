package net.msrandom.minecraftcodev.fabric.mixin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import net.msrandom.minecraftcodev.mixins.MixinListingRule
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class FabricMixinListingRule : MixinListingRule {
    override fun load(directory: Path): ListedFileHandler? {
        val mod = directory.resolve(MinecraftCodevFabricPlugin.MOD_JSON)

        if (!mod.exists()) {
            return null
        }

        return mod.inputStream().use {
            val json = Json.decodeFromStream<JsonObject>(it)
            val mixinsElement = json["mixins"]

            if (mixinsElement != null) {
                FabricMixinConfigHandler(mixinsElement, json, MinecraftCodevFabricPlugin.MOD_JSON)
            } else {
                null
            }
        }
    }
}
