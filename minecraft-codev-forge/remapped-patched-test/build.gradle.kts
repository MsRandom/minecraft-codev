plugins {
    java
    id("minecraft-codev-forge")
    id("minecraft-codev-remapper")
}

repositories {
    maven(url = "https://maven.minecraftforge.net/") {
        metadataSources {
            gradleMetadata()
            mavenPom()
            artifact()
        }
    }

    maven(url = "https://libraries.minecraft.net/")
    mavenCentral()
    minecraft()
}

val printCompileClasspath by tasks.registering {
    doLast {
        println()
        println(configurations.compileClasspath.get().joinToString("\n"))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

dependencies {
    val minecraftVersion = "1.16.5"

    patches(mappings(group = "net.minecraftforge", name = "forge", version = "$minecraftVersion-36.2.39", classifier = "userdev"))
    mappings(minecraft(MinecraftType.ClientMappings, minecraftVersion))
    implementation(minecraft.patched(minecraftVersion).remapped)
}
