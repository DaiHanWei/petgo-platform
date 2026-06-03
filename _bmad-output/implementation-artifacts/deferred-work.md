# Deferred Work

延后处理项（评审中发现、非当前 story 阻断）。

## 2026-06-03 · spec-ui-mockup-alignment 评审遗留
- **问诊严重度胶囊配色一致性**：`triage_page.dart` `_SeverityChip` 的 GREEN/YELLOW 用实底+白字（沿用 `_FreeBadge` 既有模式），而 `UX_DESIGN.md:44-47` 为 green/yellow 定义的是浅底+深字对（`triage-green-bg`/`triage-green-text`）。RED 实底白字与稿一致。非阻断（在 spec Design Notes「三色映射」授权范围内）；YELLOW(#E0A458)+白字对比度可能 <AA 4.5:1，后续可改浅底深字并补 token。
