package net.msrandom.minecraftcodev.remapper.resolve

import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata

class RemappedComponentArtifactMetadata(val delegate: ModuleComponentArtifactMetadata, private val id: RemappedComponentIdentifier) : ModuleComponentArtifactMetadata by delegate {
    override fun getComponentId() = id
}
