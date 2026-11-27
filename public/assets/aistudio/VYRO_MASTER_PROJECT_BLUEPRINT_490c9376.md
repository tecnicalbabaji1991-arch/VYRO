# VYRO — Master Project Blueprint
## Android Video Editor: Foundation, Architecture, Pipelines, Modular Feature Integration & Roadmap

**Version:** 1.0  
**Planning baseline:** 23 November 2026  
**Status:** Planning baseline; implementation and device validation are not yet completed.

---

# 1. Project Vision

VYRO is a native Android video editor designed around a stable, extensible editing engine. The initial scope is a capable **core video editor**. Advanced capabilities can be integrated later without restructuring foundational systems.

The supplied visual reference informs the editor experience:
- Top bar with close, help and export/share actions.
- Preview canvas.
- Selected text layer with bounding box and manipulation handles.
- Playback controls and time/duration display.
- Timeline ruler and playhead.
- Separate text, video and audio lanes.
- Undo/redo and keyframe controls.
- Contextual text toolbar: add, edit, font, color, animation, split and delete.

The screenshot is a UI reference, not a complete feature inventory or technical specification.

## 1.1 Locked decisions

| Area | Decision |
|---|---|
| Platform | Android |
| Minimum SDK | API 30 (Android 11) |
| UI | Hybrid: Kotlin/Jetpack Compose + custom Android Views |
| Native engine | Modular C++ |
| C++ baseline | C++17 |
| Kotlin/native boundary | JNI |
| Development | Sequential: validate foundations before feature expansion |
| Feature integration | Separate modules connected through explicit contracts |
| Initial scope | Core video editor first |
| Quality objective | Production-oriented architecture and validation |
| Rendering | OpenGL ES 3.x baseline; Vulkan optional after capability checks |
| Media | Android MediaCodec, MediaExtractor and MediaMuxer, subject to device capability checks |

“Plugin-style” initially means **in-app modular feature integration**. It does not mean downloading and executing arbitrary native or DEX plugins. Runtime downloadable executable plugins are out of initial scope because of security, signing, compatibility and distribution risks.

# 2. Product Goals and Engineering Principles

1. Build for Android phones and tablets.
2. Preserve original media; ordinary edits are non-destructive.
3. Keep project state authoritative and consistent across timeline, preview and export.
4. Separate UI, application logic, native editing logic and Android platform integration.
5. Integrate features through stable contracts, not private implementation dependencies.
6. Validate every foundation phase with automated and on-device tests.
7. Query device capabilities instead of assuming identical codecs, GPU features, memory or performance.
8. Keep heavy media work off the UI thread.
9. Use bounded queues, cancellation and stale-result rejection.
10. Do not mark a phase complete merely because it compiles.

## 2.1 Single source of truth

The project model and timeline evaluator are authoritative. Preview, timeline UI and export derive results from the same project state and time-mapping rules. UI state must not become a second conflicting source of clip timing or effect values.

## 2.2 Non-destructive editing

Editing operations update project instructions and media references. They do not overwrite source video, audio or image files. Export creates a new output.

## 2.3 Contract-first modules

Every module documents its responsibility, owned data, public interfaces, dependencies, threading rules, lifecycle, error model and tests. Cross-module access to private implementation is prohibited.

## 2.4 Deterministic editing

Add, move, trim, split, delete and undo operations must be deterministic for the same input state. Use command-based editing and testable state transitions.

# 3. High-Level Architecture

```text
+----------------------------------------------------------+
| PRESENTATION                                             |
| Compose screens | Custom timeline | Preview surface     |
+----------------------------+-----------------------------+
                             |
+----------------------------v-----------------------------+
| APPLICATION / FEATURES                                    |
| Home | Editor | Media | Text | Audio | Export | Settings |
+----------------------------+-----------------------------+
                             |
+----------------------------v-----------------------------+
| CONTRACTS / INTEGRATION                                   |
| Commands | Events | DTOs | Feature contracts | JNI API   |
+----------------------------+-----------------------------+
                             |
+----------------------------v-----------------------------+
| NATIVE C++ ENGINE                                         |
| Project | Timeline | Playback | Media | Composition      |
| Rendering | Audio | Effects | Cache | Scheduler | Export |
+----------------------------+-----------------------------+
                             |
+----------------------------v-----------------------------+
| ANDROID PLATFORM ADAPTERS                                 |
| MediaCodec | MediaExtractor | MediaMuxer | SAF | Audio   |
| Surface | Lifecycle | Storage | GPU capability queries  |
+----------------------------------------------------------+
```

## 3.1 Presentation

