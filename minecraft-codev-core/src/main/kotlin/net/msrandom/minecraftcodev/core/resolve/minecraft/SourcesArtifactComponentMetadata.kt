package net.msrandom.minecraftcodev.core.resolve.minecraft

import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName

class SourcesArtifactComponentMetadata(val libraryArtifact: ModuleComponentArtifactMetadata, id: ModuleComponentArtifactIdentifier) : ModuleComponentArtifactMetadata by libraryArtifact {
    override fun getName() = DefaultIvyArtifactName(
        libraryArtifact.name.name,
        libraryArtifact.name.type,
        libraryArtifact.name.extension,
        "sources"
    )
}
