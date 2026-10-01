plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
}

android {
    namespace = "ai.abbas.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.abbas.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 171
        versionName = "1.6.10-cpu"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // CRITICAL FIX: Ensure we only compile/include for 64-bit architectures
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // CRITICAL FIX: Disable compression for model files to prevent OutOfMemoryError
    androidResources {
        noCompress.addAll(listOf("bin", "params", "json", "wasm"))
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.16.0")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.16.0")

    // Room
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")

    // Security & Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("net.zetetic:android-database-sqlcipher:4.5.3")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // TensorFlow Lite for embeddings
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // Optional GPU delegate
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    // Optional support library for metadata etc.
    implementation("org.tensorflow:tensorflow-lite-support:0.4.0")

    // Networking for model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Stripe Payment Gateway
    implementation("com.stripe:stripe-android:20.45.0")

    // PayPal Payment Gateway
    implementation("com.paypal.android:paypal-web-payments:1.3.0")
    implementation("com.paypal.android:payment-buttons:1.3.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // llama.cpp LLM Local dependency
    implementation(project(":llama"))
    // Gson for model config parsing
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}