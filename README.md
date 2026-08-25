# Alal - Gemma Android App (MLC LLM Backend)

This Android application runs the Gemma 2B Large Language Model natively on-device using the high-performance [MLC LLM](https://llm.mlc.ai/) framework. 

**Privacy First:** This app operates 100% offline. The MLC LLM engine guarantees **zero telemetry**, meaning your prompts and the model's responses never leave your device.

## Project Structure

```text
GemmaAndroidApp/
├── app/                                 # Main application module
│   ├── build.gradle.kts                 # App-level build configurations (MLC LLM deps)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # App permissions (No INTERNET required for inference)
│       │   ├── assets/
│       │   │   └── gemma-2b-q4f16_1/    # Directory for compiled model weights and config
│       │   └── java/com/example/gemma/  # Kotlin source code
│       │       ├── MainActivity.kt      # Main Activity hosting Compose UI
│       │       ├── ui/                  # UI Layer (Jetpack Compose)
│       │       │   ├── theme/           # Material 3 theme definitions
│       │       │   ├── ChatScreen.kt    # Main chat UI layout
│       │       │   └── components/      # Reusable UI components (Message Bubbles, Input Field)
│       │       ├── viewmodel/           # Presentation Layer
│       │       │   ├── ChatViewModel.kt # State holder for the Chat screen
│       │       │   └── ChatUiState.kt   # Data classes representing UI states
│       │       └── inference/           # Domain/Data Layer
│       │           └── MlcEngineManager.kt # Wrapper for the native MLC LLM Engine
│       └── res/                         # Android resources (drawables, strings, etc.)
├── gradle/                              # Gradle wrapper and configurations
├── build.gradle.kts                     # Project-level build configurations
└── settings.gradle.kts                  # Project module settings
```

### Key Components

*   **`MlcEngineManager.kt`**: The core data layer interacting with the MLC Android SDK. It initializes the engine on a background thread, points it to the local model weights (e.g., in `assets` or local storage), and exposes a Kotlin `Flow` to stream tokens directly from the mobile GPU to the application layer.
*   **`ChatViewModel.kt`**: The bridge between the native engine and the UI. It maintains the full conversation history, appending new user prompts and updating the state incrementally as the engine generates characters.
*   **`ChatScreen.kt`**: A declarative Jetpack Compose UI that renders the conversation in a familiar chat-bubble format, updating in real-time.

## Setup Instructions

1.  Clone this repository.
2.  Download the compiled Gemma 2B model weights for MLC (q4f16_1 quantization is recommended for mobile).
3.  Place the model folder inside `app/src/main/assets/`.
4.  Build and run on a physical Android device (Snapdragon Gen 1 or newer recommended for optimal Vulkan performance).

---

## Instructions for AI Agents (Implementation Guide)

If you are an AI autonomous agent tasked with creating or maintaining this application, strictly follow these precise implementation steps to avoid common build and runtime errors.

### 1. Project Initialization & Gradle Configuration
*   **Create Project:** Initialize a standard Android Studio project with Jetpack Compose (Empty Activity). Use Kotlin as the primary language.
*   **Min SDK:** Set `minSdk = 26` (Android 8.0) or higher to ensure proper support for the required NDK and Vulkan APIs.
*   **NDK & CMake:** In `app/build.gradle.kts`, ensure you configure NDK filters to only build/include 64-bit architectures, as LLMs require significant memory.
    ```kotlin
    android {
        defaultConfig {
            ndk {
                abiFilters.add("arm64-v8a")
            }
        }
    }
    ```
*   **Avoid Asset Compression:** **[CRITICAL FIX]** The model weights (`.bin` or `.params` files) are massive. If Android tries to compress them in the APK, the app will throw `OutOfMemoryError` or fail to parse them at runtime. You MUST disable compression for model files in `app/build.gradle.kts`:
    ```kotlin
    android {
        androidResources {
            noCompress.addAll(listOf("bin", "params", "json", "wasm"))
        }
    }
    ```

### 2. MLC LLM SDK Integration
*   Since `mlc4j` is often not hosted on Maven Central and there is no standalone official prebuilt `.aar`, you must include it directly as a local module by cloning the `mlc-llm` repository.
*   Clone `mlc-llm` into your project and link the `mlc4j` directory in `settings.gradle.kts`:
    ```kotlin
    include(":mlc4j")
    project(":mlc4j").projectDir = File("mlc-llm/android/mlc4j")
    ```
*   Then add it as a project dependency in `app/build.gradle.kts`:
    ```kotlin
    dependencies {
        implementation(project(":mlc4j"))
    }
    ```

### 3. Native Model Deployment (No-Internet Policy)
*   **Zero Telemetry Constraint:** Do NOT add the `<uses-permission android:name="android.permission.INTERNET" />` permission to the `AndroidManifest.xml`. The app must function entirely offline.
*   **Model Location:** Because the app cannot download the model itself, you must assume the model is bundled in the `assets/` directory (e.g., `assets/gemma-2b-q4f16_1/`).
*   **Asset Extraction:** Android cannot easily memory-map files directly from the compressed APK `assets/` if they are not explicitly uncompressed. In `MlcEngineManager.kt`, you must write a utility function to copy the model files from `assets/` to `context.filesDir.absolutePath + "/model/"` on the first launch *before* initializing the `MLCEngine`.

### 4. Implementing the Inference Layer (`MlcEngineManager.kt`)
*   **Initialization:** Instantiate the engine using `ai.mlc.mlcllm.MLCEngine`. 
    ```kotlin
    val engine = MLCEngine(context.applicationContext)
    ```
*   **Threading:** Model loading and inference MUST be executed on a background thread (`Dispatchers.IO` or `Dispatchers.Default`). Blocking the Main/UI thread will cause an Application Not Responding (ANR) crash.
*   **Streaming Responses:** Use Kotlin `Flow` or `StateFlow` to emit chunks of text as they are generated by the engine so the UI can stream the response smoothly.

### 5. UI Implementation (`ChatScreen.kt`)
*   Use `LazyColumn` for the chat history. Implement an automatic scroll-to-bottom behavior when new tokens arrive.
*   Ensure the "Send" button is disabled while the model is actively generating a response to prevent concurrent prompt collisions.

### 6. Validation & Error Handling
*   Wrap the engine initialization and model reloading in `try-catch` blocks. The most common failure points are missing files (incorrect extraction from assets) or insufficient GPU memory (gracefully handle Vulkan `OutOfMemory` crashes by surfacing an error to the UI).
