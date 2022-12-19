package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
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
    private val defaultArtifactProvider = loadClass<ConfigurationMetadata>("DefaultArtifactProvider")

    private val getDelegateHandle = MethodHandles.publicLookup().findVirtual(delegatingComponentResolveMetadata, "getDelegate", MethodType.methodType(ComponentResolveMetadata::class.java))
    private val getDefaultArtifactHandle = MethodHandles.publicLookup().findVirtual(defaultArtifactProvider, "getDefaultArtifact", MethodType.methodType(ModuleComponentArtifactMetadata::class.java))

    val ConfigurationMetadata.allArtifacts: List<ComponentArtifactMetadata>
        get() = artifacts

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadClass(name: String): Class<out T> =
        Class.forName("net.msrandom.minecraftcodev.gradle.$name", true, classLoader) as Class<out T>

    fun ComponentResolveMetadata(
        attributes: ImmutableAttributes,
        id: ModuleComponentIdentifier,
        moduleVersionId: ModuleVersionIdentifier,
        variants: List<ConfigurationMetadata>,
        isChanging: Boolean,
        status: String,
        statusScheme: List<String>,
        defaultArtifact: ModuleComponentArtifactMetadata,
        objects: ObjectFactory
    ): ComponentResolveMetadata = objects.newInstance(customComponentResolveMetadata, attributes, id, moduleVersionId, variants, isChanging, status, statusScheme, ImmutableModuleSources.of(), defaultArtifact)

    fun ComponentResolveMetadata.copy(
        id: ComponentIdentifier,
        configuration: ConfigurationMetadata.() -> ConfigurationMetadata,
        artifact: (ModuleComponentArtifactMetadata) -> ModuleComponentArtifactMetadata,
        objects: ObjectFactory
    ): ComponentResolveMetadata = objects.newInstance(delegatingComponentResolveMetadata, this, id, configuration, artifact)

    fun ConfigurationMetadata(
        name: String,
        componentId: ModuleComponentIdentifier,
        dependencies: List<DependencyMetadata>,
        artifacts: List<ComponentArtifactMetadata>,
        attributes: ImmutableAttributes,
        capabilities: CapabilitiesMetadata,
        hierarchy: Set<String>,
        objects: ObjectFactory
    ): ConfigurationMetadata = objects.newInstance(customConfigurationMetadata, name, componentId, dependencies, artifacts, attributes, capabilities, hierarchy)

    fun ConfigurationMetadata.copy(
        describable: (DisplayName) -> DisplayName,
        attributes: ImmutableAttributes,
        dependency: (DependencyMetadata) -> DependencyMetadata,
        artifact: (ComponentArtifactMetadata, List<ComponentArtifactMetadata>) -> ComponentArtifactMetadata,
        extraArtifacts: List<ComponentArtifactMetadata>,
        objects: ObjectFactory
    ): ConfigurationMetadata = objects.newInstance(delegatingConfigurationMetadata, this, describable, attributes, dependency, artifact, extraArtifacts)

    fun getDelegate(metadata: ComponentResolveMetadata) = metadata
        .takeIf(delegatingComponentResolveMetadata::isInstance)
        ?.let { getDelegateHandle(it) as ComponentResolveMetadata }
        ?: throw UnsupportedOperationException("$metadata does not wrap anything. Can not unwrap.")

    fun getDefaultArtifact(component: ComponentResolveMetadata) = component
        .takeIf(defaultArtifactProvider::isInstance)
        ?.let { getDefaultArtifactHandle(it) as ModuleComponentArtifactMetadata }
        ?: throw UnsupportedOperationException("$component does not have a default artifact")
}
