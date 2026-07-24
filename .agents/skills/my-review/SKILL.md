---
name: my-review
description: Use this skill when the user explicitly invokes it, or requests an evidence-based review of a specified scope with a chat or Markdown report.
---

# My Review

## Overview

Conduct read-only, evidence-based reviews that prioritize actionable findings and concise reports. Use the workflow below to select the scope and review depth; load `references/report-spec.md` when forming the report.

## Workflow

### Scope and output

1. Confirm the review scope. Use the user's scope when provided; otherwise ask whether to review workspace changes, history, a diff, path, directory, or PR. If the scope cannot be located, ask for an executable identifier.
2. Confirm the output form. Honor an explicit user choice; otherwise follow the default selection in `references/report-spec.md`. Use the conversation language.
3. Do not run lint, type checks, builds, CI, local scripts, or broad analysis by default. Run them only when explicitly requested or when evidence of a compile/build error requires verification; explain and wait before any noticeably slow, stateful, networked, or externally visible operation.

### Review execution

1. Establish context with path-only or summary metadata where possible, then identify the project type, relevant tests, and reviewable files.
2. Apply the review tiers:
   - Tier 1: review first-party source, source-tied documentation, and related tests by default.
   - Tier 2: review resources and configuration only when source references or behavior dependencies require them.
   - Tier 3: review build tools, CI, dependencies, release configuration, lockfiles, and similar surfaces only for compile/build errors or explicit requests. For lockfiles, check versions, sources, checksums, and declaration consistency without reviewing every line.
3. For every tier, expand only relevant non-sensitive files. Exclude vendored, third-party, dependency/cache, generated, minified, and build-output content. Treat framework and platform internals as opaque; do not fetch, unpack, decompile, or otherwise expand them unless explicitly requested. Report unusually large binary or archive changes without reviewing them line by line.
4. Report Git conflict markers as Bug findings; downgrade markers in documentation examples or other non-executable content to `[Info]`.
5. For empty scopes, report that there is no reviewable content. For oversized scopes, prioritize Tier 1, sample only source-linked Tier 2 files, and recommend splitting follow-up work when no source-centered high-risk theme is visible.
6. Review in-scope behavior for interfaces, boundaries, errors, cleanup, concurrency, security, compatibility, documentation consistency, and test adequacy. Trace only the minimal cross-file data flow and error propagation needed to support a finding.
7. Read `references/report-spec.md` before categorizing or writing findings. Include evidence for every problem, use `[Info]` only for unconfirmed but verifiable risks, apply the final checklist, and ensure conclusions match the reviewed scope.
8. Output the reviewed report according to `references/report-spec.md`. In chat mode, return the report; in Markdown-file mode, write the file and return only its concise status summary.

## Safety

- Do not modify, delete, format, auto-fix, clean, or reset project files except to create or update a user-requested review report.
- Never record or repeat keys, tokens, certificates, private keys, cookies, or production connection strings.
- Do not open `.env*`, certificate/key files, or `secrets/`; report only the path and redacted risk type.
- For clearly source, test, documentation, or template files whose names contain `credential`, `secret`, or `token`, review structure and logic but redact any real sensitive values.

## Resources

- `references/report-spec.md`: findings categories, severities, report format, Markdown output, reliability rules, and the final checklist. Read it before preparing the report.
