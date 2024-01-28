package net.msrandom.minecraftcodev.gradle

import net.msrandom.minecraftcodev.gradle.api.ComponentMetadataHolder
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.component.model.*
import java.lang.invoke.MethodHandles

object CodevGradleLinkageLoader {
    private val classLoader = (ComponentResolveMetadata::class.java.classLoader as VisitableURLClassLoader).also {
        it.addURL(javaClass.protectionDomain.codeSource.location)
    }

    private val lookup = MethodHandles.lookup()

    private val customComponentGraphResolveMetadata = loadClass<ComponentResolveMetadata>(
        "gradle8.CustomComponentGraphResolveMetadata"
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadClass(name: String): Class<out T> =
        Class.forName("net.msrandom.minecraftcodev.gradle.$name", true, classLoader) as Class<out T>

    private fun copyToInternal(value: Any, overrides: Map<String, Any> = emptyMap()): Any {
        val originalType = value.javaClass
        val internalType = Class.forName(value.javaClass.name, true, classLoader)

        val fields = originalType.declaredFields

        return internalType.constructors.first {
            println("${it.name}, (${it.parameterTypes.joinToString()}), ${it.parameterCount}, ${fields.size}")
            it.parameterCount == fields.size
        }.newInstance(*fields.map {
            it.isAccessible = true

            overrides[it.name] ?: it[value]
        }.toTypedArray())
    }

    fun wrapComponentMetadata(
        metadata: ComponentMetadataHolder,

        objectFactory: ObjectFactory,
    ): ComponentGraphResolveState = objectFactory.newInstance(
        customComponentGraphResolveMetadata,
        copyToInternal(metadata, mapOf("variants" to metadata.variants.map(::copyToInternal))),
        ImmutableModuleSources.of()
    ).let {
        DefaultComponentGraphResolveState(it as ComponentGraphResolveMetadata, it)
    }
}
