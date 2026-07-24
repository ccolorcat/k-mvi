# Report Spec

## Findings rules

- Categories are `Bug`, `Design`, `Test`, `Doc`, `Name`, and `Style`; put positive observations only under `Positive Findings`.
- Category review scope; map cross-domain issues to the closest category by primary impact or root cause:
  - `Bug`: correctness, boundary conditions, null/empty states, exceptions, concurrency, resource cleanup, security, compatibility, and error handling. Route security, runtime, and compatibility issues here regardless of the tier that surfaced them.
  - `Design`: architecture, responsibility split, module/dependency boundaries, and over-design, plus dependencies, CI, release, and configuration.
  - `Test`: missing coverage, weak assertions, untested edge cases, fragile tests, and insufficient verification.
  - `Doc`: comments, API docs, and usage examples, plus completeness of the public/API surface and consistency with the implementation.
  - `Name`: naming and its consistency across modules, files, types, functions, variables, parameters, constants, and public concepts.
  - `Style`: language/framework idioms, readability, local complexity and duplication, visibility, and stylistic/formatting consistency.
- Severity order is `🔴 High`, `🟠 Medium-High`, `🟡 Medium`, `🔵 Low`, `⚪ Info`, `🟢 Positive`; use `[High]`, `[Medium-High]`, `[Medium]`, `[Low]`, `[Info]`, or `[Positive]` respectively.
- Every problem, including `[Info]`, must state the problem, impact, recommendation, and evidence. Merge symptoms with the same root cause; omit speculation, preference-only rewrites, and unsupported findings.
- Include the severity icon, category-local number, severity, and file path with line number when available. Sort by category, severity, and impact. Summary counts must match the findings; omit empty headings and placeholders.

## Report format

Translate headings and labels to the conversation language. Use this shape, compressing obvious fields only for short chat reports:

```markdown
# 审查报告
## 摘要
- 审查范围：
- 输出形式：
- 审查方法：
- 已运行检查：
- 未运行检查及原因：
- 范围限制：
- 总体结论：
- 审查建议：
- 严重性计数：🔴 N / 🟠 N / 🟡 N / 🔵 N / ⚪ N
## Findings
### {Category}
- 🟡 {Category} 1. [Medium] `path/to/file.ext:42`
  - 问题：
  - 影响：
  - 建议：
  - 依据：
## Positive Findings
- 🟢 Positive. [Positive]
  - 观察：
  - 价值：
  - 位置（可选）：
```

Replace `{Category}` with an allowed category. List cross-file paths with commas and make recommendations concrete. Add a localized next-steps heading only when useful.

## Markdown output and reliability

- An explicit user-selected output form wins. Otherwise, use chat below 4K characters and Markdown file output at or above 4K.
- If the user provides a target file, use it; if the target is a directory, write `review_{scope}_{YYYYMMDD}.md` there. Without a target, use `docs/review_{scope}_{YYYYMMDD}.md` when `docs/` exists, otherwise `review_{scope}_{YYYYMMDD}.md`. Use a short filename-safe scope and append `_01`, `_02`, and so on if needed.
- For Markdown reports at or above 4K characters, write incrementally in chunks of about 4K characters, splitting only at line boundaries. Start with `Status: IN_PROGRESS`, title, scope, output form, and generation date; append stable sections and finish with `Status: COMPLETE`, final counts, and completion date.
- Treat the latest status marker as authoritative. Resume an interrupted file whose latest marker is not `Status: COMPLETE` by reading it and appending only the remaining sections.
- After writing a Markdown report, return only its path, completion status, severity counts, and key conclusion in chat.

## Final checklist

- Scope, output form, and language match the request.
- Problem findings include problem, impact, recommendation, and evidence; positive findings include observation and value.
- Category numbering, grouping, sorting, and severity counts match the body; no empty headings or placeholders remain.
- Review the draft once before output and revise it as needed.
- Sensitive paths and values were protected, no project files were modified, conclusions match the actual scope, and all paths/line numbers are accurate.
