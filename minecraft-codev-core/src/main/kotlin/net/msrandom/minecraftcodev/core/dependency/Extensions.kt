package net.msrandom.minecraftcodev.core.dependency

import net.msrandom.minecraftcodev.core.repository.MinecraftRepository
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.DependenciesMetadata
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.internal.artifacts.DefaultArtifactRepositoryContainer
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint
import org.gradle.api.internal.artifacts.dsl.DefaultRepositoryHandler
import org.gradle.api.internal.artifacts.repositories.DefaultBaseRepositoryFactory
import org.gradle.api.internal.artifacts.repositories.resolver.AbstractDependenciesMetadataAdapter
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.model.ObjectFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.reflect.Instantiator

private val baseRepository =
    DefaultRepositoryHandler::class.java.getDeclaredField("repositoryFactory").apply {
        isAccessible = true
    }

private val objectFactory =
    DefaultBaseRepositoryFactory::class.java.getDeclaredField("objectFactory").apply {
        isAccessible = true
    }

private val adapterImplementationType =
    AbstractDependenciesMetadataAdapter::class.java.getDeclaredMethod("adapterImplementationType").apply {
        isAccessible = true
    }

private fun RepositoryHandler.getObjectFactory() = objectFactory[baseRepository[this]] as ObjectFactory

fun RepositoryHandler.minecraft(): MinecraftRepository =
    (this as DefaultArtifactRepositoryContainer)
        .addRepository(getObjectFactory().newInstance(MinecraftRepositoryImpl::class.java), "minecraft")

fun RepositoryHandler.minecraft(configure: Action<MinecraftRepository>): MinecraftRepository =
    (this as DefaultArtifactRepositoryContainer)
        .addRepository(getObjectFactory().newInstance(MinecraftRepositoryImpl::class.java), "minecraft", configure)

@Suppress("unused", "UnusedReceiverParameter")
fun DependencyConstraintHandler.minecraft(
    name: Any,
    version: String?,
) = DefaultDependencyConstraint(MinecraftComponentResolvers.GROUP, name.toString(), version.orEmpty())

fun DependencyConstraintHandler.minecraft(name: Any) = minecraft(name, null)

fun DependencyConstraintHandler.minecraft(notation: Map<String, Any>) =
    minecraft(notation.getValue("name"), notation["version"]?.toString())

// Used to allow dependency metadata rules to add custom module dependencies
@Suppress("UNCHECKED_CAST")
fun DependenciesMetadata<*>.add(
    project: Project,
    dependency: ModuleDependency,
) {
    val id = (project as ProjectInternal).owner.componentIdentifier

    val descriptor =
        project.gradle.getDependencyDescriptorFactories().firstNotNullOf {
            if (it.canConvert(dependency)) {
                it.createDependencyMetadata(id, Dependency.DEFAULT_CONFIGURATION, ImmutableAttributes.EMPTY, dependency)
            } else {
                null
            }
        }

    val dependencies = this as MutableList<org.gradle.api.artifacts.DependencyMetadata<*>>

    val adapterImplementationType =
        adapterImplementationType(this) as Class<org.gradle.api.artifacts.DependencyMetadata<*>>

    val instantiator = project.serviceOf<Instantiator>()
    val attributesFactory = project.serviceOf<ImmutableAttributesFactory>()

    dependencies.add(instantiator.newInstance(adapterImplementationType, attributesFactory, descriptor))
}
