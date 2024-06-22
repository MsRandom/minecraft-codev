package net.msrandom.minecraftcodev.core.resolve

import org.gradle.internal.component.model.ComponentArtifactMetadata

class PassthroughArtifactMetadata(val original: ComponentArtifactMetadata) : ComponentArtifactMetadata by original
