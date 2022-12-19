package net.msrandom.minecraftcodev.gradle

import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata

internal interface DefaultArtifactProvider {
    val defaultArtifact: ModuleComponentArtifactMetadata
}
