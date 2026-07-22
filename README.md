# Nightstorm

Automated publishing of experimental Minestom scaffold releases for the latest Minecraft snapshots.

### How to Use

Add the Nightstorm Maven repository:

```gradle
repositories {
    maven {
        url = uri("https://nightstorm.flowstom.net/")
    }
}
```

Replace your Minestom dependency with:

```gradle
dependencies {
    implementation "net.flowstom:nightstorm:<version>"
}
```

Kotlin DSL:

```kotlin
dependencies {
    implementation("net.flowstom:nightstorm:<version>")
}
```

Replace `<version>` with a version from the available [releases](https://github.com/Flowstom/Nightstorm/releases), for example `2026.07.12-26.3-snapshot-5`.
