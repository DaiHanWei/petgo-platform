# 留存看板设计（PostHog · project 211847）

> 设计时间：2026-08-24 · 分支 `stag` · 版本 `1.1.6+11`
> 依据：`docs/analytics-posthog-tracking.md` + 从 `petgo_app/lib` 真提取的事件字面量，非设计稿
> 状态：**设计稿，待拍板后落地**

---

## 0. 先定地基：五条会让看板读错数的既有事实

这五条不是免责声明，是**看板结构的约束条件**。每一条都直接决定某个格子怎么配、或者能不能配。

| # | 事实 | 对看板的直接后果 |
|---|---|---|
| 1 | **stag 与 prod 同一个 project token**，只靠 App Version 后缀 `-stag` 区分 | **每一张图都必须挂全局筛选** `App Version` not contains `-stag`。漏一张，内测数据就混进留存曲线 |
| 2 | **person property 只有 `internal_user_id`** 一个（`analytics.dart:identifyUser`） | 做不了「按注册月分群看留存曲线」这类常规切法。所有分群只能用 **event-based cohort**（见 §3）。这是本设计最大的一处将就 |
| 3 | **后端只有 `milestone_achieved` 一个事件**（`MilestoneAnalyticsListener`） | 订单/支付在 PostHog 里**只有客户端版本**，杀进程/弱网会丢。复购留存的分母不可靠 → 主口径必须是后台 AB-13B 服务端看板，PostHog 这边只做交叉验证 |
| 4 | **2026-08-04 `discovery_*` → `social_*` 改名**，历史事件不追溯重写 | 跨该日期的图要么「旧名 OR 新名」并集，要么明确只看之后。本看板**统一只看 2026-08-04 之后**，省掉一堆并集条件 |
| 5 | **Toko 刚占下第 2 个 Tab 位**（DEP-1，最近两个 commit），电商仍在 stag | 区 D 上线首月**必然是空的或极稀疏**：无人有购买历史 → FR-109 恒不触发。看板上要写明，否则会被读成「复购机制无效」 |

另外两条口径注记：
- **`Application Opened` 在切回前台也报**（带 `from_background`），不只冷启动。做「打开留存」正好，做「冷启动次数」要过滤。
- **`$screen` 有两套命名并存**：手工的 `*_page`（权威）和 observer 自报的路由路径（`/profile`）。**看板一律用 `*_page`**。

---

## 1. 最重要的设计决策：「活跃」是什么

留存的分子不定清楚，整个看板就是装饰。这里**不选单一口径，三层并行**——因为三层各回答不同的问题，混成一个反而都答不了。

| 层 | 口径 | 事件条件 | 回答什么 | 谁看 |
|---|---|---|---|---|
| **L1 打开留存** | 打开过 App | `Application Opened` | 大盘健康度、对外汇报的那个数 | 全员 / 投资人 |
| **L2 有效留存** | 做了任一核心行为 | 见下方集合（OR） | **产品决策主指标**——真实价值留存 | 产品 |
| **L3 模块留存** | 进过某个 Tab | `$screen` = `diary_page` / `social_page` / `health_page` / `toko_page` / `me_page` | 哪个模块在留人、哪个只是被路过 | 产品 / 各模块负责人 |

**L2 核心行为集合**（任一即算活跃，用 PostHog 的 "any of these events"）：

```
content_publish_submitted     发内容
diary_timeline_item_tapped    翻成长日记
post_like_tapped              社区互动
triage_submitted              AI 自查
consult_request_submitted     发起问诊
toko_product_detail_viewed    看商品详情
toko_order_submitted          下单
milestone_achieved            里程碑达成（服务端，最可靠）
```

⚠️ **刻意不把 `bottom_nav_tab_switched` 和 `button_tapped` 放进 L2**：切 Tab 是导航不是价值，混进来会让 L2 逼近 L1、失去区分度。

---

