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

    implementation(group = "net.minecraftforge", name = "accesstransformers", version = "8.0.7") {
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
    }

    implementation(group = "org.cadixdev", name = "at", version = "0.1.0-rc1")
    implementation(group = "org.cadixdev", name = "lorenz", version = "0.5.8")

    implementation(group = "de.siegmar", name = "fastcsv", version = "2.2.0")
    implementation(group = "org.apache.commons", name = "commons-lang3", version = "3.12.0")
    implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.12.4")

    compileOnly(projects.minecraftCodevGradleLinkage)

    implementation(projects.minecraftCodevAccessWidener)
    implementation(projects.minecraftCodevRemapper)
    implementation(projects.minecraftCodevRuns)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