- Compose: Home, navigation, dialogs, settings, export screens and tool panels.
- Custom Android View: high-frequency timeline drawing, hit testing and gestures.
- Dedicated preview surface/view: native GPU output.
- UI collects input, sends application commands and displays state/events.
- Timeline pointer movement must not redraw the entire Compose hierarchy every frame.

## 3.2 Application layer

Coordinates user workflows, validates application requests, invokes contracts, converts engine events into UI state and manages project/screen lifecycle. It does not implement decoding, timeline evaluation or GPU rendering.

## 3.3 Contracts and integration

Defines stable interfaces, command/event schemas, feature registration and compatibility checks. Feature modules must not depend on unrelated feature internals.

## 3.4 Native engine

Owns editing model and timeline evaluation, decoding schedule, frame delivery, composition, audio mixing, cache/memory budgets and export sessions.

## 3.5 Android platform adapters

Kotlin/platform adapters own Android-specific integration where appropriate:
- Storage Access Framework and URI permissions.
- MediaCodec, MediaExtractor and MediaMuxer lifecycle wrappers.
- Android lifecycle and Surface lifecycle.
- Runtime permissions.
- Audio focus and device routing.
- Hardware capability discovery.

C++ consumes narrow platform interfaces. Domain modules must not freely call Android APIs.

# 4. Android Gradle Modules

Start with meaningful module boundaries. Do not create many empty modules without a real responsibility.

```text
:app
:core:common
:core:model
:core:contracts
:core:events
:core:database
:core:storage
:core:diagnostics
:core:designsystem

:feature:home
:feature:editor
:feature:media
:feature:text
:feature:audio
:feature:export
:feature:settings

:platform:android-media
:platform:android-storage
:platform:android-audio
:platform:android-rendering

:bridge:jni
```

| Module | Responsibility |
|---|---|
| `:app` | Entry point, dependency composition, navigation host |
| `:core:common` | Small shared utilities and result primitives |
| `:core:model` | Stable project and media data models |
| `:core:contracts` | Public application/feature interfaces |
| `:core:events` | Typed application events |
| `:core:database` | Room database and project index |
| `:core:storage` | App file layout and storage abstractions |
| `:core:diagnostics` | Logging, diagnostics and error reporting |
| `:core:designsystem` | Colors, typography and reusable UI |
| `:feature:home` | Home screen and project list |
| `:feature:editor` | Editor screen and coordination |
| `:feature:media` | Media picker/import workflow |
| `:feature:text` | Text editing UI and integration |
| `:feature:audio` | Audio editing UI and workflows |
| `:feature:export` | Export settings, progress and result UI |
| `:feature:settings` | Application settings |
| `:platform:android-media` | Codec/extractor/muxer adapters |
| `:platform:android-storage` | SAF, URI access and storage adapters |
| `:platform:android-audio` | Audio focus, routing and platform support |
| `:platform:android-rendering` | Surface lifecycle and GPU capability adapters |
| `:bridge:jni` | Kotlin-facing native bridge API |

### Dependency rules

```text
:app -> :feature:* -> :core:*

:feature:* -> :core:*
:feature:* -> required public platform/engine contracts

:platform:* -> :core:contracts / :core:model

:bridge:jni -> stable Kotlin/native bridge contracts
```

- `:core:model` must not depend on feature modules.
- Features must not depend on each other's implementation.
- Platform modules must not depend on UI modules.
- Native engine must not depend on Compose or Android UI classes.
- No circular Gradle dependencies.
- Use explicit dependency composition in `:app`; avoid hidden global service locators.

# 5. Native C++ Engine

## 5.1 Proposed source layout

```text
engine/
├── CMakeLists.txt
├── cmake/
├── core/
├── contracts/
├── project/
├── timeline/
├── media/
├── decoder/
├── playback/
├── composition/
├── rendering/
├── audio/
├── effects/
├── transitions/
├── keyframes/
├── cache/
├── scheduler/
├── memory/
├── export/
└── platform/
```

| Module | Responsibility |
|---|---|
| `core` | Result types, IDs, time primitives, common utilities |
| `contracts` | Public C++ interfaces and stable data contracts |
| `project` | Project state, sequences and serialization model |
| `timeline` | Tracks, clips, time mapping and edit commands |
| `media` | Asset metadata and source resolution |
| `decoder` | Decoder abstractions and decoded frame handling |
| `playback` | Master clock, seek, play/pause and frame scheduling |
| `composition` | Layer evaluation and render graph construction |
| `rendering` | GPU resource and rendering backends |
| `audio` | Audio decode/mix/process pipeline |
| `effects` | Effect interfaces and effect processing |
| `transitions` | Transition evaluation and rendering |
| `keyframes` | Animated property evaluation |
| `cache` | Frame, thumbnail and waveform cache |
| `scheduler` | Prioritized tasks and cancellation |
| `memory` | Resource budgets and memory pressure handling |
| `export` | Export session and output pipeline |
| `platform` | Narrow platform adapter interfaces |

