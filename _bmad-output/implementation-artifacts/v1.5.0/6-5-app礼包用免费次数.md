# Story 6.5: App 礼包用免费次数（E1 ~ E5）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-13：用免费次数不二次确认；E4 / E5 去掉送礼方 EXP 提示）> PRD B §3.5 表、§7 > 架构 D-17、D-19、§4.2 > App 稿 E 泳道（页头「研发执行说明」第 7 步）。
> 前置：4-3、6-1、6-3、2-2。
> 范围层：**只 App**。

## Story

As a 有免费次数的用户,
I want 在 KTP / 性格测试抽屉里一键用次数解锁、在送礼包装页直接用次数送 Rp1.000 礼物,
so that 不用付款就能用上礼包。

## Acceptance Criteria

### AC1 — KTP 高清抽屉用次数 E1 / E2 `[L0]` `[L2]`
1. `hd_paywall_sheet.dart`：该卡对应款（`cardType` + `cardVariant` → 模板，映射与后端 `CreditType.ktpHdOf` 一致：学生卡无配色 = KLASIK）或 ANY 有剩余时，通过 6-1 的 `creditOverride` 插槽整块替换：confirmation_number 图标 +「{n} jatah gratis untuk desain {tpl}」+「Berlaku s/d {date}」（最早到期）+ 主按钮「Buka HD gratis」；两张付款卡和推荐行都不显示。
2. 点「Buka HD gratis」**直接**调 `POST …/id-cards/{cardId}/hd-credit-unlock`，不二次确认；成功：关抽屉、卡片去水印、按钮变「Unduh HD」（同线上解锁成功落点），`showAppToast("HD terbuka · 1 jatah gratis terpakai", extraBottom: 48)`；`invalidate(entitlementSummaryProvider)`。
3. 409 `entitlement-insufficient`（并发用完）→ 刷新次数，抽屉恢复原价付款形态 + 通用失败 toast。
4. 这款用完后抽屉恢复原价（按刷新后的 summary）。

### AC2 — 性格测试抽屉 E3 `[L0]`
同 AC1 的交互：免费次数框「{n} jatah gratis Tailsonality」（UI 稿 E3 原文；EN 建议「{n} free Tailsonality credit(s)」）+「Berlaku s/d {date}」+ 主按钮「Buka gratis」→ `POST …/tailsonality/results/{token}/credit-unlock`；成功后结果页解锁（同线上付费解锁成功落点）。成功 toast：UI 稿只写「toast 同 E2 句式」、PRD §7 只有 KTP 版，故新增 key「Hasil terbuka · 1 jatah gratis terpakai」/「Result unlocked · 1 free credit used」。这两条 EN 与新 toast 在 Completion Notes 标「待产品复核」（待拍板 Q-9）。配型抽屉不接次数。

### AC3 — 送礼包装页免费次数 E4 / E5 `[L0]` `[L2]`
在 2-2 的包装页支付区：
1. 礼物单价 = 1000 且 `GIFT_1K` 剩余 > 0 → 支付区多一个选项且**默认选中**：标题「Pakai jatah gratis」+ 右侧「Sisa {n} jatah」；主按钮「Kirim · Gratis」；礼物名下方 EXP 提示行**整行不显示**（也不提示送礼方不得 EXP）。展开「Pilih metode bayar」仍可改选 PawCoin / 直接付款（改选后恢复 EXP 提示）。
2. 数量上限 = min(99, 剩余次数)：到上限「＋」置灰、×10 等超限胶囊置灰，下方提示「Jatah gratis tinggal {n} · tambah lagi pakai PawCoin atau bayar langsung」（E5）。
3. 送出：`channel = PACK_CREDIT`；成功同样进 2-3 送出动画，送达页 EXP 行不显示（`senderExp = 0`）；`invalidate(entitlementSummaryProvider)`。
4. 单价 ≠ 1000 的礼物不出现此选项。409 `entitlement-insufficient` → 刷新次数并回到 PawCoin 默认 + 失败 toast。

### AC4 — 我的送礼记录 `[L0]`
`gift_orders_page.dart` 的 `_OrderRow`：`channel = PACK_CREDIT` 显示「Pakai jatah gratis」、金额「Gratis」（待拍板 Q-3；复用 PRD B §7「用免费次数（送礼）」key 与 E4「Kirim · Gratis」里的「Gratis」——若需单独 key 则新增「Gratis」/「Free」）。礼物详情（2-6）不显示「免费」。

### AC5 — 文案 `[L0]`
PRD B §7 逐字：{n} jatah gratis untuk desain {tpl}、Buka HD gratis / Buka gratis、HD terbuka · 1 jatah gratis terpakai、Pakai jatah gratis、Sisa {n} jatah、Kirim · Gratis、Jatah gratis tinggal {n} · tambah lagi pakai PawCoin atau bayar langsung。AC2 新增的两条标「EN 待产品复核」。

### AC6 — 测试 `[L0]`
Widget：KTP 抽屉有该款 / 只有 ANY / 无次数三态；性格测试抽屉两态；包装页 1000 礼物默认选免费、5000 礼物无此选项、数量到上限置灰与提示、改选 PawCoin 恢复 EXP 行；送礼记录 PACK_CREDIT 行。

## Tasks / Subtasks

- [ ] KTP 抽屉（AC1）
- [ ] 性格测试抽屉（AC2）
- [ ] 送礼包装页（AC3）
- [ ] 送礼记录（AC4）
- [ ] ARB + 测试（AC5、AC6）

## Dev Notes

- 模板映射写一个 App 侧纯函数 `ktpTemplateOf(cardType, cardVariant)`，单测覆盖 9 种组合（与后端 `CreditTypeTest` 同表）。
- 埋点 `entitlement_used` 在服务端报（4-3），App 不报。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/profile/ test/features/tailsonality/ test/features/gift/`
- L2（本地）：E1 ~ E5 还原度 ≥ 95%；真后端用次数后合并页数字减少

## Dev Agent Record

### Completion Notes

### File List
