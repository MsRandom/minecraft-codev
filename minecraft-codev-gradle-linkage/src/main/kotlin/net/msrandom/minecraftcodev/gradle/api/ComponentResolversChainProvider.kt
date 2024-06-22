package net.msrandom.minecraftcodev.gradle.api

import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain

fun interface ComponentResolversChainProvider {
    fun get(): ComponentResolversChain
}