## 2. 看板结构：六个区 · 21 个格子

### 区 A · 大盘留存与粘性（4 格）

| # | 图 | 类型 | 配法 |
|---|---|---|---|
| A1 | **打开留存 · 按周队列** | Retention | 事件 `Application Opened` → `Application Opened`，Weekly，8 周 |
| A2 | **有效留存 · 按周队列** | Retention | L2 事件集合 → 同集合，Weekly，8 周。**与 A1 并排放**，两条曲线的差就是「打开了但什么也没干」的规模 |
| A3 | **DAU / WAU / MAU + 粘性** | Trends | `Application Opened` 的 unique users，三条线 + 计算列 DAU/MAU |
| A4 | **新装 vs 回访构成** | Trends | `Application Installed` 与 `Application Opened` 叠加面积图，按周 |

### 区 B · 新用户激活 → 留存（5 格 · 决策价值最高的一区）

产品假设是「**建档是 Aha 时刻**」。这一区就是去证伪它。

| # | 图 | 类型 | 配法 |
|---|---|---|---|
| B1 | **激活漏斗** | Funnel | `signup_succeeded` → `pet_profile_create_submitted` → L2 任一核心行为，转化窗口 **7 天** |
| B2 | 🔴 **建档 vs 未建档的留存对比** | Retention ×2 | 两张 Retention 并排，分别按 cohort `已建档宠主` / `未建档已注册` 过滤。**这是全盘最该先看的一张**——如果两条曲线差不出来，「建档是 Aha 时刻」这个假设就该被推翻 |
| B3 | **注册来源 × D7 留存** | Retention (breakdown) | `signup_succeeded` → `Application Opened`，按 `entry_source` 拆。⚠️ **必须显式列出 `other` 档**：它包含游客点「＋」发布、点受控 Tab、401 强弹窗三种，占比不小，不列会出现一个可观的未标注桶 |
| B4 | **首周里程碑数 × 留存** | Retention (cohort) | 按首周 `milestone_achieved` 次数分桶（0 / 1-2 / 3+）建 cohort，看 D30。验证「新手任务是否真的拉留存」 |
| B5 | **落地 Tab × 次周回访** | Retention (breakdown) | `app_launch_landed_on_tab` → `Application Opened`，按 `tab` 拆（diary / social / vet_workbench）。⚠️ 带 `restore_timeout=true` 的那批要单独看——**兜底落错页的用户留存如何**，是 FR-91 值不值的唯一依据 |

### 区 C · 模块留存（4 格）

| # | 图 | 类型 | 配法 |
|---|---|---|---|
| C1 | **五个 Tab 的周留存矩阵** | Retention ×5（小图） | 每个 Tab 一张：`$screen`=`X_page` → 同事件，Weekly。⚠️ `publish_page` **不算 Tab 根页**，不进这个矩阵 |
| C2 | **Tab 使用宽度 × D30 留存** | Retention (cohort) | 按首周用过的不同 Tab 数分桶（1 / 2 / 3+）。测「多模块使用是否等于更黏」 |
| C3 | **Tab 切换热力** | Trends (breakdown) | `bottom_nav_tab_switched` 按 `from_tab`→`to_tab` 拆，看主要流转路径 |
| C4 | **Diary 私密化率**（留存的反向信号） | Trends | `publish_page_sync_to_moment_toggled` 中 `enabled=false` 占比。V1.1.2 最关键的产品假设，且长期只能看绝对值 |

### 区 D · 电商复购留存（4 格 · **上线首月必然稀疏**）

