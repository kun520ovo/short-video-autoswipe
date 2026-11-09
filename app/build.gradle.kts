import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.KeyStore
import java.security.UnrecoverableEntryException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Properties
import javax.naming.ldap.LdapName

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties()
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
    require(listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD").all {
        !signingProperties.getProperty(it).isNullOrEmpty()
    }) { "signing.properties requires KEYSTORE_FILE and KEYSTORE_PASSWORD" }
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
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (signingPropertiesFile.isFile) {
            create("release") {
                val keyStoreFile = rootProject.file(signingProperties.getProperty("KEYSTORE_FILE"))
                val password = signingProperties.getProperty("KEYSTORE_PASSWORD")
                val keyStore = KeyStore.getInstance(keyStoreFile, password.toCharArray())
                val aliases = Collections.list(keyStore.aliases())
                    .filter { keyStore.entryInstanceOf(it, KeyStore.PrivateKeyEntry::class.java) }
                require(aliases.size == 1) { "The release keystore must contain exactly one signing private key" }
                val alias = aliases.single()
                val certificate = try {
                    (keyStore.getEntry(alias, KeyStore.PasswordProtection(password.toCharArray()))
                        as KeyStore.PrivateKeyEntry).certificate as X509Certificate
                } catch (_: UnrecoverableEntryException) {
                    throw GradleException("The signing key and keystore must use the same password")
                }
                val commonName = LdapName(certificate.subjectX500Principal.name).rdns
                    .firstOrNull { it.type.equals("CN", ignoreCase = true) }?.value?.toString()
                require(!alias.equals("androiddebugkey", ignoreCase = true) &&
                    !commonName.equals("Android Debug", ignoreCase = true)) {
                    "Use a dedicated release signing key, not the Android debug keystore"
                }
                storeFile = keyStoreFile
                storeType = keyStore.type
                storePassword = password
                keyAlias = alias
                keyPassword = password
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}
