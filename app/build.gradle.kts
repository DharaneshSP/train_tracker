plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nammayatri.traintracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nammayatri.traintracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
    }

    signingConfigs {
        create("fieldtest") {
            storeFile = rootProject.file("traintracker.keystore")
            storePassword = "traintracker"
            keyAlias = "traintracker"
            keyPassword = "traintracker"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fieldtest")
        }
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // JVM tests only - replay recorded rides through TacTracker. Nothing ships in the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
