enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "minecraft-codev"

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://maven.msrandom.net/repository/root/")
        maven(url = "https://maven.fabricmc.net/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
        maven(url = "https://repo.spongepowered.org/repository/maven-public/")
        maven(url = "https://maven.minecraftforge.net/")
        // maven(url = "https://maven.architectury.dev/")
        maven(url = "https://maven.neoforged.net/")
    }
}

include("minecraft-codev-gradle-linkage")
include("minecraft-codev-core", "minecraft-codev-core:side-annotations")
include("minecraft-codev-decompiler")
include("minecraft-codev-remapper")
include("minecraft-codev-forge", "minecraft-codev-forge:forge-runtime")
include("minecraft-codev-fabric")
include("minecraft-codev-includes")
include("minecraft-codev-runs")
include("minecraft-codev-access-widener")
include("minecraft-codev-mixins")
