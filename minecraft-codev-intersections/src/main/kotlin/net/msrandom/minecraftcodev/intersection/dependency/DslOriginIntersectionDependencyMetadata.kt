package net.msrandom.minecraftcodev.intersection.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.LocalComponentDependencyMetadata
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

class DslOriginIntersectionDependencyMetadata(
    delegate: LocalComponentDependencyMetadata,
    val dependencies: List<LocalOriginDependencyMetadata>,
    private val source: Dependency,
) : LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata {
    override fun getSource() = source
}
