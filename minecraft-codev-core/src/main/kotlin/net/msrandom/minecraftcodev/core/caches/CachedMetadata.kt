package net.msrandom.minecraftcodev.core.caches

import org.gradle.internal.component.external.model.maven.MutableMavenModuleResolveMetadata
import java.time.Duration

sealed interface CachedMetadata {
    val age: Duration

    data class Missing(override val age: Duration) : CachedMetadata
    data class Present(override val age: Duration, val isChanging: Boolean, val metadata: MutableMavenModuleResolveMetadata?) : CachedMetadata
}
