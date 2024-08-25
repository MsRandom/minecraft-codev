package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.utils.asNamePart
import net.msrandom.minecraftcodev.core.utils.lowerCamelCaseGradleName
import org.gradle.api.tasks.SourceSet

val SourceSet.downloadAssetsTaskName get() = lowerCamelCaseGradleName("download", name.asNamePart, "assets")
val SourceSet.extractNativesTaskName get() = lowerCamelCaseGradleName("extract", name.asNamePart, "natives")
