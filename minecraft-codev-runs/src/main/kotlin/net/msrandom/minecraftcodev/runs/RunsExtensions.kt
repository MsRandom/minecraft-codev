package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.utils.disambiguateName
import org.gradle.api.tasks.SourceSet

val SourceSet.nativesConfigurationName get() = disambiguateName(MinecraftCodevRunsPlugin.NATIVES_CONFIGURATION)
val SourceSet.downloadAssetsTaskName get() = disambiguateName(MinecraftCodevRunsPlugin.DOWNLOAD_ASSETS_TASK)
val SourceSet.extractNativesTaskName get() = disambiguateName(MinecraftCodevRunsPlugin.EXTRACT_NATIVES_TASK)
