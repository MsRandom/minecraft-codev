package net.msrandom.minecraftcodev.remapper.dependency

import net.msrandom.minecraftcodev.core.dependency.DependencyFactory
import net.msrandom.minecraftcodev.core.dependency.convertDescriptor
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.internal.component.model.DependencyMetadata

open class RemappedDependencyFactory : DependencyFactory {
    override fun createDependency(project: Project, descriptor: DependencyMetadata): RemappedDependency<*> {
        descriptor as RemappedDependencyMetadata

        return RemappedDependency(
            project.convertDescriptor(descriptor.delegate) as ModuleDependency,
            descriptor.sourceNamespace?.name,
            descriptor.targetNamespace.name,
            descriptor.relatedConfiguration
        )
    }

    override fun canConvert(descriptor: DependencyMetadata) = descriptor is RemappedDependencyMetadata
}
