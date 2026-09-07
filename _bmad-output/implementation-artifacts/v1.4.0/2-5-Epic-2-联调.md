---
title: "Story 2.5: Epic 2 联调"
epic: 2
story: 5
version: v1.4.0
created: 2026-08-17
flyway: 无
baseline_commit: 7b9a2ec7d8b77af8ca418052c9bf7e3a908910fd
---

# Story 2.5: Epic 2 联调

Status: review

## Acceptance Criteria
- [x] `[L1]` 后台配范围与运费 → 存地址 → 试算出正确运费
- [x] `[L1]` 地址改到范围外 → 试算返回「暂不配送至该区域」（**保存仍成功**）
- [x] `[L1]` 达免运门槛 → 抵扣负数行正确出现
- [x] `[L0/L1]` 🔒 日志不含收件人姓名 / 手机号 / 详细地址任何明文
- [ ] ⏳ `[L2]` 模拟器走通（需 GUI）

## Dev Notes

### 🔒 哪些字段是 PII、哪些不是 —— 这条边界值得写死
**打码**：收件人姓名 · 履约手机号 · 详细地址。
**保留可见**：`kecamatan` · `provinsi` · `kodePos` 之外的行政区划。
🔴 理由：Kecamatan 是**运费与服务范围的判定粒度**，把它也打码会让「为什么这单算出这个运费」
变成无法回溯的问题 —— 而运费争议是电商客服最高频的工单之一。
已有断言同时验「三项被打码」与「kecamatan/provinsi 仍可见」。

### 🔗 这条链路是 Epic 3 结算页的直接上游
`quote(kecamatan, subtotal)` 就是结算页要调的东西。Epic 3 不必再造一遍运费逻辑。

## Dev Agent Record
### Agent Model Used
claude-opus-5[1m]（Claude Code）
### Debug Log References
本 Story 6 条 L1 一次通过；后端全量 **1704 通过 / 0 失败**
### File List
**新增**：`Epic2ChainIntegrationTest.java`

## Change Log
| 2026-08-17 | 创建并实现 → `review`。Epic 2 五条全部到 review |
