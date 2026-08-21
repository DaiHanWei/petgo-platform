# Story 6.4: Toko 首页区域①② 接入（App）

Status: **review**（L0 全绿；**L2 视觉验收待本地人工**）

## 交付物
`shop_repurchase.dart`（域）· `shop_repurchase_repository.dart` ·
`widgets/repurchase_zones.dart`（`RepurchaseZone` + `ProfileRecoZone`）· 接入 `toko_page.dart` ·
ARB 双语 12 条 · `test/shop/repurchase_zones_test.dart`（14 例）·
**UX-DR1 原型改版**：`01-Toko首页-有复购触发.html` 删掉驱虫卡。

## AC 与验证
| AC | 层级 | 验证 |
|---|---|---|
| 区域① 展示 FR-109 触发卡，最多 2 张；**无触发时整区不渲染** | L0 | `已登录·已建档且无触发` 用例断言**连标题都没有** |
| 区域② `Pilihan buat {宠物名}`，展示 4–6 个 | L0 | `每张推荐卡都带推荐理由` |
| 🔴 **UX-DR1：驱虫卡必须删掉** | — | 原型已改（含把「FR-108 健康记录触发」说明改成「已移出 V1.4.0」） |
| FR-93 状态矩阵四种状态 | L0 | 四条用例逐格覆盖 |
| 🔴 补货卡文案给**估算依据而非断言** | L0 | `补货卡文案是【估算】不是断言`（断言出现 `~` / `diperkirakan`） |
| 埋点：曝光 / 点击 / 关闭，且**禁带 PII** | L0 | `v112_events_test` 命名护栏 + `epic1_analytics_test` PII 白名单 |

## 🔴 三处刻意的设计选择
1. **游客态在数据层短路**（Epic 3 硬结论 1）：provider 见到未登录直接返回 `missing=GUEST`，
   **一个 `/me` 请求都不发**。靠前端不渲染是守不住的 —— 被 watch 一次就会 401 → 强登录引导 =
   变相登录墙，而 FR-93A 要求浏览路径零门槛。用例直接断言 `repo.calls` 为空。
2. **`GUEST` 与 `PROFILE` 是两个值。** 游客整区不渲染；已登录未建档才换成建档引导卡
   （矩阵第 1 行 vs 第 2 行）。混成一个值会让游客看到一张点下去就是登录墙的卡。
3. **「不展示」= 组件自己返回 `SizedBox.shrink()`**，判定放在组件里而不是首页。
   首页因此不会因为多两区就多两层状态分支。

## ⚠️ 与 AC 的两处偏离（都有理由）
1. **埋点名与 AC 原文不同**：AC 写 `repurchase_card_impression` / `_clicked` / `_dismissed`，
   实际用 `toko_repurchase_card_shown` / `_tapped` / `_dismiss_tapped`。
   本仓库的埋点命名护栏要求「模块前缀 + 受控动作词尾」，HANDOFF 硬纪律 4 明令
   **不放宽规则、不塞 legacyEvents**。语义完全一致。
2. **不上报 `pet_id`**：单账号单宠物（L-11）下它与 `distinct_id` 一一对应，
   多带一个标识只是多一条能把行为拼回个人的线索，换不来任何分析能力（NFR-5）。

## 🎨 UX-DR8
矩阵后两种状态（已登录未建档 / 状态 B/C）无视觉稿。本实现**按矩阵语义落地、未自创新版式**：
「不展示」= 真的不渲染；建档卡复用既有 FR-0G 文案（`profileOnboardingBody` / `-Create`）。
补稿后若版式有变，只需改这一个组件。
