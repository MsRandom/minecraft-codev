import net.msrandom.minecraftcodev.core.dependency.minecraft
import net.msrandom.minecraftcodev.remapper.dependency.remapped

plugins {
    java
    id("minecraft-codev-intersections")
    id("minecraft-codev-remapper")
}

repositories {
    maven(url = "https://libraries.minecraft.net/")
    mavenCentral()
    minecraft()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val mappings1182 by configurations.creating
val mappings1194 by configurations.creating
val mappings1205 by configurations.creating

dependencies {
    mappings1182(minecraft("client-mappings", "1.18.2"))
    mappings1194(minecraft("client-mappings", "1.19.4"))
    mappings1205(minecraft("client-mappings", "1.20.5"))

    implementation(
        minecraft.intersection(
            minecraft("common", "1.18.2").remapped(mappingsConfiguration = mappings1182.name),
            minecraft("common", "1.19.4").remapped(mappingsConfiguration = mappings1194.name),
            minecraft("common", "1.20.5").remapped(mappingsConfiguration = mappings1205.name),
        ),
    )

    implementation(
        minecraft.intersection(
            minecraft("client", "1.18.2").remapped(mappingsConfiguration = mappings1182.name),
            minecraft("client", "1.19.4").remapped(mappingsConfiguration = mappings1194.name),
            minecraft("client", "1.20.5").remapped(mappingsConfiguration = mappings1205.name),
        ),
    )
}
