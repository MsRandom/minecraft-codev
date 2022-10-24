plugins {
    kotlin("plugin.serialization")
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevForge") {
        id = project.name
        description = "A Minecraft Codev module that allows providing Forge patched versions of Minecraft."
        implementationClass = "net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin"
    }
}

dependencies {
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.3.3")

    implementation(group = "net.minecraftforge", name = "accesstransformers", version = "8.0.7")

    implementation(group = "de.siegmar", name = "fastcsv", version = "2.2.0")

    implementation(projects.minecraftCodevRemapper)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
