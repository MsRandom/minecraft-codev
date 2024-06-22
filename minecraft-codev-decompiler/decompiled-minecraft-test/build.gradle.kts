import java.net.URL
import net.msrandom.minecraftcodev.core.dependency.minecraft
import net.msrandom.minecraftcodev.core.dependency.withSources
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

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation(minecraft(MinecraftType.Client, "1.20.1").withSources)
}
