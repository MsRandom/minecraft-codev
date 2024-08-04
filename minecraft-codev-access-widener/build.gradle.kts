plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevAccessWidener") {
        id = project.name
        description = "A Minecraft Codev module that allows applying access wideners to dependencies."
        implementationClass = "net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin"
    }
}

dependencies {
    api(group = "net.fabricmc", name = "access-widener", version = "2.1.0")
    api(group = "org.cadixdev", name = "at", version = "0.1.0-rc1")

    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
