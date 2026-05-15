// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    kotlin("jvm") version "2.2.0" apply false
    kotlin("android") version "2.2.0" apply false
    kotlin("multiplatform") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("org.jetbrains.compose") version "1.7.1" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}