# VYRO Test Strategy

## Verification Pyramid
1. **Pure Unit Tests (`:core:model`, `:core:contracts`, `:core:database`)**:
   - `Rational` arithmetic, frame stepping, microsecond snapping, overflow safety.
   - `TimelineEvaluator` deterministic invariants (add, move, trim, split, delete, undo/redo exact state restoration).
   - `AtomicProjectStorage` write, backup rotation, and corrupt-file recovery.
2. **Engine Bridge Lifecycle Tests (`:bridge:jni`)**:
   - Session create -> open -> command -> seek -> play/pause -> close -> release.
   - 50-iteration create/release leak verification (`activeSessionCount == 0`).
3. **Robolectric CUJ Tests (`:app`)**:
   - Project Hub launch, project creation, Editor workspace navigation, text clip add/split/undo, and Diagnostics smoke test execution.
4. **Native C++ Tests (`engine/tests/`)**:
   - Standalone C++17 smoke tests verifying project state and timeline edit determinism when built with CMake.
