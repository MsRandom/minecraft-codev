plugins {
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

val common12 by sourceSets.creating { java.setSrcDirs(listOf(legacyPath)) }
val common16 by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val common18 by sourceSets.creating { java.setSrcDirs(listOf(modernPath)) }
val client12 by sourceSets.creating { java.setSrcDirs(listOf(clientPath)) }
val client16 by sourceSets.creating { java.setSrcDirs(listOf(clientPath)) }
val client18 by sourceSets.creating { java.setSrcDirs(listOf(clientPath)) }

val printCompileClasspath by tasks.registering {
    doLast {
        println()
        println(configurations[client16.compileClasspathConfigurationName].joinToString("\n"))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))

    registerFeature(common12.name) { usingSourceSet(common12) }
    registerFeature(common16.name) { usingSourceSet(common16) }
    registerFeature(common18.name) { usingSourceSet(common18) }
    registerFeature(client12.name) { usingSourceSet(client12) }
    registerFeature(client16.name) { usingSourceSet(client16) }
    registerFeature(client18.name) { usingSourceSet(client18) }
}

dependencies {
    common12.implementationConfigurationName(minecraft(MinecraftType.Common, "1.12+"))
    common16.implementationConfigurationName(minecraft(MinecraftType.Common, "1.16+"))
    common18.implementationConfigurationName(minecraft(MinecraftType.Common, "1.18+"))

    client12.implementationConfigurationName(minecraft(MinecraftType.Client, "1.12+"))
    client16.implementationConfigurationName(minecraft(MinecraftType.Client, "1.16+"))
    client18.implementationConfigurationName(minecraft(MinecraftType.Client, "1.18+"))
}
