# AndroidAI (Alal) — Session Progress Notes

**Date:** 2026-08-05  
**Device:** Samsung SM-S931B "Galaxy S25" (serial: RZCY12KM30W)  
**Current Version:** **1.3.13** (versionCode **143**) — committed as `da58d82a` on `main`  
**Safety snapshot:** branch `vulkan-baseline-v1.3.13` + APK copy `app-debug-v1.3.13-vulkan-baseline.apk` (project root)

---

## THE BIG FINDING: freeze/ANR root cause is GPU contention, NOT CPU

Proven by on-device monitoring (1s samples) + ANR main-thread stacks:

- During generation: **GPU pinned at 100% busy** for ~45s continuously; app CPU ~0%; total CPU mostly idle.
- ANR stack (identical in v141, v142, v143): main thread healthy, blocked in
  `libhwui.so → syncAndDrawFrame → pthread_cond_wait` — waiting for the GPU to return a frame.
- MLC inference runs Vulkan compute on the same Adreno GPU as UI rendering. Adreno cannot
  preempt mid-dispatch → render commands queue behind compute → input dispatch times out
  (10s) → ANR → system kills app. That's the "freeze then crash".

## What was tried (all keep, all insufficient alone)

| Version | Fix | Result |
|---------|-----|--------|
| 1.3.9 | Instant send feedback (user msg + thinking placeholder before async work); RAG+inference off Main; inference dispatcher IO→Default.limitedParallelism(1) | Better perceived latency; freeze remained |
| 1.3.10 | Bug fixes: reachable OOM handler, synchronized regenerate + dedup old answer (UI+DB, new `MessageDao.deleteMessage`), getDisplayName cursor `use{}`+`moveToFirst`, 13→0 warnings | Correctness, not perf |
| 1.3.11 | `THREAD_PRIORITY_BACKGROUND` on MLC bg loop (MLCEngine.kt); `TVM_NUM_THREADS=cores/2` before loadLibrary (MlcEngineManager.kt) | ANR'd at 14:02 |
| 1.3.12 | `prefill_chunk_size: 128` in engine config (MLCEngine.kt reload()) | ANR'd at 14:15 |
| 1.3.13 | `prefill_chunk_size: 64` | **ANR'd at 14:39** — GPU 100% for 45s, then proc died |

## Monitor evidence (v143 test, 14:38–14:40)

- 14:38:0x idle: GPU 0%, CPU 0%
- ~14:38:20 → 14:39:46: GPU 98–100% solid (prefill+decode). One CPU spike 403% at 14:39:13.
- 14:39:27 am_anr: "Input dispatching timed out (Waited 10000ms for MotionEvent)"
- 14:39:46 am_proc_died (PID 20303)
- After death: GPU back to ~8–12%

## CONCLUSION / NEXT STEP (user decision pending)

Chunking Vulkan prefill does NOT free the GPU enough on this device. Candidate paths:

1. **OpenCL device switch** (most promising): `"device": "opencl"` in MLCEngine.kt reload()
   config. Adreno's CL scheduler coexists with render pipeline better than Vulkan compute.
   REQUIREMENT: model libs in the APK must be compiled for OpenCL target — check
   mlc4j prebuilt .so and model lib identifiers; may require recompiling model libs
   with mlc_llm compile --device opencl. THIS IS WHY THE BASELINE SNAPSHOT EXISTS.
2. prefill_chunk_size: 32 (diminishing returns; 64 already barely moved the needle — likely futile)
3. Decode pacing (sleep between token steps) — hurts tokens/s, probably still ANRs during prefill.

User explicitly wants: keep current Vulkan version intact (done: branch + APK), do OpenCL work "elsewhere" (new branch, e.g. `opencl-experiment`).

## Resume checklist

```bash
cd /home/ubuntu/Documents/projects/androidai
git log --oneline -3           # da58d82a = v1.3.13 baseline
git branch                     # vulkan-baseline-v1.3.13 exists
adb devices -l                 # USB drops constantly — re-auth on phone if empty
adb reverse tcp:8081 tcp:8081 && adb reverse tcp:8000 tcp:8000
# For OpenCL experiment:
git checkout -b opencl-experiment
# → change "device": "vulkan" → "opencl" in mlc-llm/android/mlc4j/src/main/java/ai/mlc/mlcllm/MLCEngine.kt
# → verify model lib supports opencl (check app/src/main/assets + model lib names), bump to 1.3.14 (144)
```

## File-by-file changelog (what to modify if doing OpenCL)

