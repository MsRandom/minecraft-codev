package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.CapabilitiesMetadata
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object CodevGradleLinkageLoader {
    private val classLoader = (ComponentResolveMetadata::class.java.classLoader as VisitableURLClassLoader).also {
        it.addURL(javaClass.protectionDomain.codeSource.location)
    }

    private val customComponentResolveMetadata = loadClass<ComponentResolveMetadata>("CustomComponentResolveMetadata")
    private val delegatingComponentResolveMetadata = loadClass<ComponentResolveMetadata>("DelegatingComponentResolveMetadata")
    private val customConfigurationMetadata = loadClass<ConfigurationMetadata>("CustomConfigurationMetadata")
    private val delegatingConfigurationMetadata = loadClass<ConfigurationMetadata>("DelegatingConfigurationMetadata")

    private val lookup = MethodHandles.lookup()
    private val getDelegateHandle = MethodHandles.publicLookup().findVirtual(delegatingComponentResolveMetadata, "getDelegate", MethodType.methodType(ComponentResolveMetadata::class.java))
    private val getArtifactsHandle = ConfigurationMetadata::class.java.getMethod("getArtifacts").let(lookup::unreflect)
    private val keySetHandle = AttributeContainer::class.java.getMethod("keySet").let(lookup::unreflect)

    val ConfigurationMetadata.allArtifacts: List<ComponentArtifactMetadata>
        @Suppress("UNCHECKED_CAST")
        get() = getArtifactsHandle(this) as List<ComponentArtifactMetadata>

    val AttributeContainer.asList: List<Pair<Attribute<Any>, Any?>>
        @Suppress("UNCHECKED_CAST")
        get() = (keySetHandle(this) as Set<Attribute<Any>>).map { it to getAttribute(it) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadClass(name: String): Class<out T> =
        Class.forName("net.msrandom.minecraftcodev.gradle.$name", true, classLoader) as Class<out T>

    fun ComponentResolveMetadata(
        objects: ObjectFactory,
        attributes: ImmutableAttributes,
        id: ModuleComponentIdentifier,
        moduleVersionId: ModuleVersionIdentifier,
        variants: List<ConfigurationMetadata>,
        isChanging: Boolean,
        status: String,
        statusScheme: List<String>,
        defaultArtifact: ModuleComponentArtifactMetadata,
    ): ComponentResolveMetadata = objects.newInstance(customComponentResolveMetadata, attributes, id, moduleVersionId, variants, isChanging, status, statusScheme, ImmutableModuleSources.of(), defaultArtifact)

    fun ComponentResolveMetadata.copy(
        objects: ObjectFactory,
        id: ComponentIdentifier,
        artifacts: List<ModuleComponentArtifactMetadata>.() -> List<ModuleComponentArtifactMetadata>,
        configuration: ConfigurationMetadata.() -> ConfigurationMetadata,
        configurations: List<ConfigurationMetadata>.() -> List<ConfigurationMetadata> = { map(configuration) }
    ): ComponentResolveMetadata = objects.newInstance(delegatingComponentResolveMetadata, this, id, configurations, artifacts, configuration)

    fun ConfigurationMetadata(
        objects: ObjectFactory,
        name: String,
        componentId: ModuleComponentIdentifier,
        dependencies: List<DependencyMetadata>,
        artifacts: List<ComponentArtifactMetadata>,
        attributes: ImmutableAttributes,
        capabilities: CapabilitiesMetadata,
        hierarchy: Set<String>
    ): ConfigurationMetadata = objects.newInstance(customConfigurationMetadata, name, componentId, dependencies, artifacts, attributes, capabilities, hierarchy)

    fun ConfigurationMetadata.copy(
        objects: ObjectFactory,
        describable: (DisplayName) -> DisplayName,
        attributes: ImmutableAttributes,
        dependencies: List<DependencyMetadata>.() -> List<DependencyMetadata>,
        artifact: ComponentArtifactMetadata.() -> ComponentArtifactMetadata,
        artifacts: List<ComponentArtifactMetadata>.() -> List<ComponentArtifactMetadata> = { map(artifact) }
    ): ConfigurationMetadata = objects.newInstance(delegatingConfigurationMetadata, this, describable, attributes, dependencies, artifacts, artifact)

    fun getDelegate(metadata: ComponentResolveMetadata) = metadata
        .takeIf(delegatingComponentResolveMetadata::isInstance)
        ?.let { getDelegateHandle(it) as ComponentResolveMetadata }
        ?: throw UnsupportedOperationException("$metadata does not wrap anything. Can not unwrap.")
}
