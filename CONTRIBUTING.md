# Contributing to Sterna

Sterna is written and maintained by one developer and still changes often, so expect the code around your patch to move.

## Build

You need JDK 17, the Android SDK (compileSdk 36) and a device or emulator on Android 8.0 or newer. The Gradle wrapper pins Gradle.

```sh
# Every module that carries tests
./gradlew :core:jmap:test :core:imap:test :core:data:testDebugUnitTest :app:testDebugUnitTest

# Build and install a debug APK
./gradlew :app:installDebug
```

What each module holds is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Tests

Every test here is a JVM unit test: nothing renders a composable or opens a real database, so read a green suite as exactly that much.

- **A decision that matters is extracted as a pure function and run by a test.** A test that recomputes the rule to choose what to assert is a copy of the rule, and stays green when the rule is inverted.
- **Where the decision can't be reached (a composable, a ViewModel), a source lint pins the call site** by reading the file as text. Pin whole calls and whole arguments, not substrings. A lint proves a call is written, never that it works, and its header must say which of these four holes apply to it:
  1. An argument name says nothing about what it is bound to. Where you can, lift the value into the tested function instead.
  2. A rule about the arguments says nothing about the expression around the call (`!f(...)`, `f(...) || x`).
  3. A pinned line says nothing about its position in a control structure, nor about execution order.
  4. Nothing here can hold the value a composable actually collects.
- **A fix comes with a test seen to fail before it and pass after.** Then break the finished code once on purpose and watch that test go red.

## Conventions

- Kotlin official code style. Keep `:core:jmap` and `:core:imap` free of Android dependencies.
- A comment says only what the code can't: an invariant, a trap, or a decision and what I ruled out. No `File.kt:NNN` references, no history of what the code used to do, no paraphrase of the line below. The reason a guard exists does belong in the source.
- FOSS dependencies only, GPLv3-compatible: no Google Play Services, Firebase, analytics or telemetry.

## License

By contributing, you agree your contributions are licensed under the GPLv3.
