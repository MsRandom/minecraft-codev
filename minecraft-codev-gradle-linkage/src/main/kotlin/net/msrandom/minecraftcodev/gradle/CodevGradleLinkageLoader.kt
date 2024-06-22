package net.msrandom.minecraftcodev.gradle

import net.msrandom.minecraftcodev.gradle.api.*
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.component.model.*
import sun.misc.Unsafe
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.function.BiFunction
import java.util.function.Function

val UNSAFE = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }[null] as Unsafe

object CodevGradleLinkageLoader {
    private val internalLoader =
        (ComponentResolveMetadata::class.java.classLoader as VisitableURLClassLoader).also {
            it.addURL(javaClass.protectionDomain.codeSource.location)
        }

    private val lookup = MethodHandles.lookup()

    private val customComponentGraphResolveMetadata =
        loadClass<ComponentResolveMetadata>(
            "gradle8.CustomComponentGraphResolveMetadata",
        )

    private val delegateComponentGraphResolveState =
        loadClass<ComponentResolveMetadata>(
            "gradle8.DelegateComponentGraphResolveState",
        )

    private val componentGraphResolveState =
        loadClass<AbstractComponentGraphResolveState<*, *>>(
            "gradle8.DefaultComponentGraphResolveState",
        )

    private val wrapVariantHandle =
        lookup.findStatic(
            delegateComponentGraphResolveState,
            "wrapVariant",
            MethodType.methodType(
                List::class.java,
                ModuleComponentIdentifier::class.java,
                VariantGraphResolveMetadata::class.java,
            ),
        )

    fun wrapVariant(
        identifier: ModuleComponentIdentifier,
        variant: VariantGraphResolveMetadata,
    ): VariantMetadataHolder {
        @Suppress("UNCHECKED_CAST")
        val serialized = wrapVariantHandle(identifier, variant) as GradleSerializedData

        return VariantMetadataHolder.deserialize(GradleCommunicationProtocol.Deserializer(serialized))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadClass(name: String): Class<out T> =
        Class.forName("net.msrandom.minecraftcodev.gradle.$name", true, internalLoader) as Class<out T>

    fun wrapComponentMetadata(
        metadata: ComponentMetadataHolder,
        objectFactory: ObjectFactory,
    ): ComponentGraphResolveState {
        val serializer = GradleCommunicationProtocol.Serializer()
        metadata.serialize(serializer)

        val data = serializer.data

        return objectFactory.newInstance(
            customComponentGraphResolveMetadata,
            data,
            ImmutableModuleSources.of(),
        ).let {
            objectFactory.newInstance(componentGraphResolveState, it, it)
        }
    }

    fun wrapComponentMetadata(
        state: ComponentGraphResolveState,
        delegate: DelegateComponentMetadataHolder<*, *>,
        resolvers: ComponentResolversChainProvider,
        objectFactory: ObjectFactory,
    ): ComponentGraphResolveState {
        val variantWrapper =
            BiFunction<GradleSerializedData, ArtifactProvider, GradleSerializedData> { data, artifactProvider ->
                val variant = VariantMetadataHolder.deserialize(GradleCommunicationProtocol.Deserializer(data))

                GradleCommunicationProtocol.Serializer().also {
                    VariantMetadata.serialize(it, delegate.wrapVariant(variant, artifactProvider))
                }.data
            }

        val extraVariants =
            Function<List<GradleSerializedData>, List<GradleSerializedData>> {
                val variants = it.map { VariantMetadataHolder.deserialize(GradleCommunicationProtocol.Deserializer(it)) }

                delegate.extraVariants(variants).map {
                    GradleCommunicationProtocol.Serializer().apply {
                        VariantMetadata.serialize(this, it)
                    }.data
                }
            }

        return objectFactory.newInstance(
            delegateComponentGraphResolveState,
            state,
            delegate.id,
            variantWrapper,
            extraVariants,
            resolvers.get().artifactResolver,
            javaClass.classLoader,
            ImmutableModuleSources.of(),
        ).let {
            objectFactory.newInstance(componentGraphResolveState, it, it)
        }
    }
}
