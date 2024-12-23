plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevDecompiler") {
        id = project.name
        description = "A Minecraft Codev module that provides ways of decompiling & generating sources."
        implementationClass = "net.msrandom.minecraftcodev.decompiler.MinecraftCodevDecompilerPlugin"
    }
}

dependencies {
    implementation(group = "org.vineflower", name = "vineflower", version = "1.10.1")

    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
