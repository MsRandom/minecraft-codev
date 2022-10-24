plugins {
    id("minecraft-codev-remapper")
}

repositories {
    maven(url = "https://libraries.minecraft.net/")
    mavenCentral()
    minecraft()
}

val printCompileClasspath by tasks.registering {
    doLast {
        configurations.runtimeClasspath.get().resolvedConfiguration.lenientConfiguration.allModuleDependencies
        println()
        println(configurations.compileClasspath.get().joinToString("\n"))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    val version = "1.18.2"

    mappings(minecraft(MinecraftType.ServerMappings, version))
    implementation(minecraft(MinecraftType.Client, version).remapped)
}
