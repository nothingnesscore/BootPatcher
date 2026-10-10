plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nothingnesscore.bootpatcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nothingnesscore.bootpatcher"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        // A release build is signed with the real key only when
        // BOOTPATCHER_KEYSTORE is provided. Without it the release variant
        // falls back to the debug key, so CI still produces an APK that is
        // actually installable rather than an unsigned one.
        create("release") {
            val ksPath = System.getenv("BOOTPATCHER_KEYSTORE")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("BOOTPATCHER_STORE_PASSWORD")
                keyAlias = System.getenv("BOOTPATCHER_KEY_ALIAS")
                keyPassword = System.getenv("BOOTPATCHER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("BOOTPATCHER_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // magiskboot must stay executable after extraction from assets.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
}