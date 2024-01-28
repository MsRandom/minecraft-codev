package net.msrandom.minecraftcodev.accesswidener.dependency.intersection

import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.AbstractDependencyMetadataConverter
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyMetadataFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import javax.inject.Inject

open class AccessModifierIntersectionDependencyMetadataConverter @Inject constructor(
    excludeRuleConverter: ExcludeRuleConverter,
    private val descriptorFactory: DependencyMetadataFactory
) : AbstractDependencyMetadataConverter(excludeRuleConverter) {
    override fun createDependencyMetadata(
        componentId: ComponentIdentifier,
        clientConfiguration: String?,
        attributes: AttributeContainer?,
        dependency: ModuleDependency
    ): LocalOriginDependencyMetadata {
        val sources = (dependency as AccessModifierIntersectionDependency).dependencies
        val sourceDescriptors = sources.map { descriptorFactory.createDependencyMetadata(componentId, clientConfiguration, attributes, it) }

        return DslOriginAccessModifierIntersectionDependencyMetadata(sourceDescriptors, dependency)
    }

    override fun canConvert(dependency: ModuleDependency) = dependency is AccessModifierIntersectionDependency
}
