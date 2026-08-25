# Project Progress & History

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
*   **v1.2.4**: (Current)
    *   Hugging Face Token support in side drawer for gated models.
    *   Protected error handling for gated models.
    *   Switched Gemma 4 to public community repo.
    *   Deep model verification and native crash protection.

## Development Workflow
*   **Fresh Deploys**: All runs perform a full clean build and re-install (`adb install -r --user 0`).
*   **Target Device**: Samsung Galaxy S24 (RZCY12KM30W).
*   **Deployment Commands**:
    1. `./gradlew assembleDebug --no-daemon`
    2. `adb -s RZCY12KM30W install -r --user 0 app/build/outputs/apk/debug/app-debug.apk`
    3. `adb -s RZCY12KM30W shell am start --user 0 -n com.example.gemma/.MainActivity`
