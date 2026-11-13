// AGP 9 supplies the Android Kotlin integration. Keep its Kotlin toolchain
// aligned with the version catalog without applying a second Android plugin.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
