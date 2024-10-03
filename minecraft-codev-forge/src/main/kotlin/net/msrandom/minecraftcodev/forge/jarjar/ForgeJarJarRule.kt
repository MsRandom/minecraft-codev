package net.msrandom.minecraftcodev.forge.jarjar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.includes.IncludedJar
import net.msrandom.minecraftcodev.includes.IncludedJarListingRule
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class ForgeJarJarRule : IncludedJarListingRule {
    override fun load(directory: Path): ListedFileHandler? {
        val metadataPath = directory.resolve("META-INF").resolve("jarjar").resolve("metadata.json")

        if (metadataPath.notExists()) {
            return null
        }

        metadataPath.inputStream().use {
            val json = Json.decodeFromStream<JsonObject>(it)
            val jars = json["jars"]?.jsonArray

            return if (jars != null) {
                ForgeJarJarHandler(jars)
            } else {
                null
            }
        }
    }
}
