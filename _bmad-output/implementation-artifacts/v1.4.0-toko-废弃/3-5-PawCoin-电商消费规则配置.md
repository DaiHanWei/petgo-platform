---
title: "Story 3.5: PawCoin 电商消费规则配置（AB-6D / AB-6A 扩展 / AB-6C）"
epic: 3
story: 5
version: v1.4.0
created: 2026-08-17
flyway: V112
baseline_commit: 3f3351716de727253adc686028c39fcba11d6225
---

# Story 3.5: PawCoin 电商消费规则配置

Status: review

## Acceptance Criteria
- [x] AB-6D 可配置：电商 PawCoin 总开关 · 是否允许抵扣运费 · **单笔上限**（默认 Rp 1.000.000）
- [x] 🔴 上限说明写明用途是「**爆炸半径 + 监管姿态**，不是控浮存 —— 定低反而有害」（L-7 自纠）
- [x] 🔴 **两条溢价是两个独立配置项**，不共用数值（C-9 / D-8）
- [x] 补偿溢价含**比例 + 单笔上限**两个参数
- [x] 🔴 AB-6C 口径：「电商消费**加快核销 → 浮存下降**」，**不得**写成「充值推高浮存」
- [x] 🔴 S-5：总开关关闭**只影响新下单，不影响已付款订单**
- [x] 运费开关关闭 → 运费不参与抵扣
- [ ] ⏳ 后台配置页面（Thymeleaf）—— **服务层与审计已完成**，页面留待与 AB-6 模块其他页一并做
- [ ] ⏳ S-4 骗退风控（同用户 90 日内最多 2 次补偿溢价）—— **属 Epic 5 退款执行**，本 Story 只提供配置

## Dev Notes

### 🔴🔴 两条溢价共用一个数值 = 静默错误
「激励溢价」（既有，反套利）与「平台责任补偿溢价」（本版本新增，C-9）**必须独立**。
写成单值会**连带毁掉 AB-13A 的售后成本口径与 AB-6C 的浮存归因** ——
**不报错，只是两个报表的数字一直不对，且没人知道该信哪个**。已加断言：改补偿溢价后激励溢价一个数不动。

### 🔴 S-5：关总开关不得改写既有订单
已付款订单的退款按**下单时固化的比例**执行（那三段金额存在订单上，与开关无关）。
这样就不构成 FR-100A 规则 5 所说的「对已付款用户违约」，**两处定性冲突解除**。
已有断言：关开关后旧单的 `coinAmount` 仍为正。

### 🔴 AB-6C 口径方向写反的后果
「电商消费加快存量余额核销 → 预期浮存**下降**」。
写成「为买粮而充值会推高浮存」是**方向相反**的 —— 用户花掉 Coin = 平台交付价值、负债核销。
方向写反会让运营在浮存告警时做出**恰好相反**的处置。已把口径文案做成常量并加断言。

## Dev Agent Record
### Agent Model Used
claude-opus-5[1m]（Claude Code）
### Debug Log References
本 Story 7 条 L1 通过；后端全量 **1760 通过 / 0 失败**
### Completion Notes List
测试造数时把 `pawcoin_wallets` 表结构写错（该表无 `created_at`），已改为**走真实的
`PawCoinWalletService.credit`** —— 直接 INSERT 还会绕过双分录不变量，本就不该那样造数。
### File List
**新增**：`V112__add_compensation_premium_config.sql` · `order/service/AdminShopPawcoinRulesService` · `ShopPawcoinRulesIntegrationTest`
**修改**：⚠️`config/domain/PawCoinConfig.java`（**共享表加列**，只加不改、全有默认值）· ⚠️`AuditActions.java`（+1）

## Change Log
| 2026-08-17 | 创建并实现 → `review`。后台页面与 S-4 风控如实留未勾 |
