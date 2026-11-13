plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Signing.
 *  - "dev" key (committed in signing/dev.keystore): lets every test build install over the
 *    previous one. It is NOT a production key and must never be used for Play.
 *  - Production: set FOLDMOTION_KEYSTORE / _PASSWORD / _ALIAS / _KEY_PASSWORD in CI from the
 *    secret manager; the release build then signs with the owner's upload key instead.
 */
// CI passes unset secrets as empty strings, so blank means "not configured".
val releaseKeystore: String? = System.getenv("FOLDMOTION_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.foldmotion.wallpapers"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foldmotion.wallpapers"
        // Android 11: first version with TYPE_HINGE_ANGLE, WEBP_LOSSY/LOSSLESS and
        // WallpaperService.Engine.getDisplayContext. Fold7 ships with Android 16.
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/dev.keystore")
            storePassword = "foldmotion-dev"
            keyAlias = "foldmotion-dev"
            keyPassword = "foldmotion-dev"
        }
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FOLDMOTION_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FOLDMOTION_KEY_ALIAS")
                keyPassword = System.getenv("FOLDMOTION_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("signing/dev.keystore")
                storePassword = "foldmotion-dev"
                keyAlias = "foldmotion-dev"
                keyPassword = "foldmotion-dev"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Only the two languages the app is written in; drops dozens of library translations.
    androidResources {
        localeFilters += listOf("en", "ar")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                // Robolectric touches JDK internals (FileDescriptor) when emulating newer Android versions.
                it.jvmArgs(
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                )
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                // Capture screenshots with the software renderer: the hardware path starts native
                // render threads that cannot resolve JDK classes inside the test JVM.
                it.systemProperty("robolectric.pixelCopyRenderMode", "software")
                // Robolectric downloads android-all at runtime; use the same throttle-free mirror.
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                it.systemProperty("robolectric.dependency.repo.id", "MavenCentralMirror")
                it.systemProperty("foldmotion.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
            }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
