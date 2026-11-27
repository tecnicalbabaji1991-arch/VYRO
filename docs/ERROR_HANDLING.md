# Error Handling Policy

## Error Categories
- `INVALID_STATE` — Lifecycle violation (e.g., command submitted to closed or released handle).
- `INVALID_TIMELINE_EDIT` — Out-of-bounds trim, invalid split point, or negative duration.
- `CORRUPT_PROJECT` — Unparseable or schema-incompatible project JSON document.
- `STORAGE_FAILURE` — Atomic file write/read failure or disk exhaustion.
- `UNSUPPORTED_CAPABILITY` — Codec, GPU, or ABI capability missing on device.
- `NATIVE_BRIDGE_FAILURE` — Native library load failure or JNI marshalling fault.

## Structured Diagnostic Logging
All logs include timestamp, severity, module tag, project/session correlation ID, and error code. Never log private media contents or sensitive file URIs in production logs.
