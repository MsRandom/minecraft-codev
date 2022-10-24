package net.msrandom.minecraftcodev.core.caches

import org.gradle.internal.serialize.AbstractSerializer
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder

sealed interface ModuleMetadataCacheEntry {
    val createTimestamp: Long

    data class Missing(override val createTimestamp: Long) : ModuleMetadataCacheEntry
    data class Present(override val createTimestamp: Long, val isChanging: Boolean) : ModuleMetadataCacheEntry

    object Serializer : AbstractSerializer<ModuleMetadataCacheEntry>() {
        override fun read(decoder: Decoder): ModuleMetadataCacheEntry {
            return if (decoder.readBoolean()) {
                Present(decoder.readLong(), decoder.readBoolean())
            } else {
                Missing(decoder.readLong())
            }
        }

        override fun write(encoder: Encoder, value: ModuleMetadataCacheEntry) {
            if (value is Present) {
                encoder.writeBoolean(true)
                encoder.writeLong(value.createTimestamp)
                encoder.writeBoolean(value.isChanging)
            } else {
                encoder.writeBoolean(false)
                encoder.writeLong(value.createTimestamp)
            }
        }
    }
}
