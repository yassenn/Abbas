# Gemma Android App - Project Log

## Record of Improvements

### 2025-04-22
*   **v1.0.5**: Fixed issue where LLM responses and "thinking" blocks were not appearing. Refactored token processing to support streaming without `<thought>` tags.
*   **v1.0.6**: Added `APP_VERSION` constant and displayed it in grey at the bottom-left of all screens.
*   **v1.0.7**: Implemented a major threading refactor to eliminate system-wide UI freezes.
    *   Moved database operations to `Dispatchers.IO`.
    *   Moved model prefill and generation to `Dispatchers.Default`.
    *   Implemented UI throttling (100ms) to prevent Main thread saturation.
    *   Added proactive coroutine yielding in the generation loop.
*   **v1.0.8**: Introduced "Smart Auto-Scroll" to prevent the UI from jumping when the user scrolls up.
*   **v1.0.9**: Added Model Persistence and UI Enhancements.
    *   The app now remembers the last chosen model using `SharedPreferences`.
    *   Added download icons and checkmarks to the model selection screen to show local availability.
    *   Automated first-launch logic to bypass the welcome screen once a model is selected.
*   **v1.1.0**: Refined the auto-scroll logic using `LazyListLayoutInfo` for better accuracy.
*   **v1.1.1**: Implemented robust manual scroll detection to completely stop auto-snapping when the user is reviewing history by checking `isScrollInProgress`.
*   **v1.1.2**: 
    *   Changed auto-scroll trigger to be strictly based on the pixel-perfect distance from the bottom (`firstVisibleItemScrollOffset == 0`).
    *   Added support for **Gemma 4 E4B (Google)** model in the selection list.
    *   Incremented global `APP_VERSION` to `v1.1.2`.
*   **v1.1.3**:
    *   Refactored `MessageList` to use a non-reversed layout (`reverseLayout = false`).
    *   Fixed "text pushing up" issue: growth at the bottom of the list no longer shifts older items when viewing history.
    *   Fixed "unexpected snap" issue: auto-scroll is only triggered if the user is already at the very bottom.
    *   Incremented global `APP_VERSION` to `v1.1.3`.
*   **v1.1.4**:
    *   Implemented comprehensive global error handling across all layers (Inference, ViewModel, UI).
    *   Added `CoroutineExceptionHandler` to catch and report background failures via Snackbars.
    *   Improved Error UI with "Retry" and "Switch Model" options for better recovery.
    *   Refined `MlcEngineManager` with descriptive, user-friendly error messages.
    *   Incremented global `APP_VERSION` to `v1.1.4`.
*   **v1.1.5**:
    *   Fixed Gemma 4 E4B download error: Corrected case-sensitivity in Hugging Face URLs and updated model library identifier.
    *   Incremented global `APP_VERSION` to `v1.1.5`.
*   **v1.1.6**:
    *   Replaced Gemma 2 2B with **Gemma 4 E2B (Google)** in the model selection list.
    *   Incremented global `APP_VERSION` to `v1.1.6`.
*   **v1.1.7**:
    *   Added **Cancel Download** button to the model initialization screen.
    *   Made `ModelDownloader` cooperative with coroutine cancellation to stop network calls immediately.
    *   Incremented global `APP_VERSION` to `v1.1.7`.
*   **v1.1.8**:
    *   Resolved Gemma 4 download failures by switching to public mirrors (`alal-community`) to bypass Hugging Face gated repo restrictions.
    *   Fixed `isModelInAssets` logic to correctly detect pre-packaged models.
    *   Incremented global `APP_VERSION` to `v1.1.8`.
*   **v1.1.9**:
    *   Enhanced **Cancel Download** to perform automatic cleanup: any partially downloaded model files are now deleted immediately upon cancellation to save storage.
    *   Silenced "Download Failed" error alerts when the user intentionally cancels the initialization.
    *   Incremented global `APP_VERSION` to `v1.1.9`.
*   **v1.2.0**:
    *   Implemented **Corrupted Model Detection**: The app now verifies model integrity during loading and specifically detects incomplete shards or native reload failures.
    *   Added **Corrupted Recovery UI**: If a model is found to be broken, the app shows a clear warning and provides a one-click button to delete corrupted weights and re-download.
    *   Incremented global `APP_VERSION` to `v1.2.0`.
*   **v1.2.1**:
    *   Fixed app crashes when loading incomplete models: Added a deep verification layer that scans `ndarray-cache.json` and ensures every required shard exists before calling native code.
    *   Hardened native reload: Wrapped native `engine.reload()` in a `Throwable` catch block to safely capture `TVMError` and prevent JVM-level crashes.
    *   Incremented global `APP_VERSION` to `v1.2.1`.
