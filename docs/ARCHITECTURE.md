# VYRO Architecture

## Layered Design

```text
+-------------------------------------------------------------------+
| PRESENTATION (:app, :feature:home, :feature:editor)               |
| Jetpack Compose screens + Custom TimelineSurfaceView (Canvas)     |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
| CONTRACTS & DOMAIN (:core:contracts, :core:model, :core:common)   |
| Commands | Events | Rational Timebase | Authoritative Evaluator   |
+----------------+--------------------------------+-----------------+
                 |                                |
+----------------v----------------+ +-------------v-----------------+
| LOCAL PERSISTENCE               | | ENGINE BRIDGE (:bridge:jni)   |
| (:core:database)                | | NativeEngine JNI API          |
| Room Index + Atomic JSON        | | C++17 Engine / Parity Fallback|
+---------------------------------+ +-------------------------------+
```

## Core Invariants
1. **Single Source of Truth**: Project state and `TimelineEvaluator` are authoritative. UI overlays and timeline gestures dispatch `EditCommand` objects and render returned snapshots.
2. **Non-Destructive Editing**: Edits mutate project instructions in memory and atomic JSON documents without modifying source media files.
3. **Integer Timebase**: Timeline positions and durations use `Long` microseconds and `Rational` frame rates—never floating-point seconds.
4. **UI Thread Isolation**: Heavy serialization, database queries, and engine stress tests execute off the main thread.
