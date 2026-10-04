plugins {
    id("com.android.application")
}

android {
    namespace = "com.hermes.desktop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hermes.desktop"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
