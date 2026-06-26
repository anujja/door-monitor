// Top-level build file. Plugin versions are declared here and applied per-module.
// Kotlin 2.0+ ships the Compose compiler as a first-party Gradle plugin, so its
// version always matches the Kotlin version (no separate compiler-extension mapping).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