## 5.2 C++ rules

- Start with C++17.
- Prefer RAII and explicit ownership.
- Use `std::unique_ptr` for exclusive ownership.
- Use `std::shared_ptr` only where shared lifetime is necessary.
- Do not pass C++ exceptions across JNI; convert failures to structured results.
- Keep public interfaces small and hide implementation headers.
- Define thread affinity for non-thread-safe objects.
- UI objects must not own decoder or GPU internals.

# 6. JNI Bridge

JNI is a boundary, not a general-purpose message bus.

## 6.1 Responsibilities

- Create/destroy engine sessions.
- Submit coarse-grained commands.
- Receive structured state/events.
- Attach/detach preview surfaces through controlled APIs.
- Convert native errors into stable Kotlin representations.
- Avoid per-frame object churn and unnecessary data copying.

## 6.2 Initial Kotlin API sketch

This is a design sketch, not a frozen API. Handle ownership and exact native signatures must be finalized in Phase 0/1.

```kotlin
package com.vyro.bridge

class NativeEngine private constructor(
    private val nativeHandle: Long
) {
    external fun release()
    external fun createProject(configJson: String): Long
    external fun openProject(projectId: Long): Int
    external fun closeProject(projectId: Long): Int
    external fun submitCommand(projectId: Long, commandJson: String): Int
    external fun seek(projectId: Long, positionUs: Long): Int
    external fun play(projectId: Long): Int
    external fun pause(projectId: Long): Int
    external fun attachPreviewSurface(
        projectId: Long,
        surface: android.view.Surface
    ): Int
    external fun detachPreviewSurface(projectId: Long): Int
    external fun startExport(projectId: Long, configJson: String): Long
    external fun cancelExport(exportId: Long): Int

    companion object {
        init { System.loadLibrary("vyro_engine") }
        external fun create(): NativeEngine
    }
}
```

## 6.3 JNI safety rules

- Every native handle has one documented owner.
- Release is idempotent or returns a defined invalid-state error.
- Native callbacks must not access destroyed Activities or Views.
- Manage JNI local/global references correctly.
- Never cache `JNIEnv*` across threads.
- Attach/detach native threads correctly when callbacks are needed.
- Do not hold native locks while invoking Kotlin callbacks.
- Prevent unsafe callback re-entry.
- Define cancellation and shutdown ordering.

## 6.4 Event examples

```text
ProjectOpened
ProjectSaved
TimelineChanged
PlaybackStateChanged
PositionChanged
SeekCompleted
PreviewFrameAvailable
MediaImportProgress
MediaImportFailed
ExportProgress
ExportCompleted
ExportFailed
EngineWarning
EngineError
```

Coalesce/throttle high-frequency position updates for UI display. Preview rendering must not depend on frequent Kotlin callbacks.

# 7. Project and Timeline Data Model

## 7.1 Core entities

```text
Project
 └── Sequence
      ├── Track
      │    └── Clip
      │         ├── Source Reference
      │         ├── Transform
      │         ├── Effects
      │         ├── Keyframes
      │         └── Audio Settings
      ├── Markers
      └── Sequence Settings

MediaAsset
Effect
Transition
Keyframe
ExportPreset
```

## 7.2 Time representation

Do not use floating-point seconds as the authoritative timeline representation. Use integer time units and rational frame rates.

```cpp
struct Rational {
    int64_t numerator;
    int64_t denominator;
};

struct TimeRange {
    int64_t startUs;
    int64_t durationUs;
};

struct ClipTiming {
    int64_t timelineStartUs;
    int64_t sourceInUs;
    int64_t sourceOutUs;
    Rational playbackSpeed;
};
```

This is a starting model, not a complete VFR/speed-ramp implementation. The final design must define:

- Sequence and source timebases.
- Rational frame rates.
- Variable-frame-rate handling.
- Audio sample positions.
- Speed-change and reverse mapping.
- Rounding and range-boundary rules.
- Overflow limits and frame-step semantics.

## 7.3 Editing commands

- AddClip
- RemoveClip
- MoveClip
- TrimClip
- SplitClip
- ChangeClipSpeed
- AddTrack
- RemoveTrack
- ChangeLayerOrder
- UpdateTransform
- AddEffect / RemoveEffect
- AddKeyframe / RemoveKeyframe
- ApplyTransition

