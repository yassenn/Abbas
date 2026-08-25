# Native Android Gemma 2B Inference Plan (Zero Telemetry)

## 1. Objective
Build an Android application that performs native, on-device text generation using the Gemma 2B model, utilizing the **MLC LLM (Machine Learning Compilation)** framework. This approach guarantees **zero telemetry** and maximum privacy, as the core inference engine processes all data entirely offline without communicating with any external servers.

## 2. Technology Stack
*   **Language:** Kotlin
*   **UI Toolkit:** Jetpack Compose (Material 3)
*   **Asynchrony:** Kotlin Coroutines and StateFlow (for managing non-blocking streams of tokens from the native C++ engine)
*   **Dependency Injection:** Hilt (Optional, recommended for MVVM)
*   **Inference Engine:** MLC LLM Android SDK (`ai.mlc.mlcllm`)
*   **Model:** Gemma 2B Instruction-tuned, compiled specifically for mobile GPUs via Apache TVM.

## 3. Implementation Steps
1.  **Environment Setup:** Create a new Android Studio project with Jetpack Compose.
2.  **SDK Integration:** Add the MLC LLM Android dependency to your `build.gradle` (usually via JitPack or building from source for absolute control).
3.  **Model Acquisition:** Download the pre-compiled MLC format of Gemma-2B-IT from the MLC HuggingFace repository, or use the MLC tools to compile it locally for Vulkan/OpenCL to ensure maximum performance on mobile.
4.  **Model Deployment (Dev):** Package the model weights within the app's `assets` or push them to the device's local storage (`/data/local/tmp/gemma-2b-mlc/`) via ADB.
5.  **Inference Layer:** Implement an `MlcEngineManager` class that initializes the native `MLCEngine`. Configure generation parameters and system prompts.
6.  **ViewModel Implementation:** Build a `ChatViewModel` that handles the state of the chat history and manages the asynchronous calls to the `MlcEngineManager`.
7.  **UI Implementation:** Develop Jetpack Compose screens for a chat interface, displaying user messages on the right, Gemma responses on the left, and managing loading states.
8.  **Testing & Optimization:** Profile GPU memory usage and ensure the Vulkan backend is properly initialized for high-speed generation.

## 4. Privacy & Telemetry Verification
*   **Network Auditing:** Run the application through an intercepting proxy (like Charles or Wireshark) during local testing to verify that zero network requests are made by the inference engine during model loading or text generation.

---