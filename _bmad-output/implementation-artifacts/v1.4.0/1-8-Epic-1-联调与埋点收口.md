---
title: "Story 1.8: Epic 1 联调与埋点收口"
epic: 1
story: 8
version: v1.4.0
created: 2026-08-17
flyway: 无
baseline_commit: 5e89e8a735ed6f23c0ac4052757226703ae86f66
---

# Story 1.8: Epic 1 联调与埋点收口

Status: review

> 🔴 **本 Story 存在的理由：前七条各自绿灯 ≠ Epic 1 是个可验收的交付。**
> 它测的是**各段之间的接缝**，那正是分段开发最容易漏的地方。

## Story

As a 团队, I want 验证「后台录入 → 上架 → App 可见」整条链路真实跑通, so that Epic 1 是完整交付而非三段各自绿灯。

## Acceptance Criteria

### AC1 — 全链路（L1 已达成 / L2 待人工）
后台建商品 → 配 SKU 与价格 → 登记采购入库 → 上架 → App 可见，详情数据与后台录入一致。
库存改 0 → App 显示售罄；下架 → App 列表与详情均查不到。
- [x] `[L1]` 真实 pg+redis，全程经真实 service（不用 JDBC 抄近路——抄近路就测不到接缝）
- [ ] ⏳ `[L2]` 模拟器走通全链路（需 GUI）

### AC2 — 埋点收口
四个事件均实现且字段完整；🔴 **埋点禁带 PII**（NFR-5）。
- [x] `[L0]` 四事件齐全性 + 属性名逐个核查
- [ ] ⏳ `[L2]` PostHog 后台实测收到事件

## Tasks / Subtasks

- [x] T1 `Epic1ChainIntegrationTest` 5 条 L1 全链路
- [x] T2 补 `toko_out_of_stock_shown` 事件（Epic 1 唯一缺的一个）
- [x] T3 `epic1_analytics_test` 5 条：四事件齐全 + PII 护栏
- [x] T4 两侧全量回归
- [ ] ⏳ L2 两条（模拟器 / PostHog）

## Dev Notes

### 🔴 埋点事件名与 epics 的对应关系（有意偏离，已在 1.6 说明）

| epics AC 原文 | 实际实现 | 原因 |
|---|---|---|
| `toko_tab_viewed` | `toko_tab_viewed` | 一致 |
| `product_impression` | **`toko_product_shown`** | 命名护栏要求「模块前缀 + 动作词尾」，原名两条都不合 |
| `product_detail_viewed` | **`toko_product_detail_viewed`** | 补模块前缀 |
| `out_of_stock_viewed` | **`toko_out_of_stock_shown`** | 补前缀 + 动作词尾 |

护栏来自 2026-08-04 用户明确要求（产品要能一眼看出是哪个页面的事件），且原文禁止「放宽规则」与「塞进 legacyEvents 蒙混」。

### 边界
❌ L2 视觉与 PostHog 实测需 GUI / 真实凭证，留人工

## Dev Agent Record

### Agent Model Used
claude-opus-5[1m]（Claude Code）

### Debug Log References
- 后端 `mvn -B test`：**1674 通过 / 0 失败 / 6 跳过**（+5 全链路）
- 前端 `flutter test`：**789 通过**（+5）；`flutter analyze` 零问题

### Completion Notes List

**全链路 5 条一次通过**，说明前七条 story 的接缝确实对齐了。链路里顺带验到两件不显眼但要紧的事：
1. 🔒 **进货价没有从任何一段泄到对外接口** —— 这是整条链路上最容易泄的一处（后台录入时它在同一个表单里）。断言直接查 `190000` 与 `costPrice` 字面量。
2. 🔴 **售罄不下架的口径在链路上成立** —— 盘点归零后详情返回 `OUT_OF_STOCK`，但商品**仍在列表里**（保留复购提醒与外部落点）。这条如果写反了，单看 Story 1.2 或 1.5 都发现不了。

**新增 `toko_out_of_stock_shown`** —— Epic 1 四个事件里唯一缺的。售罄曝光是转化漏斗上最值得看的流失点之一（**用户想买但没货 ≠ 用户不想买**），Epic 6 的补货提醒要靠它判断值不值得做。已做去重：同一 SKU 反复 rebuild 不重复打点。

**PII 护栏逐个点名禁用词而非模糊匹配** —— 「不含 user 字样」既会误伤（`user_type` 合法）也漏得掉（`phone`/`address` 都不含 user）。并额外断言当前属性集恰为 `{product_token, sku_token, zone}`：新增属性时这条会红，逼开发者确认它不是 PII。

**测试基建又踩两处**（累计第 3、4 处，已记入 1.7）：Riverpod 3 里 `Override` 不是可直接书写的类型名；`ProviderScope.overrides` 用 `List<Object>.cast()` 绕过。

### File List
**新增**：`petgo-backend/src/test/java/com/tailtopia/shop/Epic1ChainIntegrationTest.java` · `petgo_app/test/shop/epic1_analytics_test.dart`
**修改**：`petgo_app/lib/features/shop/presentation/product_detail_page.dart`（+售罄埋点）

## Change Log
| 日期 | 变更 |
|---|---|
| 2026-08-17 | 创建并实现 → `review`。全链路 L1 5 条通过；埋点四事件收口。2 条 L2（模拟器 / PostHog）待人工 |
