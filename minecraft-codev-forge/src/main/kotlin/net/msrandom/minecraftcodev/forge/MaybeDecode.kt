package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

inline fun <reified T> Json.maybeDecode(stream: InputStream): T? = try {
    decodeFromStream(stream)
} catch (_: SerializationException) {
    null
}
