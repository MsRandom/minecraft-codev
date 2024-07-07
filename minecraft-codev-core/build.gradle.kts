plugins {
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
    implementation(group = "dev.gradleplugins", name = "gradle-api", version = "8.8")
    implementation(projects.minecraftCodevCore.sideAnnotations)

    implementation(api(group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.3.3"))
    implementation(api(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version = "1.6.4"))

    implementation(api(group = "net.minecraftforge", name = "srgutils", version = "latest.release"))

    implementation(api(group = "org.ow2.asm", name = "asm-tree", version = "9.3"))

    implementation(api(group = "com.google.guava", name = "guava", version = "31.1-jre"))
    implementation(group = "org.apache.commons", name = "commons-lang3", version = "3.12.0")
    implementation(group = "commons-io", name = "commons-io", version = "2.11.0")
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
