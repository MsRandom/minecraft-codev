package net.msrandom.minecraftcodev.core.resolve

import java.nio.file.Path

interface DelegatingComponentResolver {
    fun resolve(path: Path): Path
}
