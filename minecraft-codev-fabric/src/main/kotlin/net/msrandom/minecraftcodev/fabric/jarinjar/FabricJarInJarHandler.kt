package net.msrandom.minecraftcodev.fabric.jarinjar

import kotlinx.serialization.json.*
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.includes.IncludedJar
import java.nio.file.Path
import kotlin.io.path.outputStream

class FabricJarInJarHandler(jars: JsonArray, private val json: JsonObject, private val jsonName: String) : ListedFileHandler<IncludedJar> {
    private val jars = jars.map {
        val path = it.jsonObject["file"]?.jsonPrimitive?.content!!

        IncludedJar(path, null, null, null)
    }

    override fun list(root: Path) = jars

    override fun remove(root: Path) {
        root.resolve(jsonName).outputStream().use { output ->
            Companion.json.encodeToStream(JsonObject(json.filterNot { it.key == "jars" }), output)
        }
    }

    companion object {
        private val json = Json { prettyPrint = true }
    }
}
