# VYRO — Native-First Android Video Editor

VYRO is a modular Android non-linear video editor built around a deterministic timeline model, strict architectural boundaries, and a C++17/JNI engine contract.

## Current Status
- **Phase 0 (Engineering Foundation)**: Complete (architecture, module map, contracts, threading/ownership, coding standards, error handling, and test strategy).
- **Phase 1 (Project Infrastructure & Interactive Editor Shell)**: Implemented with API 30+ multi-module Gradle workspace, C++17 engine & JNI bridge sources, local Room + atomic JSON persistence, Engine Diagnostics console, and interactive multi-lane Editor Shell.
- **Note on Native Toolchain**: `CMakeLists.txt` and `engine/` compile `libvyro_engine.so` automatically when Android NDK and CMake are present. In environments without pre-installed NDK/CMake, `:bridge:jni` falls back transparently to a contract-identical JVM engine backend and reports the active mode in Diagnostics.

## Module Structure
- `:app` — Entry point, explicit dependency container, Navigation Compose host.
- `:core:common` — Structured `EngineResult`, `EngineError`, logging, and dispatcher abstractions.
- `:core:model` — Authoritative integer timebase (`Rational`, microseconds) and project/timeline entities.
- `:core:contracts` — Public `EditCommand`, `EngineSessionContract`, `ProjectRepository`, and deterministic `TimelineEvaluator`.
- `:core:database` — Room project index + atomic JSON document persistence with `.backup.json` recovery.
- `:core:designsystem` — Pro Studio Dark Material 3 theme, bundled typography, and studio visual tokens.
- `:bridge:jni` — `NativeEngine` JNI wrapper, C++17/CMake build hook, and contract-parity session manager.
- `:feature:home` — Project Hub, preset project creation, and project management.
- `:feature:editor` — Interactive Editor Shell, custom Android `View` multi-lane timeline, text overlay handles, and Engine Diagnostics.
- `engine/` — Modular C++17 native engine (`core`, `contracts`, `project`, `timeline`, `tests`).

## Verification
- Build APK: `gradle :app:assembleDebug`
- Run JVM & Robolectric tests: `gradle testDebugUnitTest`
