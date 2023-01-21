import net.msrandom.minecraftcodev.accesswidener.dependency.accessWidened
import net.msrandom.minecraftcodev.core.MinecraftType
import net.msrandom.minecraftcodev.core.dependency.minecraft

plugins {
    java
    id("minecraft-codev-access-widener")
}

repositories {
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
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    accessWideners(files("test.accessWidener"))
    implementation(minecraft(MinecraftType.Common, "1.18.2").accessWidened)
}
