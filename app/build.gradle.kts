import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties()
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
    require(listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all {
        !signingProperties.getProperty(it).isNullOrEmpty()
    }) { "signing.properties requires KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD" }
}

android {
    namespace = "com.shortvideo.autoswipe"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.shortvideo.autoswipe"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.2"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (signingPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("KEYSTORE_FILE"))
                storePassword = signingProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = signingProperties.getProperty("KEY_ALIAS")
                keyPassword = signingProperties.getProperty("KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = false
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    packaging {
        dex.useLegacyPackaging = true
        // Reuse the annotation license; META-INF/androidx/NOTICE.txt lists the components.
        resources.excludes += setOf(
            "META-INF/androidx/collection/collection-ktx/LICENSE.txt",
            "META-INF/androidx/collection/collection/LICENSE.txt",
            "META-INF/androidx/datastore/datastore-core-okio/LICENSE.txt",
            "META-INF/androidx/datastore/datastore-preferences-proto/LICENSE.txt",
            "META-INF/androidx/lifecycle/lifecycle-common-java8/LICENSE.txt",
            "META-INF/androidx/lifecycle/lifecycle-common/LICENSE.txt",
        )
    }
    androidResources {
        localeFilters += "zh-rCN"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }

    onVariants { variant ->
        val suffix = when {
            variant.buildType != "release" -> "-${variant.name}"
            !signingPropertiesFile.isFile -> "-unsigned"
            else -> ""
        }
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "短视频挂机-$it$suffix.apk" })
        }
        if (variant.buildType == "release") {
            // Only MultiProcessCoordinator needs this library; the app uses single-process DataStore.
            variant.packaging.jniLibs.excludes.add("**/libdatastore_shared_counter.so")
            variant.packaging.resources.excludes.addAll(
                "META-INF/androidx.*.version",
                "META-INF/kotlinx_coroutines_*.version",
                "DebugProbesKt.bin",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore)
    implementation(libs.coroutines.android)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.launcher)
}
