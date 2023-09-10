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
    implementation(group = "org.quiltmc", name = "quiltflower", version = "1.8.1")

    compileOnly(projects.minecraftCodevGradleLinkage)
    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
