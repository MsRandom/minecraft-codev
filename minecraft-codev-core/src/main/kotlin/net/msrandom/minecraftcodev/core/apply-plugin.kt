package net.msrandom.minecraftcodev.core

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

fun <T : PluginAware> Plugin<T>.applyPlugin(target: T, action: Project.() -> Unit) = applyPlugin(target, {}, action)

fun <T : PluginAware> Plugin<T>.applyPlugin(target: T, gradleSetup: (Gradle) -> Unit, action: Project.() -> Unit) = when (target) {
    is Gradle -> {
        gradleSetup(target)

        target.allprojects {
            target.plugins.apply(javaClass)
        }
    }

    is Settings -> target.gradle.apply {
        it.plugin(javaClass)
    }

    is Project -> {
        gradleSetup(target.gradle)
        target.action()
    }

    else -> Unit
}