| # | 图 | 类型 | 配法 |
|---|---|---|---|
| D1 | **首购 → 复购留存** | Retention | `toko_order_payment_succeeded`（**first time** 口径）→ 同事件，Monthly，30/60/90 天 |
| D2 | **复购卡漏斗** | Funnel | `toko_repurchase_card_shown` → `_tapped` → `toko_order_payment_succeeded`，按 `trigger_type` 拆 |
| D3 | **购买者 vs 非购买者的 App 留存** | Retention ×2 | cohort `Toko 购买者` vs 全体。测「电商是否在拉整体留存」——这是电商对留存的真实贡献，不是 GMV |
| D4 | **加购 → 支付主漏斗** | Funnel | `toko_add_to_cart_tapped` → `toko_cart_page_viewed` → `toko_checkout_page_viewed` → `toko_order_submitted` → `toko_order_payment_succeeded` |

🚩 **区 D 必须挂两条 Text 注记，缺一就会被读错：**
1. 本版本 `trigger_type` **只会产生 `FOOD_LOW`**；`DEWORM` / `VACCINE` 恒为 0 是**范围决策**（FR-108 挪 1.2.0），**不是数据丢失**。
2. 复购口径**主源是后台 AB-13B 服务端看板**（读订单行 `entry_source` / `trigger_type`）。此处是客户端交叉验证，两边数字不一致时**以后台为准**。

### 区 E · 流失预警 · 反向指标（3 格）

| # | 图 | 类型 | 配法 |
|---|---|---|---|
| E1 | **推送权限撤销趋势** | Trends | `notify_push_permission_toggled`（enabled=false），按周 + 按 `from_screen` 拆。**推送疲劳的终点信号**——没有它只能看到打开率跌，说不清有多少人彻底退出 |
| E2 | **复购卡关闭率** | Trends (formula) | `toko_repurchase_card_dismiss_tapped` / `toko_repurchase_card_shown`。持续上升 = 推送疲劳，该收缩触发频率 |
| E3 | **版本更新后的次日回访** | Retention (breakdown) | `Application Updated` → `Application Opened`，按 App Version 拆。**发版是否伤留存**的唯一读数 |

### 区 F · 口径与已知坑（1 格 Text）

把 §0 的五条事实 + 区 D 的两条注记原文贴上看板。

**这一格不是文档冗余，是看板的一部分**：`analytics-posthog-tracking.md` 里已有三次「照着过期口径配图、结果恒为 0 条」的记录（`HEALTH_EVENT` vs `HEALTH_RECORD`、`discovery_*` 改名、`A_with_profile` 枚举名）。口径不写在图旁边，就一定会有人读错。

---

## 3. 需要先建的 Cohort（7 个）

因为 person property 只有一个（§0 事实 2），**所有分群都是 event-based**。建 insight 之前先建这些：

| Cohort | 定义 |
|---|---|
| `已建档宠主` | performed `pet_profile_create_submitted` ≥ 1 |
| `未建档已注册` | performed `signup_succeeded` ≥ 1 **AND NOT** performed `pet_profile_create_submitted` |
| `Toko 购买者` | performed `toko_order_payment_succeeded` ≥ 1 |
| `Toko 复购者` | performed `toko_order_payment_succeeded` ≥ 2 |
| `问诊付费用户` | performed `consult_pay_succeeded` ≥ 1 |
| `沉默用户` | performed `Application Opened` in last 30d **AND NOT** in last 7d |
| `注册来源 × 4` | `signup_succeeded` where `entry_source` = `diary_cta` / `social_soft_login` / `login_page` / `other` |

---

## 4. 埋点缺口：哪些格子会空着，补哪个最划算

### 4.1 ✅ 已补（2026-08-24）

**person property 从 1 个变成 3 个**，改动 4 个文件、新增 1 个 L0 测试文件（8 例），全量 1228 前端测试绿、`flutter analyze` 无新增告警。

| 属性 | 语义 | 买到什么 |
|---|---|---|
| `role` | `$set`，随 identify | **把兽医号剔出宠主留存大盘**——此前兽医与宠主混在一条曲线里，行为模式完全不同 |
| `signup_date` | `$set_once`，注册成功那一刻 | **按注册月分群看留存曲线**——此前完全做不到 |
| `first_entry_source` | `$set_once`，同上 | B3 注册来源固化，不必每张图去 join `signup_succeeded` |

