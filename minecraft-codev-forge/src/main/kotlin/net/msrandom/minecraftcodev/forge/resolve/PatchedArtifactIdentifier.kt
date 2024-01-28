package net.msrandom.minecraftcodev.forge.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.forge.dependency.FmlLoaderWrappedComponentIdentifier
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class PatchedArtifactIdentifier(val id: ComponentArtifactIdentifier, private val patchesHash: HashCode) {
    object ArtifactSerializer : Serializer<PatchedArtifactIdentifier> {
        override fun read(decoder: Decoder) = PatchedArtifactIdentifier(MinecraftArtifactResolver.artifactIdSerializer.read(decoder), HashCode.fromBytes(decoder.readBinary()))

        override fun write(encoder: Encoder, value: PatchedArtifactIdentifier) {
            MinecraftArtifactResolver.artifactIdSerializer.write(encoder, value.id)
            encoder.writeBinary(value.patchesHash.toByteArray())
        }
    }
}

class FmlLoaderWrappedMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: FmlLoaderWrappedComponentIdentifier
) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier = delegate.id.let {
        if (it is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, it.name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
        }
    }

    override fun getComponentId() = id
}
