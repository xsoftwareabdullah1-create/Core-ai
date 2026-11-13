# ULTRON AI — Advanced Runtime Architecture

## Runtime layers

- Kotlin and Jetpack Compose: Android lifecycle, UI, permissions, encrypted settings, coroutine orchestration and tool execution.
- OpenRouter agent: model requests, function schemas, bounded tool-call loop and conversational state.
- Python via Chaquopy: bundled deterministic planning utilities and structured plan validation. Python has no unrestricted shell or Android privileges.
- C++ and JNI: narrow native boundary for native operations.
- Rust: native classification routines exposed through a C ABI and called by JNI.
- GitHub Actions: remote APK compilation and artifact publication.

## Trust boundaries

1. Model output is untrusted input, including tool arguments.
2. Kotlin validates tool names and arguments before execution.
3. Device actions, deletion, app launching and remote builds require explicit user approval.
4. Accessibility access is optional and controlled by Android Settings.
5. Python validates plans but cannot directly invoke Android APIs or shell commands.
6. Native code receives bounded strings and returns bounded classifications; it is not an arbitrary command executor.

## Native build

CI compiles the Rust shared library for Android arm64-v8a using cargo-ndk, stages it in the app JNI libraries directory, and invokes the Android Gradle build. CMake links the Rust shared object into the JNI bridge.

## Scope and status

The native classifier is a deterministic routing helper, not an LLM and not a self-modifying agent. The Python module validates a bounded tool plan; Kotlin remains the sole authority for user approval and device actions.