### `mlc-llm/android/mlc4j/src/main/java/ai/mlc/mlcllm/MLCEngine.kt`
**What it controls:** MLC native engine lifecycle — background loop threads, engine config JSON (device type, chunk size, context window), and the streaming callback infrastructure. This is the file to touch for OpenCL.
- `init {}`: Creates `MLCEngine`, spawns two `BackgroundWorker` threads. The first runs `jsonFFIEngine.runBackgroundLoop()` after setting `android.os.Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)` — this makes all TVM workers inherit background nice level.
- `reload()`: Builds the engine config JSON. Current: `"device": "vulkan"`, `"prefill_chunk_size": 64`, `"context_window_size": 1024`, `"mode": "interactive"`. **To switch to OpenCL:** change `"vulkan"` to `"opencl"`.
- `reset()`, `abort()`, `unload()`: Plumbing to JSONFFIEngine native methods.
- `EngineState.streamCallback()`: Receives chunks from native, deserializes JSON, fans out to kotlin `Channel`s via `GlobalScope.launch`.

### `app/src/main/java/ai/alal/app/inference/MlcEngineManager.kt`
**What it controls:** Our app's wrapper around MLCEngine — model download, verification, loading, inference dispatch, streaming flow.
- `init {}`: Sets `TVM_NUM_THREADS = availableProcessors() / 2` (minimum 2) via `android.system.Os.setenv()` BEFORE `System.loadLibrary("tvm4j_runtime_packed")`. This caps TVM's internal CPU thread pool.
- `inferenceDispatcher`: `Dispatchers.Default.limitedParallelism(1)` — single CPU-priority thread for queuing inference work (not the actual computation, just JNI bridge + flow collection).
- `generateResponse()`: Builds a `ChatCompletionRequest` with `max_tokens=512` and history cap of 20 messages, calls `currentEngine.chat.completions.create()`, collects stream chunks in a `flow {}` with `yield()` on each token, `.flowOn(inferenceDispatcher)`.
- `initializeEngine()`: Downloads model (if needed), verifies integrity via `verifyModelIntegrity()`, calls `engine.reload()`. OOM detection in the `Throwable` catch around reload (was unreachable in previous versions — fixed in v1.3.10).
- `verifyModelIntegrity(outDir: File)`: Scans `ndarray-cache.json` or `tensor-cache.json`, checks every shard exists. Unused `model` param removed in v1.3.10.

### `app/src/main/java/ai/alal/app/viewmodel/ChatViewModel.kt`
**What it controls:** App ViewModel — message flow, session management, RAG context assembly, web search toggle, donation stubs.
- `sendMessage()` (v1.3.9+): Shows user message in `_dbMessages` IMMEDIATELY (before any async work), sets a "thinking" `_generatingMessage` placeholder, saves to DB in background (no `.join()`), then launches RAG + inference on `Dispatchers.Default`.
- `generateInternal()`: Now accepts optional `botMessageId: String? = null` — if pre-created by sendMessage, reuses it; otherwise creates new (from regenerate). Paranoid `if (_generatingMessage.value?.id != effectiveId)` before setting.
- `regenerate()` (v1.3.10): `mlcHistory` mutations wrapped in `synchronized(mlcHistory)`. Old answer removed from UI (`_dbMessages.dropLast(1)`) and DB (`messageDao.deleteMessage()` — new DAO method).
- `sessionId`: Changed from `var` with null check → `val` with `?: UUID()` via `.also {}` (v1.3.10 — eliminated unnecessary `!!`).

### `app/src/main/java/ai/alal/app/data/MessageDao.kt` (+ `deleteMessage`)
- Added in v1.3.10: `@Query("DELETE FROM messages WHERE id = :messageId") suspend fun deleteMessage(messageId: String)` — used by `regenerate()` to deduplicate the old answer.

### `app/src/main/java/ai/alal/app/repository/KnowledgeRepository.kt` (getDisplayName fix)
- v1.3.10: `getDisplayName()` rewritten from manual `cursor?.getString()` (missing `moveToFirst()` → crash) to `context.contentResolver.query(...)?.use { cursor -> ... }` with proper `moveToFirst()` and column index check. Unused `ContentResolver` import removed.

### `app/build.gradle.kts`
- versionCode 143, versionName "1.3.13" (bump to 144 / "1.3.14" for OpenCL experiment)

## Key files

```
mlc-llm/android/mlc4j/src/main/java/ai/mlc/mlcllm/MLCEngine.kt   # engine config: device, chunk size; bg thread priority
app/src/main/java/ai/alal/app/inference/MlcEngineManager.kt      # TVM_NUM_THREADS, dispatcher, OOM handling
app/src/main/java/ai/alal/app/viewmodel/ChatViewModel.kt         # instant feedback sendMessage, regenerate dedup
```

## Gotchas

- USB/ADB drops constantly (device sleep, cable) — `adb kill-server` + re-auth on phone.
- User forbids blind `adb input tap` UI driving; user sends prompts manually while we monitor.
- GPU busy: `adb shell cat /sys/class/kgsl/kgsl-3d0/gpubusy` (two ints: busy total; busy/total*100 = %).
- ANR reports: `adb shell dumpsys dropbox --print data_app_anr` (grep for "v14X").
- Proc deaths: `adb logcat -d -b events | grep -E 'am_anr|am_proc_died'`.
- Every change bumps versionCode/versionName (next: 1.3.14 / 144).
