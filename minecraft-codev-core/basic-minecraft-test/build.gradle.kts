import net.msrandom.minecraftcodev.core.MinecraftType
import net.msrandom.minecraftcodev.core.dependency.minecraft

plugins {
    java
    id("minecraft-codev")
}

repositories {
    maven(url = "https://libraries.minecraft.net/")
    mavenCentral()
    minecraft()
}

val legacyPath = layout.projectDirectory.dir("src").dir("1.12")
val modernPath = layout.projectDirectory.dir("src").dir("1.16+")
val clientPath = layout.projectDirectory.dir("src").dir("client")

val commonUniqueSnapshot by sourceSets.creating { java.setSrcDirs(listOf(legacyPath)) }
val commonLatestRelease by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val commonLatestSnapshot by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val commonSubversion by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val commonClosedMavenRange by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val commonOpenMavenRange by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }

val clientUniqueSnapshot by sourceSets.creating { java.setSrcDirs(listOf(clientPath)) }
val clientSubversion by sourceSets.creating { java.setSrcDirs(listOf(clientPath)) }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))

    registerFeature(commonUniqueSnapshot.name) { usingSourceSet(commonUniqueSnapshot) }
    registerFeature(commonLatestRelease.name) { usingSourceSet(commonLatestRelease) }
    registerFeature(commonLatestSnapshot.name) { usingSourceSet(commonLatestSnapshot) }
    registerFeature(commonSubversion.name) { usingSourceSet(commonSubversion) }
    registerFeature(commonClosedMavenRange.name) { usingSourceSet(commonClosedMavenRange) }
    registerFeature(commonOpenMavenRange.name) { usingSourceSet(commonOpenMavenRange) }
    registerFeature(clientUniqueSnapshot.name) { usingSourceSet(clientUniqueSnapshot) }
    registerFeature(clientSubversion.name) { usingSourceSet(clientSubversion) }
}

dependencies {
    commonUniqueSnapshot.implementationConfigurationName(minecraft(MinecraftType.Common, "1.12-20170918.113946-1"))
    commonLatestRelease.implementationConfigurationName(minecraft(MinecraftType.Common, "latest.release"))
    commonLatestSnapshot.implementationConfigurationName(minecraft(MinecraftType.Common, "latest.snapshot"))
    commonSubversion.implementationConfigurationName(minecraft(MinecraftType.Common, "1.18+"))
    commonClosedMavenRange.implementationConfigurationName(minecraft(MinecraftType.Common, "[1.0,1.19.3]"))
    commonOpenMavenRange.implementationConfigurationName(minecraft(MinecraftType.Common, "[1.19,)"))

    clientUniqueSnapshot.implementationConfigurationName(minecraft(MinecraftType.Client, "1.12-20170918.113946-1"))
    clientSubversion.implementationConfigurationName(minecraft(MinecraftType.Client, "1.18+"))
}

val printStuff by tasks.registering {
    doLast {
        println(configurations[commonUniqueSnapshot.compileClasspathConfigurationName].joinToString("\n"))
    }
}
