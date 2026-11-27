# Module Contracts

Each module exposes a narrow public surface and hides internal data sources, serialization formats, and native handles.

| Module | Owned State / Responsibility | Public Contracts |
|---|---|---|
| `:core:common` | Result/error primitives & structured logs | `EngineResult`, `EngineError`, `VyroLogger` |
| `:core:model` | Authoritative timeline & project schema | `ProjectDocument`, `Sequence`, `Track`, `Clip`, `Rational` |
| `:core:contracts` | Command/event protocol & evaluator | `EditCommand`, `EngineSessionContract`, `ProjectRepository`, `TimelineEvaluator` |
| `:core:database` | Room project index & atomic JSON files | `LocalProjectRepository`, `VyroDatabase`, `AtomicProjectStorage` |
| `:core:designsystem` | Pro Studio Dark theme & typography | `VyroTheme`, `VyroColors`, `VyroTypography` |
| `:bridge:jni` | Native JNI session ownership & telemetry | `NativeEngine`, `VyroEngineSessionManager` |
| `:feature:home` | Project Hub & creation flows | `HomeScreen`, `HomeViewModel` |
| `:feature:editor` | Timeline View, preview overlay & diagnostics | `EditorScreen`, `DiagnosticsScreen` |
