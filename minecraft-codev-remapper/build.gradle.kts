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
    api(group = "net.fabricmc", name = "tiny-remapper", version = "0.8.4")
    api(group = "net.fabricmc", name = "access-widener", version = "2.1.0")

    implementation(group = "org.apache.commons", name = "commons-lang3", version = "3.12.0")

    implementation(projects.minecraftCodevCore)
    api(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