Commands validate preconditions and preserve enough information for undo/redo.

## 7.4 Timeline invariants

- Source ranges are valid.
- Timeline duration is not negative.
- Track type is compatible with clip type.
- Clips reference a valid asset or explicit missing-media state.
- Overlap rules are explicit per track type.
- Transition duration is compatible with adjacent clip handles.
- Preview and timeline use the same evaluation result for a given time.
- Invalid edits cannot silently corrupt project state.

# 8. End-to-End Pipelines

## 8.1 Media import

```text
User selects media
  -> Android picker / SAF URI
  -> Permission and URI access validation
  -> Container inspection
  -> Metadata extraction
  -> Codec and capability validation
  -> MediaAsset registration
  -> Thumbnail task
  -> Optional waveform task
  -> Project media library updated
  -> Ready for timeline use
```

Metadata where available:
- Stable asset ID and URI/storage reference.
- MIME type and container.
- Video/audio codec.
- Width, height, rotation and duration.
- Nominal frame rate and VFR indication where detectable.
- Audio sample rate and channel count.
- Bitrate and file size.
- Color transfer/primaries and HDR information where detectable.
- Decoder capability result.

Do not reject media only because its extension is unfamiliar. Inspect actual metadata. Unsupported media must produce a clear error.

## 8.2 Playback and preview

```text
Play / Seek
  -> Playback Controller
  -> Master Timeline Clock
  -> Timeline Evaluator
  -> Active Clip Resolver
  -> Decoder Scheduler
  -> Decoded Frame Queue
  -> Composition Graph
  -> Effects / Transitions / Layers
  -> GPU Renderer
  -> Preview Surface
```

Rules:
- Audio clock may be master during audio playback.
- Silent playback uses a stable monotonic clock.
- Seek invalidates stale queued frames.
- Pause preserves a well-defined timeline position.
- Frame stepping follows sequence timebase rules.
- Avoid unnecessary decoder reinitialization.
- Preview may use lower resolution than export, but composition semantics must match.

## 8.3 Audio

```text
Audio Track Evaluation
  -> Source Resolver
  -> Audio Decoder
  -> Sample Rate / Channel Conversion
  -> Clip Gain / Pan / Fade
  -> Track Effects
  -> Track Mixer
  -> Master Mixer
  -> Audio Output OR Export Encoder
```

Requirements: multiple tracks, accurate timing, gain/fade automation, sample-aware mixing where feasible, A/V sync, audio focus/routing, bounded buffers and underrun diagnostics. Evaluate Oboe for low-latency I/O; validate on actual devices.

## 8.4 Composition and rendering

```text
Timeline Evaluation
  -> Layer and Property Evaluation
  -> Render Graph Construction
  -> Source Texture / Frame Resolution
  -> Transform / Crop / Opacity
  -> Effects and Transitions
  -> Blend and Composite
  -> Output Surface
```

The render graph must support dependencies and multiple layers, not just a fixed single chain. Isolate GPU API details behind a rendering backend interface. OpenGL ES is baseline; Vulkan is optional after capability and performance evaluation.

## 8.5 Export

```text
Export Request
  -> Validate Project and Config
  -> Create Export Session
  -> Resolve Original Media
  -> Timeline Evaluation
  -> Video Frame Pipeline ----> Video Encoder
  -> Audio Mix Pipeline ------> Audio Encoder
  -> MP4 Muxer
  -> Temporary Output
  -> Validate Output
  -> Publish Final Output
```

Export must use original media when accessible, share project semantics with preview, support cancellation, release resources on every exit path, validate temporary output before publishing, query encoder capabilities and handle storage failures. Never report success before finalization.

# 9. Project Persistence and Recovery

## 9.1 Storage split

Use Room for project indexing and lightweight metadata. Use a versioned project document for the full editable structure.

Logical layout:

```text
VYRO/
├── projects/
│   └── <project-id>/
│       ├── project.json
│       ├── project.backup.json
│       ├── thumbnails/
│       ├── proxies/
│       ├── waveforms/
│       ├── cache/
│       └── recovery/
└── exports/
```

Actual paths must comply with Android storage rules and user-selected destinations.

## 9.2 Save requirements

- Schema version and migration.
- Atomic write strategy.
- Backup/recovery strategy.
- Validation before opening.
- Missing-media detection and relinking.
- Corrupt project handling.
- Autosave and recovery snapshot policy.
- Defined undo-history persistence policy.
- Persist and revalidate URI grants; never assume access lasts forever.

# 10. Cache, Proxy, Memory and Scheduling

