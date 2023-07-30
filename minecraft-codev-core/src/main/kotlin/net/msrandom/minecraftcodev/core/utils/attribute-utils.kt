package net.msrandom.minecraftcodev.core.utils

import org.gradle.api.internal.attributes.ImmutableAttributes

fun ImmutableAttributes.getAttribute(name: String) = findEntry(name).takeIf { it.isPresent }?.get()
