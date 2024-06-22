package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet.Visitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactResults
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.file.FileCollectionInternal
import org.gradle.api.internal.file.FileCollectionStructureVisitor
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.local.model.LocalConfigurationMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Supplier
import kotlin.concurrent.withLock

private val ATTRIBUTE_SCHEMA_LOCK = ReentrantLock()

// TODO Follow DefaultConfigurationResolver more to have more parity with regular resolution.
fun Project.visitConfigurationFiles(
    resolvers: ComponentResolversChainProvider,
    configuration: Configuration,
    source: Dependency? = null,
    visit: (Supplier<File>) -> Unit,
) {
    val context = configuration as ResolveContext

    val localConfigurationMetadata = context.toRootComponentMetaData().getConfiguration(context.name) as LocalConfigurationMetadata

    for (fileMetadata in localConfigurationMetadata.files) {
        if (source == null || fileMetadata.source == source) {
            for (file in fileMetadata.files) {
                visit { file }
            }
        }
    }

    for (dependency in localConfigurationMetadata.dependencies) {
        visitDependencyArtifacts(resolvers, configuration, dependency, false, source, visit)
    }
}

fun Project.visitDependencyArtifacts(
    resolvers: ComponentResolversChainProvider,
    configuration: Configuration,
    dependency: DependencyMetadata,
    transitive: Boolean,
    source: Dependency? = null,
    visit: (Supplier<File>) -> Unit,
) {
    if (source == null || dependency is DslOriginDependencyMetadata && dependency.source == source) {
        val selector = dependency.selector

        visitSelectorArtifacts(resolvers, selector, configuration, dependency, transitive, source, visit)
    }
}

fun Project.visitSelectorArtifacts(
    resolvers: ComponentResolversChainProvider,
    selector: ComponentSelector,
    configuration: Configuration,
    dependency: DependencyMetadata,
    transitive: Boolean,
    source: Dependency? = null,
    visit: (Supplier<File>) -> Unit,
) {
    val versionSelectorSchema = serviceOf<VersionSelectorScheme>()

    val constraint =
        if (selector is ModuleComponentSelector) {
            selector.versionConstraint
        } else {
            DefaultImmutableVersionConstraint.of()
        }

    val strictly =
        constraint
            .strictVersion
            .takeUnless(String::isEmpty)
            ?.let(versionSelectorSchema::parseSelector)

    val require =
        constraint
            .requiredVersion
            .takeUnless(String::isEmpty)
            ?.let(versionSelectorSchema::parseSelector)

    val preferred =
        constraint
            .preferredVersion
            .takeUnless(String::isEmpty)
            ?.let(versionSelectorSchema::parseSelector)

    val reject = UnionVersionSelector(constraint.rejectedVersions.map(versionSelectorSchema::parseSelector))

    val idResult = DefaultBuildableComponentIdResolveResult()

    resolvers.get().componentIdResolver.resolve(dependency, strictly ?: preferred ?: require, reject, idResult)

    val state =
        idResult.state ?: run {
            val componentResult = DefaultBuildableComponentResolveResult()

            resolvers.get().componentResolver.resolve(
                idResult.id,
                DefaultComponentOverrideMetadata.forDependency(
                    dependency.isChanging,
                    dependency.artifacts.getOrNull(0),
                    DefaultComponentOverrideMetadata.extractClientModule(dependency),
                ),
                componentResult,
            )

            componentResult.state
        }

    val selectionResult =
        ATTRIBUTE_SCHEMA_LOCK.withLock {
            dependency.selectVariants(
                (configuration.attributes as AttributeContainerInternal).asImmutable(),
                state,
                serviceOf(),
                configuration.outgoing.capabilities,
            )
        }

    for (selectedConfiguration in selectionResult.variants) {
        fun resolve(artifact: ComponentArtifactMetadata) {
            val artifactResult = DefaultBuildableArtifactResolveResult()
            resolvers.get().artifactResolver.resolveArtifact(
                state.prepareForArtifactResolution().resolveMetadata,
                artifact,
                artifactResult,
            )

            visit(artifactResult.result::getFile)
        }

        dependency.artifacts.firstOrNull()?.let {
            resolve((state.metadata as ModuleComponentResolveMetadata).artifact(it.name, it.extension ?: it.type, it.classifier))
        } ?: run {
            state.resolveArtifactsFor(selectedConfiguration).artifacts.forEach(::resolve)
        }

        if (!transitive) {
            continue
        }

        for (transitiveDependency in selectedConfiguration.dependencies) {
            visitDependencyArtifacts(resolvers, configuration, transitiveDependency, true, source, visit)
        }
    }
}

fun visitDependencyResultArtifacts(
    artifacts: SelectedArtifactResults,
    visit: (Supplier<File>) -> Unit,
) {
    artifacts.artifacts.visit(
        object : Visitor {
            override fun prepareForVisit(source: FileCollectionInternal.Source) = FileCollectionStructureVisitor.VisitType.Visit

            override fun visitArtifacts(artifacts: ResolvedArtifactSet.Artifacts) {
                artifacts.visit(
                    object : ArtifactVisitor {
                        override fun visitArtifact(
                            variantName: DisplayName,
                            variantAttributes: AttributeContainer,
                            capabilities: MutableList<out Capability>,
                            artifact: ResolvableArtifact,
                        ) {
                            visit(artifact::getFile)
                        }

                        override fun requireArtifactFiles() = false

                        override fun visitFailure(failure: Throwable) {
                            throw failure
                        }
                    },
                )
            }
        },
    )
}