实现要点（改动集中在 `petgo_app/lib/core/analytics/analytics.dart`）：
- `Analytics.personProperties()` —— 纯函数、L0 可测，走 `scrub`。
- `Analytics.captureSignupSucceeded()` —— T-7 的**唯一**上报入口，两条注册路径共用，`$set_once` 与事件**原子同发**。挂在事件上而非单独调 `setPersonProperties`，是为了①不会与事件漂开 ②不依赖 identify 的先后 ③能被既有 `debugCaptureSink` 在 L0 断言到。
- ⚠️ 内部那句**必须写成 `Analytics.capture(` 自限定形式**：埋点守卫是从 `lib/` 正则提取该字面量对账的，简化成裸 `capture(` 会让 `signup_succeeded` 从提取集里整个消失。代码旁已注明。

### 4.2 🔴 一处必须写进看板的口径修正

**person property 不能替 B2 做事。** 它是**覆盖式**的、回溯查询取**最新值**：一个用户第 60 天才建档，`has_pet_profile=true` 会让他在**第 7 天的留存**里也被算进「已建档组」，归因是反的。

所以：
- **B2 / B4 / C2 仍然必须用 event-based cohort**，person property 帮不上忙；
- `user_state` / `has_pet_profile` **刻意不做成 person property**（有测试守着这条设计意图）：它们可变，而 `identifyUser` 只在**换人**时触发，放进去会永远停在首次登录那一刻的值 —— 脏数据比没数据更糟。`user_state` 本来就已是 T-1 / T-2 的**事件**属性，按事件取数天然是当时的真值。

### 4.3 仍然空着的

| # | 缺口 | 伤害 | 补法与成本 |
|---|---|---|---|
| 1 | 🔴 **服务端只有里程碑一个事件** | D1/D2/D3/D4 的支付节点用的是客户端事件，杀进程/弱网必丢 → **复购留存分母不可靠** | `PostHogAnalyticsClient` 基建已在，照 `MilestoneAnalyticsListener` 加个订单支付成功监听，十几行 |
| 2 | 🔴 **存量用户没有 `signup_date`** | 按注册月分群时**分母只含本次上线之后注册的人**，不是全体用户 | 后端 `UserProfileResponse` 下发 `createdAt`，登录时补 `$set_once`。需要一次接口改动 |
| 3 | 🟡 **健康记录 / PawCoin / 客服 / KTP 零埋点** | 这几个模块对留存的贡献在 L2/L3 里完全看不见。**健康记录尤其可疑——它可能才是真正的留存引擎** | 各加 1-2 个 `*_succeeded`，按 §8.1 命名约定 |
| 4 | 🟡 **兽医端 `/vet/*` 零埋点** | 兽医侧留存完全不可见。**兽医流失比宠主流失更致命** | 需单独一轮，不在本看板范围 |
| 5 | 🟡 **stag/prod 同 project** | 靠 `-stag` 后缀过滤，一旦有人绕过 `build-stag-apk.sh` 就污染，**且事后无法区分** | 分一个 stag project + 打包脚本注入 token。一次性 |

## 5. 落地状态（2026-08-24 已交付）

**PostHog dashboard `911815` · Product Activation & Module Retention (Production Only)**
https://eu.posthog.com/project/211847/dashboard/911815

9 张图 + 1 个口径 text tile，**逐张验证过真出数**。另建 2 个 event-based cohort：
`219108 已建档宠主` / `219109 注册未建档`。

### 与既有看板的分工

建之前发现项目里**已有成熟的留存看板**，范围因此重划：

