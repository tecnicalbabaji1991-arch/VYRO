# Threading and Ownership Policy

## Native Ownership
- Every native engine instance (`nativeHandle: Long`) has a single owner (`VyroEngineSessionManager`).
- `release()` is idempotent and clears all active project sessions registered under that engine instance.
- C++ objects use `std::unique_ptr` for exclusive ownership and mutex-protected handle tables across JNI calls.
- C++ exceptions must never propagate across the JNI boundary; errors are translated into `EngineErrorCode` integers and structured error payloads.

## Threading Rules
- **Main Thread**: Compose UI state collection, `TimelineSurfaceView` `onDraw`, gesture capture.
- **IO / Engine Dispatcher**: Atomic JSON saves, Room queries, `EngineSessionContract` command submission, and lifecycle stress runs.
- **JNI Safety**: `JNIEnv*` is never cached globally across threads. Native locks are never held while invoking JVM callbacks.
