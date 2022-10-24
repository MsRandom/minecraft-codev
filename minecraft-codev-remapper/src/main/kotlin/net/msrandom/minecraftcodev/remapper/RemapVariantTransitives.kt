package net.msrandom.minecraftcodev.remapper

import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata

private val groovyType = Class.forName("net.msrandom.minecraftcodev.remapper.RemapVariantTransitives")
private val canRemap = groovyType.getMethod("canRemap", ModuleComponentResolveMetadata::class.java)
private val remap = groovyType.getMethod("canRemap", ModuleComponentResolveMetadata::class.java)

fun canRemapVariants(metadata: ModuleComponentResolveMetadata) = remap.invoke(null, metadata) as Boolean

/*
fun remapVariants(metadata: ModuleComponentResolveMetadata) = object : ModuleComponentResolveMetadata by metadata {
    override fun getVariantsForGraphTraversal()
}
*/