| 看板 | 归属 | 回答什么 |
|---|---|---|
| `901790` Release & Retention | Raffy（8/18，已审计） | **发版是否改变了回访**（release 视角）。**大盘留存口径以它为准** |
| `911728` [DRAFT-AUDIT] | Raffy（8/24 在改） | 上者的校验副本 + 数据限制注记 |
| **`911815`（本次）** | — | **什么行为让人留下来**（产品视角）：激活→留存、模块留存 |

production 筛选**逐字复用** `911728` 修正版，不另写一份：
`$app_version NOT ILIKE '%stag%'` · `$is_emulator`/`$is_testflight`/`$is_sideloaded` ≠ `true` ·
`NOT IN cohort 202794` · `filterTestAccounts=true`。

### 未建的格子（刻意，非遗漏）

- **区 A 大盘留存** —— `901790` 已覆盖。重建会造成口径分裂，比没有更糟。
- **区 D 电商 + 区 E 推送疲劳** —— 事件已埋，但实测是内部测试量级：
  `toko_order_payment_succeeded` **1 人**、`toko_add_to_cart_tapped` **1 人**、
  `notify_push_permission_toggled` 全时段 **0 条**。建了只会被读成「电商效果差」，
  实际是还没发给用户。v1.4.0 放量后再补。

### 🔴 建看板过程中查出的两件事

**1. 服务端埋点在生产从未上报过一条。**
`$lib` 全时段只有 `posthog-flutter`（145,697 条）；`milestone_achieved` 全时段 **0 条**。
V1.1.2 Story 6.1 那套后端上报（`PostHogAnalyticsClient` + `MilestoneAnalyticsListener`，
配了专用线程池、超时、跨语言 distinctId 断言、9 条单测）**建成了但没开**——
`application.yml:250` 的 `POSTHOG_SERVER_KEY` 生产 env 为空，空值即静默关闭、不出网、不报错。
诊断一行：`grep POSTHOG ~/.env.petgo ; docker logs petgo-server 2>&1 | grep "server-side analytics"`，
看那句 `enabled=false`。（生产操作，本文档不执行。）

**2. `entry_source` 里 `other` 占 59%（294/496），且 `social_soft_login` 恒为 0。**
软登录浮层至今**零转化**（13 次曝光 / 5 人 / 1 人点击 / 0 注册）。
`other` 含游客点「＋」发布、点受控 Tab、401 强弹窗三种——PRD 早就警告过它不是边角，实测坐实。

### 🔴 首次读数：核心产品假设未获支持

B2a / B2b（注册后日留存，剔除 <10 人的小队列）：

| | N | D1 | D2 | D3 | D4 | D5 | D6 | D7 |
|---|---|---|---|---|---|---|---|---|
| **已建档者** | 365 | 14.2% | 9.3% | 3.0% | 3.0% | 3.3% | 2.2% | 0.5% |
| **注册未建档者** | 60 | 10.0% | 6.7% | 3.3% | 0.0% | 1.7% | 1.7% | 0.0% |
| 倍数 | | 1.4x | 1.4x | **0.9x** | — | 2.0x | 1.3x | — |

**「建档是 Aha 时刻」没有得到数据支持。** D1 差 4.2pp、**到 D3 就收敛甚至反超**；
对照组 n=60，D1 的标准误约 3.9pp，14.2% 与 10.0% 的差距不到 1.1 个标准误 —— **不显著**。

三条必须一起读的限制：
1. **选择偏差**：会建档的人本来就更投入，相关也不等于因果——而这里连相关都很弱；
2. **对照组天然小**：B1 显示 80.7% 的注册者会走完建档，剩下的 20% 是自选择样本；
3. **只有 18 天数据**（`signup_succeeded` 自 8/6 才上报）。

**这不是「建档没用」的结论，是「现有数据不足以支持把建档当作留存杠杆」。**
真要裁决，需要的是干预实验（比如对一部分用户弱化建档引导），不是继续观察。
更值得注意的是**两组的绝对值都很低**：D7 分别是 0.5% 与 0%，与 `901790` 的口径一致。

