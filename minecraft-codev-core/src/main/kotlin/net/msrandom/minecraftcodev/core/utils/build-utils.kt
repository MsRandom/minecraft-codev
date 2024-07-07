package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware

fun osVersion(): String {
    val version = SystemUtils.OS_VERSION
    val versionEnd = version.indexOf('-')
    return if (versionEnd < 0) version else version.substring(0, versionEnd)
}

fun <T : PluginAware> Plugin<T>.applyPlugin(
    target: T,
    action: Project.() -> Unit = {},
) {
    target.plugins.apply(MinecraftCodevPlugin::class.java)

    return when (target) {
        is Gradle -> {
            target.allprojects {
                target.plugins.apply(javaClass)
            }
        }

        is Settings ->
            target.gradle.apply {
                it.plugin(javaClass)
            }

        is Project -> {
            target.action()
        }

        else -> Unit
    }
}
