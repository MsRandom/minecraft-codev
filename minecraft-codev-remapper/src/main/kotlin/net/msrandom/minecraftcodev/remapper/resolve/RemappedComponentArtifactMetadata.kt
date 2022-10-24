package net.msrandom.minecraftcodev.remapper.resolve

import org.gradle.internal.component.model.ComponentArtifactMetadata

class RemappedComponentArtifactMetadata(val delegate: ComponentArtifactMetadata, private val id: RemappedComponentIdentifier) : ComponentArtifactMetadata by delegate {
    override fun getComponentId() = id
}