## 10.1 Cache categories

Decoded frames, thumbnails, waveforms, proxy media and intermediate render results.

Every cache defines key, memory/disk budget, eviction policy, invalidation rules, persistence and concurrency behavior.

## 10.2 Proxy rules

A proxy is an alternate representation of the same logical asset:
- Clip timing references the logical asset.
- Proxy switching does not change trims, speed, effects or placement.
- Export resolves original media when available.
- Missing-original behavior is explicit.
- Proxy generation supports cancellation and capability checks.

## 10.3 Initial scheduling priorities

1. Active playback/audio deadlines.
2. Current preview frame and user seek.
3. Immediate editor interactions.
4. Export work.
5. Proxy generation.
6. Waveform and thumbnail generation.
7. Cache cleanup.

Priorities must be adaptive. If simultaneous editing and export are not supported initially, communicate that limitation clearly.

## 10.4 Memory rules

- Bounded decoded-frame queues.
- Explicit decoder count limits.
- Reuse GPU textures safely.
- Release resources on Surface destruction.
- Handle Android memory pressure.
- Avoid unbounded bitmap retention.
- Profile native allocations.
- Test long sessions and repeated open/close cycles.

# 11. Feature Contracts and Plugin-Style Integration

## 11.1 Feature manifest/contract

Each feature declares:

```text
Feature ID
Feature Version
Minimum Engine Contract Version
Required Capabilities
Required Services
Commands Provided
Events Consumed
Events Produced
UI Entry Points
Lifecycle Hooks
Resource Ownership
Error Codes
Test Requirements
```

## 11.2 Lifecycle

```text
DISCOVERED
  -> VALIDATE MANIFEST
  -> CHECK CONTRACT VERSION
  -> CHECK DEPENDENCIES
  -> REGISTER
  -> ACTIVATE
  -> RUN
  -> DEACTIVATE
  -> RELEASE RESOURCES
```

Registration must fail cleanly if dependencies or contract versions are incompatible.

## 11.3 Initial core feature modules

| Feature | Initial responsibility |
|---|---|
| Media Import | Add video, image and audio assets |
| Basic Timeline | Add, move, trim, split and delete clips |
| Preview Controls | Play, pause, seek and frame step |
| Transform | Position, scale, rotation and crop |
| Text Editing | Add/edit text, font, color and placement |
| Basic Text Animation | Basic in/out/loop presets |
| Audio Editing | Add, trim, split, volume and fade |
| Basic Transitions | Transitions between adjacent clips |
| Basic Effects | Initial color and blur effects |
| Keyframes | Animate supported properties |
| Undo/Redo | History for supported editing commands |
| Export | MP4 output with supported presets |

This is a target scope. Implement each feature only after its dependencies are ready.

# 12. Editor UI and Interaction Plan

## 12.1 Main regions

1. Top action bar.
2. Preview canvas.
3. Selection overlay and transform handles.
4. Playback/time control row.
5. Timeline ruler and playhead.
6. Video track.
7. Overlay/text tracks.
8. Audio tracks.
9. Contextual tool panel.
10. Undo/redo and keyframe controls.

## 12.2 Selection overlay

Support select/deselect, move, resize, rotate, duplicate, delete, edit content and stacking order where supported. The selection UI reflects and edits model state through commands; it is not the authoritative source of transform values.

## 12.3 Timeline interactions

- Horizontal scroll and zoom.
- Vertical track scrolling when needed.
- Stable playhead behavior.
- Clip selection, move and trim.
- Snapping/alignment rules.
- Track visibility, mute and lock where supported.
- Predictable hit areas and gesture conflict resolution.
- Accessibility labels for important controls.

## 12.4 UI performance

- Do not rebuild the entire editor for each playhead tick.
- Keep timeline drawing and hit testing efficient.
- Use immutable snapshots or targeted updates.
- Avoid expensive text layout during every pointer movement.
- Separate preview rendering from timeline drawing.

# 13. Phase-by-Phase Development Roadmap

## Phase 0 — Engineering Foundation

**Work**
- Confirm repository and package naming.
- Finalize module responsibility map.
- Create architecture and dependency documentation.
- Define naming and coding standards.
- Define error/logging conventions.
- Define JNI ownership and threading rules.
- Define test strategy and build configurations.

**Deliverables**
- `ARCHITECTURE.md`
- `MODULE_DEPENDENCIES.md`
- `MODULE_CONTRACTS.md`
- `THREADING_AND_OWNERSHIP.md`
- `CODING_STANDARDS.md`
- `TEST_STRATEGY.md`

