# Contributing

Thanks for your interest in K-MVI. Contributions are welcome through GitHub issues and pull requests.

## Development Setup

- Use JDK 17.
- Use the Gradle wrapper from the repository root.
- Keep Kotlin code formatted with the official Kotlin style.

## Checks

Run the focused core test suite before opening a pull request:

```bash
./gradlew :core:test
```

For changes that touch the sample app or Android integration, also run:

```bash
./gradlew :app:compileDebugKotlin
```

## Pull Requests

- Keep changes focused and describe the user-visible behavior change.
- Add or update tests for behavior changes.
- Update `README.md` and `README.zh-CN.md` when public APIs or documented behavior changes.
- Use Conventional Commit style for commit messages, such as `feat:`, `fix:`, `docs:`, or `chore:`.

## Reporting Issues

When reporting a bug, include:

- K-MVI version
- Kotlin, AGP, and Android API versions
- A minimal code sample or reproduction steps
- Expected and actual behavior
