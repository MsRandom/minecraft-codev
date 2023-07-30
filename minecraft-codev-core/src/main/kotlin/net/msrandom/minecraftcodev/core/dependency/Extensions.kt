package net.msrandom.minecraftcodev.core.dependency

import net.msrandom.minecraftcodev.core.repository.MinecraftRepository
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.minecraft.MinecraftComponentResolvers
import org.gradle.api.Action
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.internal.artifacts.DefaultArtifactRepositoryContainer
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint
import org.gradle.api.internal.artifacts.dsl.DefaultRepositoryHandler
import org.gradle.api.internal.artifacts.repositories.DefaultBaseRepositoryFactory
import org.gradle.api.model.ObjectFactory

private val baseRepository = DefaultRepositoryHandler::class.java.getDeclaredField("repositoryFactory").apply {
    isAccessible = true
}

private val objectFactory = DefaultBaseRepositoryFactory::class.java.getDeclaredField("objectFactory").apply {
    isAccessible = true
}

private fun RepositoryHandler.getObjectFactory() = objectFactory[baseRepository[this]] as ObjectFactory

fun RepositoryHandler.minecraft(): MinecraftRepository = (this as DefaultArtifactRepositoryContainer)
    .addRepository(getObjectFactory().newInstance(MinecraftRepositoryImpl::class.java), "minecraft")

fun RepositoryHandler.minecraft(configure: Action<MinecraftRepository>): MinecraftRepository = (this as DefaultArtifactRepositoryContainer)
    .addRepository(getObjectFactory().newInstance(MinecraftRepositoryImpl::class.java), "minecraft", configure)

@Suppress("unused", "UnusedReceiverParameter")
fun DependencyConstraintHandler.minecraft(name: Any, version: String?) =
    DefaultDependencyConstraint(MinecraftComponentResolvers.GROUP, name.toString(), version.orEmpty())

fun DependencyConstraintHandler.minecraft(name: Any) =
    minecraft(name, null)

fun DependencyConstraintHandler.minecraft(notation: Map<String, Any>) =
    minecraft(notation.getValue("name"), notation["version"]?.toString())
