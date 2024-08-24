package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.utils.asNamePart
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import net.msrandom.minecraftcodev.core.utils.lowerCamelCaseName
import org.gradle.api.tasks.SourceSet

val SourceSet.downloadAssetsTaskName get() = lowerCamelCaseName("download", name.asNamePart, "assets")
val SourceSet.extractNativesTaskName get() = lowerCamelCaseName("extract", name.asNamePart, "natives")
