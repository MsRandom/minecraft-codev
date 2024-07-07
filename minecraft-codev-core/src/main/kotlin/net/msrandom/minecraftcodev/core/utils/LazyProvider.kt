package net.msrandom.minecraftcodev.core.utils

import org.gradle.api.Project
import org.gradle.api.provider.Provider

fun <T> Project.lazyProvider(provide: () -> T): Provider<T> {
    val lazy = lazy(LazyThreadSafetyMode.NONE, provide)

    return provider {
        lazy.value
    }
}
