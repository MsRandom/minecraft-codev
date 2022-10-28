package net.msrandom.minecraftcodev.accesswidener.resolve

import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata

class AccessWidenedComponentArtifactMetadata(val delegate: ModuleComponentArtifactMetadata, private val id: AccessWidenedComponentIdentifier, val namespace: String?) : ModuleComponentArtifactMetadata by delegate {
    override fun getComponentId() = id
}
