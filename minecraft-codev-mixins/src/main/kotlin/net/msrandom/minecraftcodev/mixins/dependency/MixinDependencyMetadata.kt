package net.msrandom.minecraftcodev.mixins.dependency

import net.msrandom.minecraftcodev.core.dependency.ConfiguredDependencyMetadata
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface MixinDependencyMetadata : ConfiguredDependencyMetadata {
    val delegate: DependencyMetadata
}

sealed interface SkipMixinDependencyMetadata {
    val delegate: DependencyMetadata
}

class MixinDependencyMetadataWrapper(override val delegate: DependencyMetadata, override val relatedConfiguration: String?) : MixinDependencyMetadata, DependencyMetadata by delegate

class SkipMixinsDependencyMetadataWrapper(override val delegate: DependencyMetadata) : SkipMixinDependencyMetadata, DependencyMetadata by delegate

class DslOriginMixinDependencyMetadata(
    override val delegate: LocalOriginDependencyMetadata,
    private val source: Dependency,
    override val relatedConfiguration: String?
) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    MixinDependencyMetadata {

    override fun withTarget(target: ComponentSelector) =
        DslOriginMixinDependencyMetadata(delegate.withTarget(target), source, relatedConfiguration)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginMixinDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source, relatedConfiguration)

    override fun getSource() = source
    override fun forced() = DslOriginMixinDependencyMetadata(delegate.forced(), source, relatedConfiguration)
}

class DslOriginSkipMixinsDependencyMetadata(
    override val delegate: LocalOriginDependencyMetadata,
    private val source: Dependency
) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    SkipMixinDependencyMetadata {

    override fun withTarget(target: ComponentSelector) =
        DslOriginSkipMixinsDependencyMetadata(delegate.withTarget(target), source)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginSkipMixinsDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source)

    override fun getSource() = source
    override fun forced() = DslOriginSkipMixinsDependencyMetadata(delegate.forced(), source)
}

