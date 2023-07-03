package net.msrandom.minecraftcodev.fabric.mixin

import kotlinx.serialization.json.*
import net.msrandom.minecraftcodev.mixins.MixinConfigHandler
import java.nio.file.Path
import kotlin.io.path.outputStream

class FabricMixinConfigHandler(mixins: JsonElement, private val json: JsonObject, private val jsonName: String) : MixinConfigHandler {
    private val paths = if (mixins is JsonArray) {
        mixins.map { if (it is JsonObject) it["config"]?.jsonPrimitive?.content!! else it.jsonPrimitive.content }
    } else {
        listOf(mixins.jsonPrimitive.content)
    }

    override fun list(root: Path) = paths

    override fun remove(root: Path) {
        root.resolve(jsonName).outputStream().use { output ->
            Companion.json.encodeToStream(JsonObject(json.filterNot { it.key == "mixins" }), output)
        }
    }

    companion object {
        private val json = Json { prettyPrint = true }
    }
}
