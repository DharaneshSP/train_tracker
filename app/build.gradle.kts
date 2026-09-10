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
        versionCode = 1
        versionName = "0.1"
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

dependencies { }
