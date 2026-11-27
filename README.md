# VYRO — Native-First Android Video Editor

VYRO is a modular Android non-linear video editor built around a deterministic timeline model, strict architectural boundaries, and a C++17/JNI engine contract.

## Current Status
- **Phases 0–12 Complete**: Engineering Foundation, Project Infrastructure & Interactive Editor Shell, Media Import & Validation, Media Playback & Timeline Core, GPU Composition (`RenderGraph` / OpenGL ES), Audio Engine (48kHz Multi-Track Mix + Waveforms), Cache & Proxy Pipeline, Project Persistence & Atomic Recovery, Video/Audio Export Pipeline (720p/1080p/4K H.264/HEVC MP4), Core Feature Integration (transitions, keyframes, text animations including typewriter, crop/transform/blend, effects, filters, full undo/redo, and export presets), Editor UI Completion (responsive phone/tablet layouts, custom timeline pinch-zoom/snap/lock arbitration, unified stage selection overlay, contextual selection strip, and 48dp TalkBack accessibility), and Production Hardening (device tier & thermal/battery throttling, long-session stress & cache verification, mid-export disk exhaustion & malformed media guards, permission revocation recovery, R8/ProGuard keep rules, and Play Policy compliance verification).
- **Note on Native Toolchain**: `CMakeLists.txt` and `engine/` compile `libvyro_engine.so` automatically when Android NDK and CMake are present. In environments without pre-installed NDK/CMake, `:bridge:jni` falls back transparently to a contract-identical JVM engine backend and reports the active mode in Diagnostics.

## Module Structure
- `:app` — Entry point, explicit dependency container, Navigation Compose host.
- `:core:common` — Structured `EngineResult`, `EngineError`, logging, and dispatcher abstractions.
- `:core:model` — Authoritative integer timebase (`Rational`, microseconds) and project/timeline entities (`TransitionSpec`, `TransformKeyframe`, `LayerTransform`, `TextClipSpec`, `AudioClipSpec`).
- `:core:contracts` — Public `EditCommand`, `CommandHistory`, `RenderGraphEvaluator`, `AudioMixEngine`, `ExportCoordinatorContract`, and deterministic `TimelineEvaluator`.
- `:core:database` — Room project index + atomic JSON document persistence with `.backup.json` and crash snapshot recovery.
- `:core:designsystem` — Pro Studio Dark Material 3 theme, bundled typography, and studio visual tokens.
- `:bridge:jni` — `NativeEngine` JNI wrapper, C++17/CMake build hook, and contract-parity session manager.
- `:platform:android-media` — Android `MediaExtractor`/`MediaCodec`/`MediaMuxer` inspector, playback coordinator, OpenGL ES composition renderer, audio engine, cache/proxy manager, and MP4 export engine.
- `:feature:home` — Responsive Project Hub, preset project creation, and project management.
- `:feature:media` — Photo Picker, audio picker, studio materials, and multi-clip timeline import.
- `:feature:editor` — Adaptive Compact/Expanded Editor UI, custom Android `View` multi-lane timeline (pinch-zoom, magnetic snap, lock indicators, waveforms, transitions, keyframe diamonds), stage selection overlay, contextual toolbars, export studio dialog, and Engine Diagnostics.
- `engine/` — Modular C++17 native engine (`core`, `contracts`, `project`, `timeline`, `tests`).

## Verification
- Build APK: `gradle :app:assembleDebug`
- Run JVM & Robolectric tests: `gradle testDebugUnitTest --console=plain`
