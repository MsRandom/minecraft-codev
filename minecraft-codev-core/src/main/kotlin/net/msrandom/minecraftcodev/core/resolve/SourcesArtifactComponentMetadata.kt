package net.msrandom.minecraftcodev.core.resolve

import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata

class SourcesArtifactComponentMetadata(val libraryArtifact: ComponentArtifactMetadata, id: ModuleComponentArtifactIdentifier) :
    DefaultModuleComponentArtifactMetadata(id)
