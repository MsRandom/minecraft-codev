package net.msrandom.minecraftcodev.accesswidener.dependency

import net.msrandom.minecraftcodev.core.dependency.DependencyFactory
import net.msrandom.minecraftcodev.core.dependency.convertDescriptor
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.internal.component.model.DependencyMetadata

open class AccessWidenedDependencyFactory : DependencyFactory {
    override fun createDependency(project: Project, descriptor: DependencyMetadata): AccessWidenedDependency<*> {
        descriptor as AccessWidenedDependencyMetadata

        return AccessWidenedDependency(project.convertDescriptor(descriptor.delegate) as ModuleDependency, descriptor.relatedConfiguration)
    }

    override fun canConvert(descriptor: DependencyMetadata) = descriptor is AccessWidenedDependencyMetadata
}
