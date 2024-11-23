package net.msrandom.minecraftcodev.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(ModuleLibraryIdentifier.IdentifierSerializer::class)
data class ModuleLibraryIdentifier(val group: String, val module: String, val version: String, val classifier: String?) {
    override fun toString() = buildString {
        append(group)
        append(':')

        append(module)
        append(':')

        append(version)

        if (classifier != null) {
            append(':')
            append(classifier)
        }
    }

    companion object {
        fun load(notation: String) = notation.split(":").let {
            ModuleLibraryIdentifier(it[0], it[1], it[2], it.getOrNull(3))
        }
    }

    @Serializer(ModuleLibraryIdentifier::class)
    class IdentifierSerializer : KSerializer<ModuleLibraryIdentifier> {
        override fun deserialize(decoder: Decoder) = load(decoder.decodeString())

        override fun serialize(
            encoder: Encoder,
            value: ModuleLibraryIdentifier,
        ) = encoder.encodeString(value.toString())
    }
}
