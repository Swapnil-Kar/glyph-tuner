plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.glyphtuner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glyphtuner"
        minSdk = 34
        targetSdk = 35
        versionCode = 12
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Nothing's official Glyph Matrix SDK (from github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit)
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))
}
