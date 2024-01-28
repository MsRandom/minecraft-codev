package net.msrandom.minecraftcodev.core.dependency

import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.VersionConstraintInternal
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.AbstractDependencyMetadataConverter
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.model.LocalComponentDependencyMetadata
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import javax.inject.Inject

open class MinecraftDependencyMetadataConverter @Inject constructor(excludeRuleConverter: ExcludeRuleConverter) :
    AbstractDependencyMetadataConverter(excludeRuleConverter) {
    override fun createDependencyMetadata(
        componentId: ComponentIdentifier,
        clientConfiguration: String?,
        attributes: AttributeContainer?,
        dependency: ModuleDependency
    ): LocalOriginDependencyMetadata {
        val externalModuleDependency = dependency as MinecraftDependency
        val force = externalModuleDependency.isForce
        val changing = externalModuleDependency.isChanging
        val transitive = externalModuleDependency.isTransitive

        val selector = DefaultModuleComponentSelector.newSelector(
            DefaultModuleIdentifier.newId(dependency.group.orEmpty(), dependency.name),
            (externalModuleDependency.versionConstraint as VersionConstraintInternal).asImmutable(),
            dependency.getAttributes(),
            dependency.getRequestedCapabilities()
        )

        val excludes = convertExcludeRules(dependency.excludeRules)

        val dependencyMetaData = LocalComponentDependencyMetadata(
            componentId,
            selector,
            clientConfiguration,
            attributes,
            dependency.attributes,
            dependency.targetConfiguration,
            convertArtifacts(dependency.artifacts),
            excludes,
            force,
            changing,
            transitive,
            false,
            dependency.isEndorsingStrictVersions,
            dependency.reason
        )

        return DslOriginMinecraftDependencyMetadata(dependencyMetaData, dependency)
    }

    override fun canConvert(dependency: ModuleDependency) =
        dependency is MinecraftDependency
}
