plugins {
    id("minecraft-codev-forge")
}

repositories {
    maven(url = "https://libraries.minecraft.net/")
    mavenCentral()
    minecraft()

    maven(url = "https://maven.minecraftforge.net/")
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
    patches(group = "net.minecraftforge", name = "forge", version = "1.12.2-14.23.5.2860", classifier = "userdev3")
    implementation(minecraft(CLIENT, "1.12+").patched)
}
