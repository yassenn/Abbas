# Production Readiness & Publishing Guide - Alal AI

This guide outlines the steps to take the Alal Android app from a development prototype to a production-ready application suitable for the Google Play Store and Apple App Store.

## Part 1: Technical Production Hardening (Android)

### 1. Rename Package Name
Currently, the app uses `com.example.gemma`. This must be changed to a unique, professional identifier (e.g., `ai.alal.app`).
*   **Action:** Update `applicationId` and `namespace` in `app/build.gradle.kts`.
*   **Action:** Refactor the package structure in `app/src/main/java`.

### 2. Enable R8 (Minification & Obfuscation)
Essential for reducing APK size and protecting your code.
*   **Action:** Update `build.gradle.kts`:
    ```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    ```
*   **Note:** You will need to add ProGuard rules for Room and MLC LLM to prevent them from being stripped.

### 3. App Signing
You must sign your app to publish it.
1.  Generate a keystore: `keytool -genkey -v -keystore alal-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias alal-alias`
2.  Add signing config to `build.gradle.kts` (securely using environment variables or a local.properties file).

### 4. Adaptive Icons
The current icon is a single XML. Production apps should use **Adaptive Icons** (Foreground + Background) to look correct across all Android launchers.

---

## Part 2: Publishing to Google Play Store

### 1. Account Setup
*   Create a **Google Play Developer Account** ($25 one-time fee).

### 2. Prepare Store Assets
*   **App Icon:** 512x512px PNG (with alpha).
*   **Feature Graphic:** 1024x500px PNG.
*   **Screenshots:** At least 2 for phone, 7-inch tablet, and 10-inch tablet.
*   **Descriptions:** Short (80 chars) and Full (4000 chars).

### 3. Legal & Compliance
*   **Privacy Policy:** Must be hosted on a public URL.
*   **Data Safety:** You must declare that your app **does not collect or share user data** (since inference is local). This is a strong selling point.

### 4. Submission
*   Build the App Bundle: `./gradlew bundleRelease`
*   Upload to Google Play Console.
*   Start an **Internal Testing** track first to verify everything works on real devices.

---

## Part 3: Publishing to Apple App Store (iOS)

The `mlc-llm/ios` folder contains the iOS implementation.

### 1. Account Setup
*   Enroll in the **Apple Developer Program** ($99/year).

### 2. Xcode Preparation
*   Set a unique **Bundle Identifier**.
*   Configure **Signing & Capabilities** with your Apple Developer account.
*   Build for "Any iOS Device (arm64)".

### 3. App Store Connect
*   Create a new App entry.
*   Provide icons (1024x1024) and screenshots for iPhone (6.5", 5.5") and iPad (12.9").

### 4. Upload & Review
*   In Xcode, go to `Product > Archive`.
*   Once archived, click `Distribute App` to upload to App Store Connect.
*   Submit for review.

---

## Part 4: Production Checklist
- [ ] R8/Minification enabled and tested.
- [ ] Release signing configured.
- [ ] Version code incremented.
- [ ] Privacy Policy URL live.
- [ ] Offline mode verified (no network leaks).
- [ ] Assets (Icons, Graphics) finalized.
