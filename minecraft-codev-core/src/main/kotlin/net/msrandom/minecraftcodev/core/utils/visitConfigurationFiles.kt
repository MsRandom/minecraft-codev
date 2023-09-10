package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.allArtifacts
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.local.model.LocalConfigurationMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import java.io.File

// TODO Follow DefaultConfigurationResolver more to have more parity with regular resolution.
fun Project.visitConfigurationFiles(resolvers: ComponentResolversChainProvider, configuration: Configuration, source: Dependency? = null, visit: (File) -> Unit) {
    val versionSelectorSchema = serviceOf<VersionSelectorScheme>()
    val context = configuration as ResolveContext

    val localConfigurationMetadata = context.toRootComponentMetaData().getConfiguration(context.name) as LocalConfigurationMetadata

    for (fileMetadata in localConfigurationMetadata.files) {
        if (source == null || fileMetadata.source == source) {
            for (file in fileMetadata.files) {
                visit(file)
            }
        }
    }

    for (dependency in localConfigurationMetadata.dependencies) {
        if (source == null || dependency is DslOriginDependencyMetadata && dependency.source == source) {
            val selector = (dependency as LocalOriginDependencyMetadata).selector
            val constraint = if (selector is ModuleComponentSelector) selector.versionConstraint else DefaultImmutableVersionConstraint.of()
            val strictly = if (constraint.strictVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.strictVersion)
            val require = if (constraint.requiredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.requiredVersion)
            val preferred = if (constraint.preferredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.preferredVersion)
            val reject = UnionVersionSelector(constraint.rejectedVersions.map(versionSelectorSchema::parseSelector))
            val idResult = DefaultBuildableComponentIdResolveResult()

            resolvers.get().componentIdResolver.resolve(dependency, strictly ?: preferred ?: require, reject, idResult)

            val metadata = idResult.metadata ?: run {
                val componentResult = DefaultBuildableComponentResolveResult()
                resolvers.get().componentResolver.resolve(idResult.id, DefaultComponentOverrideMetadata.forDependency(dependency.isChanging, dependency.artifacts.getOrNull(0), DefaultComponentOverrideMetadata.extractClientModule(dependency)), componentResult)

                componentResult.metadata
            }

            for (selectedConfiguration in dependency.selectConfigurations(
                (context.attributes as AttributeContainerInternal).asImmutable(),
                metadata,
                project.dependencies.attributesSchema as AttributesSchemaInternal,
                configuration.outgoing.capabilities
            )) {
                fun resolve(artifact: ComponentArtifactMetadata) {
                    val artifactResult = DefaultBuildableArtifactResolveResult()
                    resolvers.get().artifactResolver.resolveArtifact(artifact, metadata.sources, artifactResult)

                    visit(artifactResult.result)
                }

                val artifacts = dependency.artifacts
                if (artifacts.isEmpty()) {
                    selectedConfiguration.allArtifacts.forEach(::resolve)
                } else {
                    artifacts.asSequence().map(selectedConfiguration::artifact).forEach(::resolve)
                }
            }
        }
    }
}
