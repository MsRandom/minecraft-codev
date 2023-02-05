package net.msrandom.minecraftcodev.core.utils

import org.gradle.api.Named
import org.gradle.api.model.ObjectFactory

inline fun <reified T : Named> ObjectFactory.named(name: String): T = named(T::class.java, name)
