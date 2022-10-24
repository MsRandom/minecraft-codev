# minecraft-codev-gradle-linkage
This project provides the ability for minecraft-codev to create instances of Gradle classes it would not normally be able to acquire instances of,
due to the classloading aspect of Gradle.

Every public API within this project has to adhere to strict, clear rules.
Namely only including Java and Gradle classes within the signatures of every method and field,
otherwise there is a clear risk of breaking classloading rules.

Internally, this uses classloading to allow creating instances that are in the same class loader as the Gradle instances,
resolving any issues normally found when trying to extend or invoke behavior from internal Gradle APIs the use external dependencies.
By returning types that are not loaded by the plugin class loader(Java and Gradle classes), there is no risk of mixing class loaders.
