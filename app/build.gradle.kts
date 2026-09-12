plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace   = "com.bearnest.vpn"
    compileSdk  = 35

    defaultConfig {
        applicationId             = "com.bearnest.vpn"
        minSdk                    = 26
        targetSdk                 = 35
        versionCode               = 1
        versionName               = "3.12.26"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    // ── Подпись релиза ────────────────────────────────────────────────────────
    signingConfigs {
        create("release") {
            storeFile     = file("keystore.jks")   // путь к вашему keystore
            storePassword = "YOUR_STORE_PASSWORD"
            keyAlias      = "YOUR_KEY_ALIAS"
            keyPassword   = "YOUR_KEY_PASSWORD"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ── Переименование APK: BearNest-3.12.26-release.apk ────────────────────
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "BearNest-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs   { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}