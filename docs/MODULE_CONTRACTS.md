# Module Contracts

Each module exposes a narrow public surface and hides internal data sources, serialization formats, and native handles.

| Module | Owned State / Responsibility | Public Contracts |
|---|---|---|
| `:core:common` | Result/error primitives & structured logs | `EngineResult`, `EngineError`, `VyroLogger` |
| `:core:model` | Authoritative timeline & project schema | `ProjectDocument`, `Sequence`, `Track`, `Clip`, `Rational`, `TransitionSpec`, `TransformKeyframe` |
| `:core:contracts` | Command/event protocol, evaluators & hardening | `EditCommand`, `CommandHistory`, `EngineSessionContract`, `ProjectRepository`, `TimelineEvaluator`, `RenderGraphEvaluator`, `AudioMixEngine`, `ExportCoordinatorContract`, `RuntimeHardeningMonitorContract`, `ProductionHardeningVerifier` |
| `:core:database` | Room project index & atomic JSON files | `LocalProjectRepository`, `VyroDatabase`, `AtomicProjectStorage`, `ProjectSchemaMigrator` |
| `:core:designsystem` | Pro Studio Dark theme & typography | `VyroTheme`, `VyroColors`, `VyroTypography` |
| `:bridge:jni` | Native JNI session ownership & telemetry | `NativeEngine`, `VyroEngineSessionManager` |
| `:platform:android-media` | Media3/Codec playback, GLES render, audio, cache, export, hardening | `AndroidMediaInspector`, `AndroidPlaybackCoordinator`, `GlesCompositionRenderer`, `AndroidAudioPlaybackEngine`, `AndroidCacheAndProxyManager`, `AndroidExportEngine`, `AndroidRuntimeHardeningMonitor` |
| `:feature:home` | Project Hub & creation flows | `HomeScreen`, `HomeViewModel` |
| `:feature:media` | Photo/audio picker & studio materials import | `MediaSelectorScreen`, `MediaSelectorViewModel` |
| `:feature:editor` | Responsive Editor UI, custom timeline, selection overlays, contextual toolbars & diagnostics | `EditorScreen`, `EditorPreviewCanvas`, `TimelineSurfaceView`, `BottomEditorToolStrip`, `ContextualSelectionStrip`, `ExportStudioDialog`, `DiagnosticsScreen` |
