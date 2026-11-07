plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
}

android {
    namespace = "ai.abbas.app"
    compileSdk = 34
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "ai.abbas.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 196
        versionName = "1.6.35-cpu"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // arm64-v8a is the shipping ABI. Pass -PincludeX86=true to ALSO build an
        // x86_64 slice, needed to run chat/inference on the desktop emulator
        // (its ARM translation aborts inside llama.cpp's compute graph).
        ndk {
            abiFilters.add("arm64-v8a")
            if (project.findProperty("includeX86")?.toString() == "true") {
                abiFilters.add("x86_64")
            }
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

    // Security & Encryption. sqlcipher-android is the maintained SQLCipher
    // binding; the retired android-database-sqlcipher bundles 4 KB-aligned
    // .so files, which Play rejects on 16 KB-page devices.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // LiteRT (formerly TensorFlow Lite) for embeddings. 1.4.2 ships 16 KB-page
    // aligned native libs; org.tensorflow:tensorflow-lite:2.16.1 does not, and
    // Play rejects the whole APK for it. The GPU delegate is unused, so the
    // GPU artifact is gone too.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    // TensorBuffer/FileUtil helpers only — pure JVM code. Exclude the old API
    // artifact so org.tensorflow.lite.Interpreter isn't defined twice.
    implementation("org.tensorflow:tensorflow-lite-support:0.4.0") {
        // Its runtime belongs to LiteRT now; pulling the old interpreter in
        // duplicates org.tensorflow.lite.* across the two artifacts.
        exclude(group = "org.tensorflow", module = "tensorflow-lite")
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }

    // Networking for model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // PDF text extraction for document ingestion
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
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
    // Compose-rule tests can't run on this device: Espresso's onIdle() reflects
    // into the removed InputManager.getInstance() on Android 15+. UiAutomator
    // drives the real app through accessibility nodes instead.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}