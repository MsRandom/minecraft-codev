package net.msrandom.minecraftcodev.core.utils

import java.util.*

inline fun <reified T> serviceLoader(): Iterable<T> = ServiceLoader.load(T::class.java, T::class.java.classLoader).toList()