**Exit criteria**
- Every initial module has a clear purpose and owner.
- Dependency graph has no cycles.
- Public contracts are reviewed.
- Ownership and lifecycle rules are documented.

## Phase 1 — Project Infrastructure

**Work**
- Create Android project with API 30 minimum.
- Configure Kotlin and Gradle modules.
- Configure NDK and CMake.
- Build minimal native shared library.
- Add JNI create/release smoke test.
- Add native and Kotlin logging.
- Add structured error translation.
- Add unit-test targets.
- Add debug/release variants.
- Add static checks and reproducible build notes.

**Deliverables**
- Buildable Android app.
- Buildable C++ shared library.
- Working JNI smoke test.
- Module skeletons with documented boundaries.
- Initial automated tests.

**Exit criteria**
- Clean build from documented setup.
- Native library loads on API 30+ test device.
- JNI lifecycle test passes.
- Repeated create/release does not leak native sessions.
- Tests run in CI or documented local workflow.

## Phase 2 — Project and Media Foundation

**Work**
- Project model and schema version.
- Project create/open/close.
- Room project index.
- SAF media selection and URI grant persistence.
- Metadata extraction.
- Codec/capability validation.
- Asset registration.
- Asynchronous thumbnails.
- Missing-media state.

**Exit criteria**
- Supported test media imports reliably.
- Invalid/unreadable media reports clear errors.
- Projects reopen with media references intact.
- Metadata work does not block UI.
- Permission loss is handled gracefully.

## Phase 3 — Timeline Foundation

**Work**
- Sequence, Track, Clip and Asset references.
- Timebase and rational frame-rate utilities.
- Source-to-timeline mapping.
- Add/move/trim/split/delete commands.
- Undo/redo for initial commands.
- Track ordering and clip validation.
- Timing edge-case tests.

**Exit criteria**
- Operations are deterministic.
- Boundary and rounding tests pass.
- Undo/redo restores exact prior state.
- Invalid edits do not corrupt project state.

## Phase 4 — Decoder, Preview and Playback

**Work**
- Decoder abstraction.
- MediaExtractor/MediaCodec platform adapter.
- Seek cancellation and stale-frame rejection.
- Bounded frame queues.
- Master clock and frame scheduler.
- Safe preview Surface attach/detach.
- Play, pause, seek and frame stepping.
- Audio clock synchronization design/tests.

**Exit criteria**
- Stable playback on selected API 30+ devices.
- Repeated play/pause/seek stress tests pass.
- No stale frame after seek completion.
- Surface recreation does not crash or leak.
- A/V synchronization is measured against a defined tolerance.

## Phase 5 — Composition and Rendering

**Work**
- Render graph contracts.
- Layer evaluation.
- Transform, crop, opacity and blend baseline.
- OpenGL ES backend.
- GPU resource lifecycle.
- Device capability detection.
- Prototype Vulkan only if justified.

**Exit criteria**
- Multiple layers compose correctly.
- Transform and z-order tests pass.
- Surface loss/recreation is handled.
- GPU cleanup is validated.
- Rendering meets measured target-device budgets.

## Phase 6 — Audio Foundation

**Work**
- Audio source abstraction and decode.
- Sample-rate/channel conversion.
- Track mixer.
- Gain, pan and fade.
- Waveform generation.
- Timeline/audio clock synchronization.
- Oboe and device output evaluation.

**Exit criteria**
- Multi-clip timing is correct.
- Drift is measured in defined scenarios.
- Underruns are diagnosed.
- Pause/seek/stop lifecycle is stable.

## Phase 7 — Cache, Proxy and Memory

**Work**
- Cache keys and budgets.
- Frame/thumbnail/waveform caches.
- Proxy mapping and generation.
- Cancellation.
- Memory pressure handling.
- Task priorities.
- Leak and long-session tests.

**Exit criteria**
- Budgets are enforced.
- Proxy/original switching preserves edit state.
- Cancelled tasks release resources.
- Long sessions show no unbounded growth.

## Phase 8 — Persistence and Recovery

**Work**
- Atomic save.
- Autosave.
- Recovery snapshots.
- Schema migration.
- Corrupt/incomplete project handling.
- Undo-history persistence policy.

**Exit criteria**
- Forced process termination recovers last valid save/snapshot.
- Migration tests pass.
- Corrupt data does not crash the app.
- Missing media can be relinked or reported.

## Phase 9 — Export Foundation

**Work**
- Export configuration and capability validation.
- Export session lifecycle.
- Timeline evaluation using preview semantics.
- Encoder and muxer integration.
- Temporary output and validation.
- Progress, cancellation and errors.
- Device-specific codec tests.

