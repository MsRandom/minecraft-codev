plugins {
    kotlin("plugin.serialization")
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodev") {
        id = rootProject.name
        description = "A Gradle plugin that allows using Minecraft as a dependency that participates in variant selection and resolution."
        implementationClass = "net.msrandom.minecraftcodev.core.MinecraftCodevPlugin"
    }
}

dependencies {
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.3.3")
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version = "1.6.4")

    implementation(api(group = "net.minecraftforge", name = "srgutils", version = "latest.release"))

    implementation(group = "org.quiltmc", name = "quiltflower", version = "1.8.1")

    implementation(group = "org.ow2.asm", name = "asm", version = "9.3")
    implementation(group = "org.ow2.asm", name = "asm-tree", version = "9.3")

    implementation(api(group = "com.google.guava", name = "guava", version = "31.1-jre"))

    implementation(api(projects.minecraftCodevGradleLinkage) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    })
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}

publishing {
    publications {
        create<MavenPublication>("pluginMaven") {
            suppressAllPomMetadataWarnings()
        }
    }
}
