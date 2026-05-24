# Repository Guidelines

## Project Structure & Module Organization
This repository is a multi-module Android/Kotlin project:

- `core/`: the reusable MVI library (`src/main/java/cc/colorcat/mvi`).
- `app/`: sample Android app demonstrating library usage (`src/main/java/cc/colorcat/mvi/sample`).
- Unit tests: `core/src/test` and `app/src/test`.
- Instrumentation tests: `core/src/androidTest` and `app/src/androidTest`.
- Android resources/assets: `app/src/main/res`.
- Build/version config: `gradle/libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`.

## Build, Test, and Development Commands
Use the Gradle wrapper from repo root:

- `./gradlew :core:assemble` - build the library module.
- `./gradlew :app:assembleDebug` - build the sample debug APK.
- `./gradlew :core:test` - run core JVM unit tests.
- `./gradlew :app:testDebugUnitTest` - run app JVM unit tests.
- `./gradlew :core:connectedAndroidTest` - run instrumentation tests (requires emulator/device).
- `./gradlew publishReleasePublicationToGitHubPackagesRepository -Pgpr.personal.user=<user> -Pgpr.personal.key=<token>` - publish `core` artifact.

Project targets Java 17 and Android compile SDK 34.

## Coding Style & Naming Conventions
- Follow `.editorconfig`: 4-space indentation, max line length 120, LF endings, UTF-8, final newline.
- Kotlin style is the default; trailing commas are enabled.
- Use descriptive Kotlin naming: types in `PascalCase` (`DashboardViewModel`), functions/properties in `camelCase` (`handleLoadCategory`), and constants in `UPPER_SNAKE_CASE`.
- Keep package names under `cc.colorcat.mvi...` and align file names with primary class/interface names.

## Testing Guidelines
- Frameworks: JUnit4 (`org.junit`), `kotlinx-coroutines-test`, AndroidX JUnit/Espresso for instrumented tests.
- Place tests next to module scope and mirror production package paths.
- Test class naming: `<Subject>Test` (for example, `ReactiveContractImplTest`).
- Prefer focused behavior-style test names using backticks in Kotlin.

## Commit & Pull Request Guidelines
- Recent history favors short, imperative commit subjects, often with prefixes like `feat: ...`, `refactor: ...`, `add test`, and `improve doc`.
- Recommended format: `<type>: <concise action>`; keep commits scoped to one change.
- PRs should include what changed and why, modules touched (`core`, `app`), test commands run with outcomes, and UI screenshots/GIFs when `app` UI behavior changes.
