package net.msrandom.minecraftcodev.accesswidener.resolve

import org.gradle.internal.component.model.ComponentArtifactMetadata

class AccessWidenedComponentArtifactMetadata(val delegate: ComponentArtifactMetadata, private val id: AccessWidenedComponentIdentifier) : ComponentArtifactMetadata by delegate {
    override fun getComponentId() = id
}
