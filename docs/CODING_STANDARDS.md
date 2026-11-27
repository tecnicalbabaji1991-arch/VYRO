# Coding Standards & Error Handling

## Coding Standards
1. Kotlin 100% for Android layers; C++17 for native engine modules.
2. Minimum SDK API 30 (`minSdk = 30`).
3. No floating-point seconds for authoritative clip/sequence timing.
4. No unbounded queues or caches.
5. Resource strings for user-facing labels; `Modifier.testTag(...)` on primary interactive controls.
6. Custom Android `View` for high-frequency timeline rendering and scrubbing to avoid Compose tree churn.

## Error Taxonomy
Every operational failure maps to a stable `EngineError` containing:
- `code: EngineErrorCode` (`INVALID_STATE`, `INVALID_TIMELINE_EDIT`, `CORRUPT_PROJECT`, `STORAGE_FAILURE`, `UNSUPPORTED_CAPABILITY`, `NATIVE_BRIDGE_FAILURE`)
- `sourceModule: String`
- `userMessage: String`
- `diagnosticDetail: String`
- `recoverable: Boolean`
