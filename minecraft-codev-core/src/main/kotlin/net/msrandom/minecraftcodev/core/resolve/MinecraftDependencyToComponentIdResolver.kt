package net.msrandom.minecraftcodev.core.resolve

import net.minecraftforge.srgutils.MinecraftVersion
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyMetadata
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.ivy.IvyModuleDescriptor
import org.gradle.api.internal.artifacts.ComponentSelectionInternal
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionRangeSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.resolve.RejectedByAttributesVersion
import org.gradle.internal.resolve.RejectedByRuleVersion
import org.gradle.internal.resolve.RejectedBySelectorVersion
import org.gradle.internal.resolve.RejectedVersion
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult
import org.gradle.util.internal.BuildCommencedTimeProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

open class MinecraftDependencyToComponentIdResolver @Inject constructor(
    private val repositories: List<MinecraftRepositoryImpl.Resolver>,
    private val resolveContext: ResolveContext,
    private val cachePolicy: CachePolicy,
    private val project: Project,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val versionParser: VersionParser,
    private val attributesFactory: ImmutableAttributesFactory,
    cacheProvider: CodevCacheProvider
) : DependencyToComponentIdResolver {
    private val cacheManager = cacheProvider.manager("minecraft")

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is MinecraftDependencyMetadata) {
            resolveVersion(dependency, acceptor, rejector, result, ::MinecraftComponentIdentifier)
        }
    }

    fun resolveVersion(
        dependency: DependencyMetadata,
        acceptor: VersionSelector?,
        rejector: VersionSelector?,
        result: BuildableComponentIdResolveResult,
        identifierFactory: (String, String) -> ModuleComponentIdentifier
    ) {
        val componentSelector = dependency.selector
        if (componentSelector is ModuleComponentSelector) {
            if (componentSelector.group == MinecraftComponentResolvers.GROUP) {
                if (acceptor?.isDynamic != false) {
                    val attributesSchema = project.dependencies.attributesSchema as AttributesSchemaInternal

                    for (repository in repositories) {
                        val versionList = MinecraftMetadataGenerator.getVersionList(
                            DefaultModuleComponentIdentifier.newId(componentSelector.moduleIdentifier, componentSelector.version),
                            null,
                            repository.url,
                            cacheManager,
                            cachePolicy,
                            repository.resourceAccessor,
                            checksumService,
                            timeProvider,
                            null
                        )

                        if (versionList != null) {
                            val scheme = versionList.latest.keys.reversed()
                            val unmatched = mutableListOf<String>()
                            val rejections = mutableListOf<RejectedVersion>()

                            for ((id, version) in versionList.versions) {
                                val metadata = object : ComponentMetadata {
                                    override fun getAttributes() = attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, status)
                                    override fun getId() = DefaultModuleVersionIdentifier.newId(componentSelector.moduleIdentifier, id)
                                    override fun isChanging() = false
                                    override fun getStatus() = version.type
                                    override fun getStatusScheme() = scheme
                                }

                                val accepted = if (acceptor == null) {
                                    true
                                } else if (acceptor is VersionRangeSelector) {
                                    VersionRangeSelector(acceptor.selector, { x, y -> compareVersions(x.source, y.source) }, versionParser).accept(id)
                                } else if (acceptor.requiresMetadata()) {
                                    acceptor.accept(metadata)
                                } else {
                                    acceptor.accept(id)
                                }

                                if (!accepted) {
                                    unmatched.add(id)
                                    continue
                                }

                                val componentIdentifier = identifierFactory(componentSelector.module, id)

                                if (!resolveContext.attributes.isEmpty) {
                                    val attributes = metadata.attributes as AttributeContainerInternal
                                    val matching = attributesSchema.matcher().isMatching(attributes, resolveContext.attributes as AttributeContainerInternal)
                                    if (!matching) {
                                        rejections.add(
                                            RejectedByAttributesVersion(
                                                componentIdentifier,
                                                attributesSchema.matcher().describeMatching(attributes, resolveContext.attributes as AttributeContainerInternal)
                                            )
                                        )

                                        continue
                                    }
                                }

                                if (rejector != null && rejector.accept(id)) {
                                    rejections.add(RejectedBySelectorVersion(componentIdentifier, rejector))
                                    continue
                                }

                                val selection: ComponentSelectionInternal = object : ComponentSelectionInternal {
                                    private var rejected = false
                                    private var rejectionReason: String? = null

                                    override fun getCandidate() = componentIdentifier
                                    override fun getMetadata() = metadata
                                    override fun <T : Any?> getDescriptor(descriptorClass: Class<T>) = null

                                    override fun reject(reason: String) {
                                        rejected = true
                                        rejectionReason = reason
                                    }

                                    override fun isRejected() = rejected
                                    override fun getRejectionReason() = rejectionReason
                                }

                                if (processRules(selection, metadata, true)) {
                                    processRules(selection, metadata, false)
                                }

                                if (selection.isRejected) {
                                    rejections.add(RejectedByRuleVersion(componentIdentifier, selection.rejectionReason))
                                    continue
                                }

                                result.resolved(componentIdentifier, metadata.id)
                                break
                            }

                            break
                        }
                    }
                } else {
                    val version = acceptor.selector
                    val moduleId = componentSelector.moduleIdentifier
                    val id = identifierFactory(componentSelector.module, version)
                    val mvId = DefaultModuleVersionIdentifier.newId(moduleId, version)
                    if (rejector != null && rejector.accept(version)) {
                        result.rejected(id, mvId)
                    } else {
                        result.resolved(id, mvId)
                    }
                }
            }
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val minecraftVersion1 = try {
            MinecraftVersion.from(version1)
        } catch (_: IllegalArgumentException) {
            MinecraftVersion.NEGATIVE
        }

        val minecraftVersion2 = try {
            MinecraftVersion.from(version2)
        } catch (_: IllegalArgumentException) {
            MinecraftVersion.NEGATIVE
        }

        return minecraftVersion1.compareTo(minecraftVersion2)
    }

    private fun processRules(
        selection: ComponentSelectionInternal,
        metadata: ComponentMetadata,
        empty: Boolean
    ): Boolean {
        for (rule in resolveContext.resolutionStrategy.componentSelection.rules) {
            if (rule.action.inputTypes.isEmpty() == empty) {
                if (rule.spec.isSatisfiedBy(selection)) {
                    val inputValues = rule.action.inputTypes.map {
                        when (it) {
                            ComponentMetadata::class.java -> metadata
                            IvyModuleDescriptor::class.java -> null
                            else -> throw IllegalStateException()
                        }
                    }

                    // If any of the input values are not available for this selection, ignore the rule
                    if (null !in inputValues) {
                        try {
                            rule.action.execute(selection, inputValues)
                        } catch (exception: Exception) {
                            throw InvalidUserCodeException("There was an error while evaluating a component selection rule for ${selection.candidate.displayName}.", exception)
                        }
                    }
                }

                if (selection.isRejected) {
                    LOGGER.info("Selection of {} rejected by component selection rule: {}", selection.candidate.displayName, selection.rejectionReason)
                    return false
                }
            }
        }

        return true
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(MinecraftComponentResolvers::class.java)
    }
}
