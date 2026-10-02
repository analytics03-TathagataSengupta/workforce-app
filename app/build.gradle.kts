plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing values come from environment variables (set by GitHub Actions from Secrets).
// Nothing secret is stored in the repository.
val ksPath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.alcoverealty.workforce"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alcoverealty.workforce"
        minSdk = 24          // Android 7.0+
        targetSdk = 34
        // Auto-increment from GitHub run number, so every build can install over the previous one
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true        // R8 code shrinking -> smaller APK
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
