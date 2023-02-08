import java.net.URL
import net.msrandom.minecraftcodev.core.dependency.minecraft
import net.msrandom.minecraftcodev.core.MinecraftType

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
val commonChangingSnapshot by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val commonSubversion by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val commonClosedMavenRange by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val commonOpenMavenRange by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }

val clientUniqueSnapshot by sourceSets.creating { java.setSrcDirs(listOf(clientPath)) }
val clientSubversion by sourceSets.creating { java.setSrcDirs(listOf(clientPath)) }

val printCompileClasspath by tasks.registering {
    doLast {
        println()
        println(configurations[clientSubversion.compileClasspathConfigurationName].joinToString("\n"))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))

    registerFeature(commonUniqueSnapshot.name) { usingSourceSet(commonUniqueSnapshot) }
    registerFeature(commonLatestRelease.name) { usingSourceSet(commonLatestRelease) }
    registerFeature(commonLatestSnapshot.name) { usingSourceSet(commonLatestSnapshot) }
    registerFeature(commonChangingSnapshot.name) { usingSourceSet(commonChangingSnapshot) }
    registerFeature(commonSubversion.name) { usingSourceSet(commonSubversion) }
    registerFeature(commonClosedMavenRange.name) { usingSourceSet(commonClosedMavenRange) }
    registerFeature(commonOpenMavenRange.name) { usingSourceSet(commonOpenMavenRange) }
    registerFeature(clientUniqueSnapshot.name) { usingSourceSet(clientUniqueSnapshot) }
    registerFeature(clientSubversion.name) { usingSourceSet(clientSubversion) }
}

dependencies {
    val latestVersion = URL("https://maven.msrandom.net/repository/root/minecraft-latest-version.txt").openStream().use {
        String(it.readAllBytes()).trim()
    }

    commonUniqueSnapshot.implementationConfigurationName(minecraft(MinecraftType.Common, "1.12-20170918.113946-1"))
    commonLatestRelease.implementationConfigurationName(minecraft(MinecraftType.Common, "latest.release"))
    commonLatestSnapshot.implementationConfigurationName(minecraft(MinecraftType.Common, "latest.snapshot"))
    commonChangingSnapshot.implementationConfigurationName(minecraft(MinecraftType.Common, "${latestVersion}-SNAPSHOT"))
    commonSubversion.implementationConfigurationName(minecraft(MinecraftType.Common, "1.18+"))
    commonClosedMavenRange.implementationConfigurationName(minecraft(MinecraftType.Common, "[1.0,1.19.3]"))
    commonOpenMavenRange.implementationConfigurationName(minecraft(MinecraftType.Common, "[1.19,)"))

    clientUniqueSnapshot.implementationConfigurationName(minecraft(MinecraftType.Client, "1.12-20170918.113946-1"))
    clientSubversion.implementationConfigurationName(minecraft(MinecraftType.Client, "1.18+"))
}
