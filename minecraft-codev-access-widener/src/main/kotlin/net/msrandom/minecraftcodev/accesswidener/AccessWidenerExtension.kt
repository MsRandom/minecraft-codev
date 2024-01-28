package net.msrandom.minecraftcodev.accesswidener

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.accesswidener.AccessWidenerReader
import net.msrandom.minecraftcodev.accesswidener.dependency.intersection.AccessModifierIntersectionDependency
import net.msrandom.minecraftcodev.accesswidener.dependency.intersection.AccessModifierIntersectionDependencyImpl
import net.msrandom.minecraftcodev.core.ResolutionData
import net.msrandom.minecraftcodev.core.dependency.resolverFactories
import net.msrandom.minecraftcodev.core.resolutionRules
import net.msrandom.minecraftcodev.core.utils.visitConfigurationFiles
import net.msrandom.minecraftcodev.core.zipResolutionRules
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.internal.artifacts.GlobalDependencyResolutionRules
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.model.ObjectFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.hash.HashCode
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.inputStream

class AccessModifierResolutionData(
    visitor: AccessModifiers,
    messageDigest: MessageDigest,

    val namespace: String?,
) : ResolutionData<AccessModifiers>(visitor, messageDigest)

open class AccessWidenerExtension(objectFactory: ObjectFactory, private val project: Project) {
    val zipAccessWidenerResolution = objectFactory.zipResolutionRules<AccessModifierResolutionData>()
    val accessWidenerResolution = objectFactory.resolutionRules(zipAccessWidenerResolution)

    private val accessWidenerCache = ConcurrentHashMap<Configuration, LoadedAccessWideners>()

    init {
        accessWidenerResolution.add { path, extension, data ->
            if (extension.lowercase() != "accesswidener") {
                return@add false
            }

            val reader = AccessWidenerReader(data.visitor)

            data.decorate(path.inputStream()).bufferedReader().use {
                reader.read(it, data.namespace)
            }

            true
        }

        accessWidenerResolution.add { path, extension, data ->
            if (extension.lowercase() != "json") {
                return@add false
            }

            val modifiers = data.decorate(path.inputStream()).use {
                Json.decodeFromStream<AccessModifiers>(it)
            }

            data.visitor.visit(modifiers)

            true
        }
    }

    fun intersection(vararg dependencies: ModuleDependency): AccessModifierIntersectionDependency = AccessModifierIntersectionDependencyImpl(listOf(*dependencies))

    fun loadAccessWideners(files: Configuration, objects: ObjectFactory, namespace: String?) = accessWidenerCache.computeIfAbsent(files) { configuration ->
        val widener = AccessModifiers(false, namespace)

        val md = MessageDigest.getInstance("SHA1")

        val resolvers by lazy {
            val detachedConfiguration = project.configurations.detachedConfiguration()
            val resolutionStrategy = detachedConfiguration.resolutionStrategy
            val resolvers = mutableListOf<ComponentResolvers>()
            for (resolverFactory in project.gradle.resolverFactories) {
                resolverFactory.create(detachedConfiguration as ResolveContext, resolvers)
            }

            resolvers.add(
                project.serviceOf<ResolveIvyFactory>().create(
                    detachedConfiguration.name,
                    resolutionStrategy as ResolutionStrategyInternal,
                    project.serviceOf<RepositoriesSupplier>().get(),
                    project.serviceOf<GlobalDependencyResolutionRules>().componentMetadataProcessorFactory,
                    ImmutableAttributes.EMPTY,
                    null,
                    project.serviceOf(),
                    project.serviceOf()
                )
            )

            ComponentResolversChain(resolvers, project.serviceOf(), project.serviceOf())
        }

        val data = AccessModifierResolutionData(widener, md, namespace)

        for (dependency in configuration.allDependencies) {
            project.visitConfigurationFiles({ resolvers }, configuration, dependency) { file ->
                for (rule in accessWidenerResolution.get()) {
                    if (rule.load(file.toPath(), file.extension, data)) {
                        break
                    }
                }
            }
        }

        LoadedAccessWideners(
            widener,
            HashCode.fromBytes(md.digest())
        )
    }

    fun loadAccessWideners(files: Iterable<File>, objects: ObjectFactory): LoadedAccessWideners {
        val widener = AccessModifiers(false, null)

        val md = MessageDigest.getInstance("SHA1")

        val data = AccessModifierResolutionData(widener, md, null)

        for (file in files) {
            for (rule in accessWidenerResolution.get()) {
                if (rule.load(file.toPath(), file.extension, data)) {
                    break
                }
            }
        }

        return LoadedAccessWideners(
            widener,
            HashCode.fromBytes(md.digest())
        )
    }

    data class LoadedAccessWideners(val tree: AccessModifiers, val hash: HashCode)
}
