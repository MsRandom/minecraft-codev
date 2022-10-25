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
    implementation(group = "net.fabricmc", name = "access-widener", version = "2.1.0")

    implementation(group = "org.ow2.asm", name = "asm", version = "9.3")

    compileOnly(projects.minecraftCodevGradleLinkage)

    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
