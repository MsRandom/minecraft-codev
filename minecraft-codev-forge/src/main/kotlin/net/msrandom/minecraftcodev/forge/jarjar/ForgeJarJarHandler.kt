package net.msrandom.minecraftcodev.forge.jarjar

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.msrandom.minecraftcodev.core.ListedFileHandler
import java.nio.file.Path
import kotlin.io.path.deleteExisting

class ForgeJarJarHandler(jars: JsonArray) : ListedFileHandler {
    private val jars =
        jars.map {
            it.jsonObject["path"]?.jsonPrimitive?.content!!
        }

    override fun list(root: Path) = jars

    override fun remove(root: Path) {
        root.resolve("META-INF").resolve("jarjar").resolve("metadata.json").deleteExisting()
    }
}
