plugins {
    id("minecraft-codev-forge")
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
    val minecraftVersion = "1.12.2"

    patches(group = "net.minecraftforge", name = "forge", version = "$minecraftVersion-14.23.5.2860", classifier = "userdev3")
    implementation(minecraft(MinecraftType.Client, minecraftVersion).patched)
}
