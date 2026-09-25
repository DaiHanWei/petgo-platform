# specs/ —— 独立规格与设计文档

不属于某个版本 story 的功能规格、修复方案、接入方案，统一放这里（原散在 `implementation-artifacts/` 根、`docs/`、`docs/superpowers/` 三处，2026-09-09 合并）。

命名：`spec-<主题>.md`；按日期归档的设计稿保留原 `YYYY-MM-DD-<主题>-design.md` 名。

主要分组：
- **兽医端**：`spec-vet-*`（工作台 / 队列 / 会话 / 历史 / 主题）
- **i18n 对齐**：`spec-*-i18n-align.md`
- **第三方接入**：`spec-push-timpush-integration.md`、`spec-posthog-analytics-integration.md`、`spec-im-sdk-integration-handoff.md`、`spec-5-5-im-live-realchat-mau.md`、`spec-2-1-oss-single-bucket-l2.md`、`spec-lark-scheduled-posts.md`
- **Bug 系统工具**：`spec-bug-system-*.md`、`2026-07-01-bug-system-concurrency-triage-*.md`
- **问诊与支付修复**：`spec-consult-billing-flow-gap.md`、`2026-07-20-consult-qris-fix-design.md`、`2026-07-21-consult-timeout-redesign-311.md`、`spec-topup-60min-window-reuse.md`
- **身份证**：`spec-ktp-*.md`
