# minecraft-codev
A Gradle plugin that allows using Minecraft as a dependency with modules that allow mod development in Forge, Fabric, Quilt with any mappings.

## Features
- Supports the Java and Kotlin Multiplatform plugins, with support for using it standalone.
- Supports applying mixins directly to dependencies to give a better debugging and development experience.
- Supports custom Gradle version selectors like `1.16+`, `[1.16, 1.18]`, `1.19.3-SNAPSHOT`

This project is currently heavily WIP, so many of the planned features are yet not fully implemented.

## Examples

### Simple Minecraft Example
```kotlin
plugins {
  java
  id("minecraft-codev-remapper") version "1.0"
}

repositories {
  minecraft()
  mavenCentral()
}

dependencies {
  // Defined in gradle.properties
  val minecraftVersion: String by project

  // `mappings` is the default mappings configuration that `remapped` uses.
  mappings(minecraft(MinecraftType.ClientMappings, minecraftVersion))

  // `minecraft(MinecraftType.Client, minecraftVersion)` returns a client Jar, which transitively includes a common Jar.
  // `.remapped` returns a remapped version of the dependency.
  implementation(minecraft(MinecraftType.Client, minecraftVersion).remapped);
}
```

### Forge Patched Minecraft Example
```kotlin
plugins {
  java
  id("minecraft-codev-forge") version "1.0"
  id("minecraft-codev-remapper") version "1.0"
}

repositories {
  maven(url = "https://maven.minecraftforge.net/")
  minecraft()
  mavenCentral()
}

dependencies {
  val minecraftVersion: String by project
  val forgeVersion: String by project

  // `patches` is the default patches configuration that `patched` uses.
  // The Forge userdev can be used in mappings as well to allow Forge's srg mappings to be applied.
  patches(mappings(group = "net.minecraftforge", name = "forge", version = "$minecraftVersion-$forgeVersion", classifier = "userdev"))

  mappings(minecraft(MinecraftType.ClientMappings, minecraftVersion))
  implementation(minecraft(MinecraftType.Client, minecraftVersion).patched.remapped);
}
```

### Multiversion Example
```kotlin
plugins {
  java
  id("minecraft-codev-remapper") version "1.0"
}

val mod16: Configuration by sourceSets.creating // For 1.16
val mod18: Configuration by sourceSets.creating // For 1.18
val mod19: Configuration by sourceSets.creating // For 1.19

repositories {
  minecraft()
  mavenCentral()
}

dependencies {
  // The remapper can automatically find the right mappings configuration based on the resolving module, like finding mod16Mappings from mod16Implementation.

  "mod16Mappings"(minecraft(MinecraftType.ClientMappings, "1.16+"))
  "mod16Implementation"(minecraft(MinecraftType.ClientMappings, "1.18+").remapped)

  "mod18Mappings"(minecraft(MinecraftType.ClientMappings, "1.18+"))
  "mod18Implementation"(minecraft(MinecraftType.ClientMappings, "1.18+").remapped)

  "mod19Mappings"(minecraft(MinecraftType.ClientMappings, "1.19+"))
  "mod19Implementation"(minecraft(MinecraftType.ClientMappings, "1.19+").remapped)
}
```
