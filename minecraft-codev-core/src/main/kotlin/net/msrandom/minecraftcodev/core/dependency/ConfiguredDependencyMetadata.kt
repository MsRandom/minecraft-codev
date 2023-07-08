package net.msrandom.minecraftcodev.core.dependency

import org.gradle.internal.component.model.DependencyMetadata

interface ConfiguredDependencyMetadata : DependencyMetadata {
    /**
     * A configuration name which references a configuration with related files, such as mappings or access wideners.
     */
    val relatedConfiguration: String?
}
