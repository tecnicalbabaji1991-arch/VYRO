# VYRO Test Strategy

## Verification Pyramid
1. **Pure Unit & Hardening Stress Tests (`:core:model`, `:core:contracts`, `:core:database`, `:platform:android-media`)**:
   - `Rational` arithmetic, frame stepping, microsecond snapping, overflow safety.
   - `TimelineEvaluator` deterministic invariants (add, move, trim, split, delete, lock/mute, transitions, keyframes, undo/redo exact state restoration across every `EditCommand`).
   - `RenderGraphEvaluator` linear keyframe interpolation, `SLIDE_LEFT`/`CROSS_DISSOLVE`/`ZOOM_IN` transitions, and `TYPEWRITER` character reveal.
   - `AudioMixEngine` gain, pan, fade envelopes, and mixer timing; `AndroidCacheAndProxyManager` budget trimming and proxy mapping.
   - `AtomicProjectStorage` atomic write, backup rotation, crash snapshot recovery, v1->v3 migration, and transition/keyframe round-trips.
   - **Phase 12 Long-Session & Failure Stress Tests**: 200-step continuous edit/render/undo/redo + 500-put bounded cache churn (`ProductionHardeningVerifier`), mid-export storage exhaustion (`ENOSPC`), 0-byte & truncated MP4 rejection, `SecurityException` permission revocation handling, export preset/codec matrix, thermal (`SEVERE`/`CRITICAL`) and battery-saver throttling, and Play Policy manifest compliance verification.
2. **Engine Bridge Lifecycle Tests (`:bridge:jni`)**:
   - Session create -> open -> command -> seek -> play/pause -> close -> release.
   - 50-iteration create/release leak verification (`activeSessionCount == 0`).
3. **Robolectric CUJ & Gesture Tests (`:app`)**:
   - Project Hub launch, project creation, Media import & relink (`MISSING` & `PERMISSION_REVOKED`), playback & frame stepping, GPU Crop/Blend/Rotation, Audio Mixer & waveforms, Proxy mode, Crash Recovery & Corrupt fallback, MP4 Export presets & validation, Transitions/Keyframes/Typewriter/Undo-Redo, Phase 11 Responsive Tablet Split Layout, Contextual Toolbars, Track Lock, Stage Selection Overlay, and `TimelineSurfaceView` gesture arbitration & accessibility actions, plus Phase 12 Production Hardening diagnostics card & tablet/phone device matrix regression checks.
4. **Native C++ Tests (`engine/tests/`)**:
   - Standalone C++17 smoke tests verifying project state and timeline edit determinism when built with CMake.
