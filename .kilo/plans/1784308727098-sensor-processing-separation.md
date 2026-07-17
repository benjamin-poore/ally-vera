# Plan: Sensor / Processing separation

## Goal
Make the two frame sources — `MyAccessibilityService` (API 34+) and `ScreenCaptureService`
(legacy MediaProjection) — behave as pure **sensors**: they acquire a frame and emit it,
and nothing else. All decisions about *what to do with the frame* (save format, letterbox
preview, patch tiling, TFLite inference, timing, battery estimate, debug bookkeeping) move
into one dedicated **processing layer**. The sensor layer must not import `ScreenshotSaver`,
`DebugManager`, `DebugScreenshotItem`, or any TensorFlow logic.

## Current state (the overlap being fixed)
- `MyAccessibilityService.takeScreenshotNow()` → `ScreenshotSaver.save(bitmap)` directly.
- `ScreenCaptureService.onImageAvailable()` → `ScreenshotSaver.save(bitmap)` directly.
- `ScreenshotSaver.save()` is misnamed: it saves full JPG, builds letterbox preview, crops
  + saves 4 patch JPGs, runs inference, times it, estimates battery, builds
  `DebugScreenshotItem`, and calls `DebugManager.addScreenshot(...)`. It is the "do everything"
  layer, which is why the sensors feel entangled with logic.
- `ScreenshotItem.kt` (non-Debug) is dead — only `DebugScreenshotItem` is referenced. Remove it.

## Design: Frame bus + processing layer

### 1. Frame contract (`frame/Frame.kt`, new package `com.allyvera.frame`)
```kotlin
data class CapturedFrame(val bitmap: Bitmap, val source: FrameSource)
enum class FrameSource { ACCESSIBILITY, MEDIA_PROJECTION }
```

### 2. Frame bus (`frame/FrameBus.kt`)
- Single process-wide `object FrameBus` holding a `MutableSharedFlow<CapturedFrame>` (replay=0,
  extraBufferCapacity small, e.g. 2, so a slow consumer drops rather than blocks the sensor).
- `fun emit(frame: CapturedFrame)` and `val frames: SharedFlow<CapturedFrame>`.
- Sensors call `FrameBus.emit(...)`; they never own the consumer.

### 3. Processing coordinator (`processing/ProcessingCoordinator.kt`)
- `object` (or injected singleton) that `collect`s `FrameBus.frames` in a long-lived
  `CoroutineScope` and runs the existing `ScreenshotSaver` pipeline per frame.
- Start it from a single app entry point (see step 5) — NOT from the sensors.

### 4. Strip `ScreenshotSaver`
- Rename to `processing/ScreenshotProcessor` (or keep file but move package). Keep its
  `save/process` pipeline intact (full JPG, letterbox, patches, inference, timing, battery,
  `DebugScreenshotItem`, `DebugManager.addScreenshot`). This is the analysis layer; it stays
  out of the sensor package. Only the coordinator calls it.
- Move `NsfwScores` and model constants with it (they already live in `screenshot` package;
  relocate whole `screenshot` → `processing` to keep names honest).

### 5. Sensor cleanup
- `MyAccessibilityService`: keep `takeScreenshotNow()` acquisition; on success wrap Bitmap in
  `CapturedFrame(..., ACCESSIBILITY)` and `FrameBus.emit(...)`. Remove `processScreenshot`,
  `ScreenshotSaver` import, `HardwareBuffer.toBitmap` (move to processor or keep as pure
  conversion helper in `frame`). No `Dispatchers`/`DebugManager` references.
- `ScreenCaptureService.onImageAvailable()`: replace `ScreenshotSaver.save(...)` with emit of
  `CapturedFrame(..., MEDIA_PROJECTION)`. Remove saver import.
- Both services must `recycle()` the Bitmap only after it has been handed downstream — since
  emit is async, hand ownership to the processor: the processor recycles at end of pipeline
  (it already does `bitmap.recycle()` today). Document that the sensor transfers ownership on
  emit and must NOT recycle.

### 6. Start the coordinator
- Create a lightweight `android.app.Application` subclass (or start in `MainActivity.onCreate`
  if avoiding manifest change is preferred — recommend Application for lifecycle correctness)
  that launches `ProcessingCoordinator.start()`. Register in `AndroidManifest.xml`.
- Coordinator scope is cancelled on process death only; survives both services.

### 7. Delete dead file
- Remove `ui/debug/ScreenshotItem.kt` (unused `ScreenshotItem`).

## Files touched
- NEW: `app/src/main/java/com/allyvera/frame/Frame.kt`
- NEW: `app/src/main/java/com/allyvera/frame/FrameBus.kt`
- NEW: `app/src/main/java/com/allyvera/processing/ProcessingCoordinator.kt`
- MOVE/RENAME: `screenshot/ScreenshotSaver.kt` → `processing/ScreenshotProcessor.kt`
- MOVE: `screenshot/NsfwScores.kt` → `processing/NsfwScores.kt`
- EDIT: `accessibility/MyAccessibilityService.kt` (emit only)
- EDIT: `screen/ScreenCaptureService.kt` (emit only)
- EDIT: `AndroidManifest.xml` (Application subclass) + NEW `AllyVeraApplication.kt`
- DELETE: `ui/debug/ScreenshotItem.kt`

## Risks / edge cases
- Bitmap ownership transfer: sensor must not recycle after emit; processor recycles. Verify no
  double-recycle if emit is dropped (buffer full) — in that case the bus should still recycle
  the dropped frame or hand it back; simplest: have sensors NOT recycle and let processor always
  recycle (including a dropped-frame path). Confirm SharedFlow won't retain references.
- HARDWARE bitmaps: `ScreenshotSaver` already copies to software; keep that in processor.
- Two concurrent emitters: SharedFlow fans both into one consumer — safe, single-threaded-ish
  via the coordinator scope.

## Validation
- Build (`gradle assembleDebug`) succeeds with no unresolved `ScreenshotSaver`/dead imports.
- Run: enable accessibility service (API34+) and/or grant MediaProjection (legacy). Confirm
  screenshots still appear in Debug screen with scores + timing (behavior unchanged).
- Confirm neither service references `DebugManager`, `NsfwScores`, or `Interpreter`.
- Confirm no Bitmap leak: watch logcat for "unable to reuse" / recycled-while-drawn warnings.
