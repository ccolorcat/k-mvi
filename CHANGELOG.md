# Changelog

This changelog starts at version 1.4.1. Earlier release history is not recorded here.

## 1.4.4

- Fixed a still-active contract silently going inert: a `CancellationException` escaping an intent handler (e.g. an uncaught `withTimeout`) now routes through `FatalErrorHandler` and closes the intent queue, so later `dispatch()` returns `Unavailable` instead of `Submitted`.

## 1.4.1

- Added `FatalErrorHandler` for unrecoverable pipeline failures.
- Documented fatal reducer failures and retry/fatal error interaction.
- Added consumer R8 rules for K-MVI public API and MVI marker subtypes.
- Updated sample ViewBinding delegates to avoid reflection-based binding lookup.
- Updated README examples and release metadata for public publishing.
