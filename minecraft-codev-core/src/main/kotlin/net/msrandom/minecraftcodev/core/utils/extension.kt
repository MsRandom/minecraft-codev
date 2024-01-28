package net.msrandom.minecraftcodev.core.utils

import org.gradle.api.plugins.ExtensionAware

inline fun <reified T> ExtensionAware.extension(): T = extensions.getByType(T::class.java)
