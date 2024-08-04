plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevRemapper") {
        id = project.name
        description = "A Minecraft Codev module that allows remapping dependencies to different mapping namespaces."
        implementationClass = "net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin"
    }
}

dependencies {
    api(group = "net.fabricmc", name = "mapping-io", version = "0.3.0")
    api(group = "net.fabricmc", name = "tiny-remapper", version = "0.8.11")

    implementation(group = "org.cadixdev", name = "mercury", version = "0.1.0")
    implementation(group = "com.github.replaymod", name = "remap", "5134612")

    api(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