*   **v1.2.2**:
    *   Refined **Corrupted Model Recovery UI**: Separated "Re-download" and "Delete Corrupted Files" into distinct buttons for better user control.
    *   Added `deleteModelWeights` to ViewModel for standalone cleanup of specific model files.
    *   Incremented global `APP_VERSION` to `v1.2.2`.
*   **v1.2.3**:
    *   Implemented **Protected Error Handling**: Raw network/Hugging Face errors are now caught and transformed into user-friendly messages (e.g., explaining gated model access).
    *   Switched Gemma 4 models to the public `welcoma` community repository to improve out-of-the-box download success.
    *   Incremented global `APP_VERSION` to `v1.2.3`.
*   **v1.2.4**:
    *   Added **Hugging Face Token Support**: Users can now provide a personal HF Read Token in the side drawer. This allows the app to download gated models (like Gemma 4) after the user has accepted the license on the HF website.
    *   Integrated token-based authorization into the `ModelDownloader` and `MlcEngineManager` layers.
    *   Enhanced Privacy: HF tokens are masked in the UI and stored securely in SharedPreferences.
    *   Incremented global `APP_VERSION` to `v1.2.4`.
*   **v1.2.5**:
    *   Fixed application crash when clicking the "Stop" button: Replaced the problematic `abort()` call (which was missing a required native argument) with `reset()` in the `MlcEngineManager` and `JSONFFIEngine` layers.
    *   Incremented global `APP_VERSION` to `v1.2.5`.
*   **v1.2.6**:
    *   Fixed model download failures for Gemma 4 and other models: Added support for `tensor-cache.json` as an alternative parameter manifest file when `ndarray-cache.json` is missing.
    *   Incremented global `APP_VERSION` to `v1.2.6`.
*   **v1.2.7**:
    *   Improved System Responsiveness: Lowered native engine thread priority from `MAX_PRIORITY` to `NORM_PRIORITY + 1` to prevent UI freezing during model loading and generation.
    *   Optimized Inference: Implemented a conversation history cap (last 20 messages) and set a default `max_tokens` limit of 512 to prevent excessive "prefill" times.
    *   Reduced VRAM Pressure: Limited the default context window size to 1024 tokens during model initialization.
    *   Incremented global `APP_VERSION` to `v1.2.7`.
*   **v1.2.8**:
    *   Optimized Model Switching: Refactored the engine lifecycle to reuse the `MLCEngine` instance.
    *   VRAM Management: Explicitly calls native `unload()` before loading a new model to prevent VRAM leakage and stutters.
    *   Reduced Loading Latency: Eliminated redundant initialization delays, resulting in significantly faster model swaps.
    *   Incremented global `APP_VERSION` to `v1.2.8`.
*   **v1.2.9**:
    *   Fixed Model Switching Bug: Refactored `currentModel` into an observable `StateFlow` to ensure the UI correctly detects and responds to model selection changes.
    *   Enhanced Stability: Now automatically clears conversation history when switching models to prevent context mismatch between different LLM architectures.
    *   Incremented global `APP_VERSION` to `v1.2.9`.
*   **v1.3.0**:
    *   Privacy-First Implementation: Integrated **SQLCipher** for full encryption of the Room database (chat history and sessions) at rest.
    *   Secure Settings: Migrated all user preferences and secrets (like Hugging Face tokens) to **EncryptedSharedPreferences** backed by the Android Keystore.
    *   Robust Security: Implemented `SecurityUtils` to manage cryptographic keys and ensure all user data is encrypted on-disk.
    *   Incremented global `APP_VERSION` to `v1.3.0`.
*   **v1.3.1**:
    *   Added **Custom Model Support**: Users can now add custom Hugging Face model repositories by providing the Model ID, Lib Identifier, and Base URL.
    *   Dynamic Model Management: Custom models are persisted securely and merged seamlessly into the model dropdown list.
    *   Incremented global `APP_VERSION` to `v1.3.1`.

## Core Mandates & Development Workflow
*   **Fresh Deploys**: Every "run" command must perform a full build and re-install using `adb install -r --user 0`.
*   **Target Device**: Samsung Galaxy S24 (RZCY12KM30W).
*   **Version Display**: All screens must display the current version string in grey at the bottom left.

## Standard Deployment Commands
1. `./gradlew assembleDebug --no-daemon`
2. `adb -s RZCY12KM30W install -r --user 0 app/build/outputs/apk/debug/app-debug.apk`
3. `adb -s RZCY12KM30W shell am start --user 0 -n com.example.gemma/.MainActivity`
