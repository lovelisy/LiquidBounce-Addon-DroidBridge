plugins {
    id("com.android.application")
    id("com.cefrium")
}

android {
    namespace = "liquidbounce.droidbridge.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "liquidbounce.droidbridge.host"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.19.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.cefrium:cefrium-sdk-core:0.19.0")
    implementation("com.cefrium:cefrium-native-libre-arm64-v8a:0.19.0")
    implementation("androidx.core:core-ktx:1.16.0")
}
