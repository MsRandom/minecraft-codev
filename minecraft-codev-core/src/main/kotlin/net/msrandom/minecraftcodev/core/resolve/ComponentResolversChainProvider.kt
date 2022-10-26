package net.msrandom.minecraftcodev.core.resolve

import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain

fun interface ComponentResolversChainProvider {
    fun get(): ComponentResolversChain
}
