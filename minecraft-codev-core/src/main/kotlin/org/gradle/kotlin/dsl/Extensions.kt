package org.gradle.kotlin.dsl

import net.msrandom.minecraftcodev.core.dependency.MinecraftDependency
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyExtension
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyImpl
import net.msrandom.minecraftcodev.core.repository.MinecraftRepository
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import org.gradle.api.Action
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.artifacts.dsl.DependencyHandler
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

private fun toString(minecraftName: Any) = if (minecraftName is MinecraftType) minecraftName.module else minecraftName.toString()

const val COMMON = "common"
const val CLIENT = "client"
const val SERVER_MAPPINGS = "server-mappings"
const val CLIENT_MAPPINGS = "client-mappings"

fun RepositoryHandler.minecraft(): MinecraftRepository = (this as DefaultArtifactRepositoryContainer)
    .addRepository(getObjectFactory().newInstance(MinecraftRepositoryImpl::class.java), "minecraft")

fun RepositoryHandler.minecraft(configure: Action<MinecraftRepository>): MinecraftRepository = (this as DefaultArtifactRepositoryContainer)
    .addRepository(getObjectFactory().newInstance(MinecraftRepositoryImpl::class.java), "minecraft", configure)

fun DependencyHandler.minecraft(name: Any, version: String?): MinecraftDependency = extensions.getByType(MinecraftDependencyExtension::class.java).run {
    injectServices(MinecraftDependencyImpl(toString(name), version ?: "", null))
}

fun DependencyHandler.minecraft(name: Any) =
    minecraft(name, null)

fun DependencyHandler.minecraft(notation: Map<String, Any>) =
    minecraft(notation.getValue("name"), notation["version"]?.toString())

@Suppress("unused")
fun DependencyConstraintHandler.minecraft(name: Any, version: String?) =
    DefaultDependencyConstraint(MinecraftComponentResolvers.GROUP, MinecraftComponentResolvers.PREFIX + toString(name), version ?: "")

fun DependencyConstraintHandler.minecraft(name: Any) =
    minecraft(name, null)

fun DependencyConstraintHandler.minecraft(notation: Map<String, Any>) =
    minecraft(notation.getValue("name"), notation["version"]?.toString())

enum class MinecraftType(val module: String) {
    Common(COMMON),
    Client(CLIENT),
    ServerMappings(SERVER_MAPPINGS),
    ClientMappings(CLIENT_MAPPINGS)
}