**Exit criteria**
- Output opens in independent players.
- Duration, dimensions and frame rate match supported config.
- A/V timing is validated.
- Cancellation/storage failure release resources.
- Success is reported only after finalization.

## Phase 10 — Core Feature Integration

Implement in dependency order:
1. Media import.
2. Basic timeline operations.
3. Playback controls.
4. Transform and crop.
5. Text editing.
6. Basic text animation.
7. Audio editing.
8. Basic transitions.
9. Basic effects.
10. Keyframes.
11. Undo/redo coverage.
12. Export UI and presets.

Every feature needs a contract, UI entry point, engine integration, unit tests, integration tests, error states and documentation.

## Phase 11 — Editor UI Completion

- Complete editor layout.
- Integrate custom timeline view.
- Selection overlay.
- Contextual toolbars.
- Gesture arbitration.
- Responsive layouts.
- Accessibility.
- Validate screenshot-reference workflows on actual devices.

## Phase 12 — Production Hardening

- Device matrix.
- Long playback/export stress tests.
- Memory/GPU profiling.
- Thermal and battery behavior.
- Crash recovery.
- Storage exhaustion.
- Permission revocation.
- Codec and malformed-file tests.
- UI responsiveness.
- Regression suite.
- Release build verification.

# 14. Testing Strategy

## 14.1 Test levels

| Type | Purpose |
|---|---|
| Unit | Algorithms and data models |
| Contract | Interface compatibility |
| Integration | Connected modules |
| Instrumentation | Android framework integration |
| Device | Actual codec/GPU behavior |
| Stress | Repeated operations and long sessions |
| Performance | Frame time, memory, CPU, GPU and export |
| Recovery | Process kill, interrupted save/export and relaunch |

## 14.2 Essential scenarios

- Import short/long video, audio-only media and images.
- Import rotated and variable-frame-rate media.
- Try unsupported/corrupt media.
- Revoke access to imported URI.
- Add multiple clips and tracks.
- Trim at exact boundaries.
- Split at first, middle and final valid boundary.
- Repeated play/pause.
- Rapid seek during decode.
- Pause then frame-step.
- Destroy/recreate preview Surface.
- Add multiple overlay layers.
- Save/reopen project.
- Kill process during autosave.
- Cancel export.
- Fill storage during export.
- Run long editing session and monitor memory.
- Test thermal throttling.

## 14.3 Device matrix

Test representative low-memory, mid-range and high-performance devices, different GPU vendors, Android 11+ versions and differing hardware codec capabilities. API level alone does not guarantee codec or GPU feature support.

# 15. Performance and Quality Targets

These are targets to measure, not guarantees.

| Area | Initial target |
|---|---|
| Timeline interaction | Aim for 60 FPS on supported devices |
| Preview | Aim for 30 FPS where source/device permit |
| Seeking | No stale frame after seek completion |
| Playback | Stable clock and bounded queues |
| A/V sync | Define and measure acceptable drift |
| Memory | Enforced budgets; no unbounded growth |
| Export | Capability-aware profiles |
| Recovery | Last valid state recoverable |
| UI | No heavy media work on main thread |

Performance reports must record device, Android version, media characteristics, codec, resolution, frame rate and thermal state.

# 16. Error Handling and Diagnostics

Error categories:
- Invalid project state.
- Missing media.
- Permission/URI access failure.
- Unsupported container or codec.
- Decoder/encoder initialization failure.
- GPU capability or Surface failure.
- Out-of-memory/resource exhaustion.
- Storage full/write failure.
- Export cancellation.
- Internal engine failure.

Every error should include a stable code, module/source, user-safe message, diagnostic details, recoverability classification and optional retry/relink action.

Use structured logs with timestamp, severity, module, session/project correlation ID, operation ID and error code. Do not log private media contents or sensitive user data.

# 17. Coding and Repository Rules

1. One module has one clear responsibility.
2. No circular dependencies.
3. No direct UI-to-decoder calls.
4. No unbounded queues or caches.
5. No floating-point seconds as authoritative timeline time.
6. No silently swallowed errors.
7. No blocking media work on the UI thread.
8. No undocumented native ownership.
9. No feature complete without tests.
10. Avoid unrelated refactoring during focused bug fixes.
11. Run the smallest relevant validation first; use full builds when warranted.
12. Keep generated/build files out of source control.
13. Use resource strings for user-facing text.
14. Comments explain intent and invariants, not obvious code.
15. Keep documentation synchronized with implementation.

# 18. Build and Repository Configuration

## 18.1 Android configuration

