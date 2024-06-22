package net.msrandom.minecraftcodev.intersection.dependency

import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.AbstractDependencyMetadataConverter
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyMetadataFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.model.LocalComponentDependencyMetadata
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import javax.inject.Inject

open class IntersectionDependencyMetadataConverter
@Inject
constructor(
    excludeRuleConverter: ExcludeRuleConverter,
    private val metadataFactory: DependencyMetadataFactory,
) :
    AbstractDependencyMetadataConverter(excludeRuleConverter) {
    override fun createDependencyMetadata(
        componentId: ComponentIdentifier,
        clientConfiguration: String?,
        attributes: AttributeContainer?,
        dependency: ModuleDependency,
    ): LocalOriginDependencyMetadata {
        val intersectionDependency = dependency as IntersectionDependency
        val selector =
            DefaultModuleComponentSelector.newSelector(
                DefaultModuleIdentifier.newId(intersectionDependency.group.orEmpty(), intersectionDependency.name),
                DefaultImmutableVersionConstraint.of(intersectionDependency.version),
                dependency.attributes,
                dependency.requestedCapabilities,
            )

        val excludes = convertExcludeRules(dependency.excludeRules)

        val dependencyMetaData =
            LocalComponentDependencyMetadata(
                componentId,
                selector,
                clientConfiguration,
                attributes,
                dependency.attributes,
                dependency.targetConfiguration,
                convertArtifacts(dependency.artifacts),
                excludes,
                false,
                false,
                false,
                false,
                dependency.isEndorsingStrictVersions,
                intersectionDependency.reason,
            )

        val dependencyMetadata =
            intersectionDependency.dependencies.map {
                metadataFactory.createDependencyMetadata(
                    componentId,
                    clientConfiguration,
                    attributes,
                    it,
                )
            }

        return DslOriginIntersectionDependencyMetadata(dependencyMetaData, dependencyMetadata, dependency)
    }

    override fun canConvert(dependency: ModuleDependency) = dependency is IntersectionDependency
}
