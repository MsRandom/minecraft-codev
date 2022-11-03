package net.msrandom.minecraftcodev.core.dependency

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.internal.component.model.DependencyMetadata

interface DependencyFactory {
    fun createDependency(project: Project, descriptor: DependencyMetadata): ModuleDependency
    fun canConvert(descriptor: DependencyMetadata): Boolean
}