- Minimum SDK: 30.
- Choose a currently supported compile/target SDK at implementation time.
- Pin mutually compatible stable Kotlin and Android Gradle Plugin versions.
- Pin NDK and CMake versions.
- Use Gradle version catalogs.
- Separate debug and release configurations.
- Keep signing secrets out of source control.

Do not invent toolchain version numbers in this plan. Confirm compatibility when creating the repository.

## 18.2 Native build

- One root CMake project.
- Native targets split by responsibility.
- Explicit include visibility.
- Native unit-test targets.
- Build ABIs based on distribution and device testing.
- Keep Vulkan sources optional and isolated.

## 18.3 Repository layout

```text
VYRO/
├── app/
├── core/
├── feature/
├── platform/
├── bridge/
├── engine/
├── plugins/
├── shaders/
├── tests/
├── docs/
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── CMakeLists.txt
├── .gitignore
└── README.md
```

# 19. Phase 0 and Phase 1 Task Checklists

## Phase 0

- [ ] Create repository.
- [ ] Write README.
- [ ] Write architecture document.
- [ ] Write module dependency map.
- [ ] Write module contract policy.
- [ ] Write threading and ownership policy.
- [ ] Write error handling policy.
- [ ] Write coding standards.
- [ ] Write test strategy.
- [ ] Confirm package/application ID.
- [ ] Confirm supported ABIs and test devices.

## Phase 1

- [ ] Create Android application module.
- [ ] Configure API 30 minimum.
- [ ] Configure hybrid UI baseline.
- [ ] Create initial Gradle modules.
- [ ] Configure NDK/CMake.
- [ ] Build `libvyro_engine`.
- [ ] Implement native engine create/destroy.
- [ ] Implement JNI error/result mapping.
- [ ] Add minimal diagnostic screen.
- [ ] Add Kotlin unit test.
- [ ] Add C++ unit test.
- [ ] Add JNI instrumentation smoke test.
- [ ] Verify repeated native session create/release.
- [ ] Verify clean build from fresh checkout.
- [ ] Record exact toolchain versions.

# 20. Decisions Deliberately Deferred

Decide these only after prototypes or measurements:
- Vulkan implementation and device coverage.
- Proxy resolutions and quality presets.
- Cache budgets by device class.
- Maximum concurrent decoder count.
- Export profiles and bitrates.
- Optional HEVC/AV1 support.
- Whether FFmpeg or another third-party media component is necessary.
- AI feature execution model.
- Runtime downloadable plugin support.
- Final performance thresholds after baseline profiling.

This avoids locking architecture to assumptions that have not been tested.

# 21. Definition of a Successful Core Release

The core release is ready only when:
- Users can create and reopen projects.
- Supported video, image and audio media can be imported.
- Media access failures are handled clearly.
- Multiple timeline tracks work.
- Basic clip editing is deterministic.
- Preview and timeline share one timing model.
- Play, pause, seek and frame-step are stable on tested devices.
- Basic layers and transforms render correctly.
- Text can be added and edited.
- Basic audio editing/mixing works.
- Projects save and recover.
- Export produces valid playable output.
- Cancellation and failure paths release resources.
- Automated regression tests pass.
- Defined long-session and stress tests pass.

# 22. Working Protocol for Every Future Task

1. Inspect the existing project and relevant files.
2. Confirm intended behavior and reproduction steps.
3. Identify responsible module and contracts.
4. Trace the full data flow before changing code.
5. Make the smallest correct change.
6. Add or update tests.
7. Run focused validation.
8. Run broader builds/tests when warranted.
9. Inspect logs and runtime behavior.
10. Update documentation and task status.
11. Report exactly what changed, what passed, what failed and what remains unverified.

Never claim a feature is complete based only on generated code or a successful compile.

# 23. Immediate Next Step

Begin with **Phase 0 — Engineering Foundation**, then implement **Phase 1 — Project Infrastructure**.

Before creating the project, establish:
1. Repository/package identity.
2. Exact compatible Gradle, Kotlin, AGP, NDK and CMake versions.
3. Module dependency graph.
4. Native ownership and threading rules.
5. JNI lifecycle contract.
6. Build and test workflow.

Then create the smallest working application that:
- Launches on Android API 30+.
- Loads the native library.
- Creates a native engine session.
- Reports a structured result.
- Destroys the session safely.
- Passes Kotlin, C++ and JNI smoke tests.

Only after this foundation passes should media import and project management begin.

---

**End of VYRO Master Project Blueprint**

This document is the planning baseline. It describes intended architecture and acceptance criteria; it does not claim the application, modules or pipelines have already been implemented or device-tested.
