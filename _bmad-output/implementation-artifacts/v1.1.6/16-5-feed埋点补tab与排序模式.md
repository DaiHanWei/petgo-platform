---
baseline_commit: be9cade7
---

# Story 16.5: Feed 埋点补 `feed_tab` 与 `rank_mode`

Status: review

## Story

As a 数据，
I want Feed 埋点能区分「哪个 Tab」和「哪条排序路径」，
so that 推荐序的效果能被归因，而不是和分类 Tab 的数据混在一起。

---

> 🔴 **不加这两个属性，FR-95 的效果无法归因** —— 而这个 FR 的参数本来就要在发版后校准，
> 归因不了等于校准也做不了。

---

## Acceptance Criteria

### AC1 — 两个属性（L1）

**Then** Feed 相关埋点须带 `[L1]`：
- `feed_tab`：`all` / `moment` / `tips` / …
- `rank_mode`：`recommend` / `chrono`

**And** 🔴 两者**都要有** `[L1]`

> 只有 `feed_tab` 不够：降级链级别 4 会让 **ALL Tab 也走时间倒序**，
> 那时 `feed_tab=all` 但 `rank_mode=chrono` —— 把它算进推荐序的效果里就是错的。

### AC2 — 命名规范（L0）

**Then** 事件名与属性名符合 `模块_对象_动作`（动作在词尾且为动词）`[L0]`
**And** 🛡 契约测试必须绿；需扩白名单时**写明理由，不改产品定的名字** `[L0]`

> Story 10-1 已把白名单机制与"扩表不改名"的先例立好，照它做。

### AC3 — 回写文档（L0）

**Then** `docs/analytics-posthog-tracking.md` 补本 story 的事件与属性 `[L0]`
**And** 🛡 与 `埋点清单v116.md` 两边一致 `[L0]`

> ⚠️ Story 10-1 的对账发现过：清单里有十条事件名与实现不符（PRD 改过、清单漏同步）。
> 本 story 新增的名字**两边同时写**，不要留第二次对账的活。

### AC4 — 🛡 曝光类埋点不在本 story（L0）

**Then** 🛡 **不新增曝光埋点** `[L0]`

> ⚠️ PRD §8.3 已核实数据侧**没有曝光类埋点**，而灰度观测建议的"人均浏览深度"依赖它。
> 但本补充**没有把它列为交付项**，且 16-1 的曝光记录用的是"下发即记"、**不依赖客户端上报**。
> 🔴 **记录一处缺口**：没有曝光埋点 ⇒ "人均浏览深度"这个观测指标**做不出来**（OQ-B8 提到的四个指标里的一个）。

---

## Tasks / Subtasks

- [x] **T1 · 两个属性接入 Feed 埋点**（AC1）
- [x] **T2 · 契约测试与白名单**（AC2）
- [x] **T3 · 回写两份文档**（AC3）
- [x] **T4 · 测试**
  - [x] L1：ALL Tab 正常态 → `rank_mode=recommend`
  - [x] L1：🔴 ALL Tab 降级到级别 4 → `rank_mode=chrono`（钉住 AC1 那条"只有 feed_tab 不够"）
  - [x] L1：非 ALL Tab → `rank_mode=chrono`
  - [x] L0：契约测试绿；两份文档事件集合一致

## References

- [Source: V1.1.6/1-1-6补充prd.md#3.1] §2 第 6 条末段 · §8.3 灰度与验证
- [Source: V1.1.6/埋点清单v116.md] 命名规范与"按属性验收"的口径

---

## Dev Agent Record

### Context Reference

- `_bmad-output/planning-artifacts/epics-v1.1.6.md` §补充：Epic 16–18
- `V1.1.6/1-1-6补充prd.md` §2 第 6 条末段 · §8.3 灰度与验证
- `V1.1.6/埋点清单v116.md` 命名规范与「按属性验收」的口径

### Agent Model Used

claude-opus-5[1m]

### Debug Log References

一次「反证发现测试缺口」（见 Completion Notes 第 5 条）。

### Completion Notes List

**1. 🔴 `rank_mode` 必须由服务端下发 —— 这是本 story 唯一真正的设计决定**

客户端**推不出来**：降级链级别 4 会让 ALL Tab **也走时间倒序**，而那对客户端完全无感。
按「是不是 ALL Tab」自己判断的话，降级期间的数据会被算进推荐序的效果里 ——
而那正是 FR-95 参数校准要看的数（第一次校准必然在发版之后，OQ-B1）。

实现：`FeedPageResponse` 新增 `rankMode`。
- `recommendedFeed` 成功 → `recommend`
- `chronoFeed`（分类 Tab **与**级别 4 回落）→ `chrono`
- 🛡 「我的发布」→ `null`（Jackson 省略）。填个 `chrono` 会让它被算进首页排序的分母里

**2. 🔴 两个"说不清"的取值，都是有意加的**

| 取值 | 什么时候 | 为什么不挑一边冒充 |
|---|---|---|
| `mixed` | 同一次刷新里各页路径不一致（首屏推荐序、第二页恰好降级） | 记成 `recommend` 就把降级期间的数据算进效果里；记成 `chrono` 又把推荐序的首屏效果丢掉 |
| `unknown` | 服务端没下发（老后端） | 猜错哪边都污染归因；`unknown` 在看板上一眼能筛掉 |

⚠️ 我第一版给 `FeedState.rankMode` 写的注释是「翻页时不覆盖……保留最近一次取数的值」——
**自相矛盾**（既说不覆盖又说保留最近一次）。想清楚之后改成 `RankMode.merge`：
路径相同就保留，不同就 `mixed`，任一为 `unknown` 则整段 `unknown`。

**3. 🛡 缺任一个就都不带（不发半套）**

半套记录比没有更糟：看板上会出现一批「有 `feed_tab` 没有 `rank_mode`」的记录，
既不能算进推荐序也不能算进时间倒序，**只能整批扔掉，而扔的时候没人知道它们本来属于哪边**。

**4. 属性显式串下来，没做成全局可变状态**

`home_page → FeedMasonryView → MasonryCard → LikeButton` 一路显式传。
🛡 **刻意不做「当前 Feed 上下文」那种模块级可变状态** —— 那正是产生错误归因的写法
（用户已经进详情页了，全局状态还留着首页的 tab）。
代价是多了几个可选参数；换来的是**不可能串错**。

⚠️ 详情页点赞（`source=detail`）**不带**这两个属性 —— 那里没有「哪个 Tab、哪条排序路径」
这回事，硬填会在看板上造出不存在的 Feed 会话。有测试钉这条。

**5. 🔴 反证发现了一个测试缺口（不是实现问题，是我漏测）**

反证「半套属性也发」时，`flutter analyze` 通过、**所有测试仍然绿** ——
说明那条 🛡 规则当时**没有任何测试覆盖**（它写在 `_FeedMasonryViewState` 的私有 getter 里，
而 FeedView 的 widget 测试成本高，我没写）。

修法：把规则抽成 `RankMode.eventProps(feedTab, rankMode)` 公开纯函数，
FeedView 只取值不再另写一遍。重做反证 → `Expected: empty / Actual: {'feed_tab': 'all'}`，
这次真的红了。
⚠️ **教训**：「护栏写在私有方法里」等于「护栏没有测试」——
反证的价值有一半在于暴露这种没人会去写测试的角落。

**6. `feed_tab` 词表与接口契约值刻意分开**

`FeedCategory.analyticsTab`（`all` / `daily` / `moment` / `tips`）与
`FeedCategory.wire`（`ALL` / `DAILY` / `GROWTH_MOMENT` / `KNOWLEDGE`）是两个值。
🛡 共用一个意味着任何一侧想改都得动另一侧（一个是接口契约、一个是看板词表）。
有一条测试断言两者对每个取值都不相等。

**7. AC2 命名规范：不需要扩白名单**

本 story **不新增事件名**，只给既有事件加属性，所以 `模块_对象_动作` 那套契约测试
一条都不用动。两个属性名 `feed_tab` / `rank_mode` 是小写 snake_case，与既有 `source` 同风格。

**8. AC3 两份文档已同步**（10-1 那次的教训：清单里有十条事件名与实现不符，就是漏同步）

- `docs/analytics-posthog-tracking.md` 新增 §10.8（属性表 + 为什么必须服务端给 +
  哪些事件带 + 哪些不带）
- `埋点清单v116.md` 新增「附二」，同一套口径
⚠️ §10.8 我一开始插错了位置（落到 §10.5 前面），已挪到 §10 末尾。

**9. AC4 的缺口已记为 G-6**

数据侧**从来没有** Feed 曝光埋点。后果两处：
1. 灰度观测建议里的「**人均浏览深度**」这个指标**做不出来**
2. 曝光衰减只能用「序列下发即记曝光」的口径（16-1 AC2）——
   代价是"下发但用户没滚到"的内容也被记入

🛡 本 story 明确**不补**：补它需要一整套视口上报 + 回传，量级远大于现有任何事件。
文档里记成 G-6（缺口表从五项变六项），并有一条测试防止有人"顺手"加一个曝光事件。

**10. 三次反证（改坏实现 → 确认指定断言按预期变红 → 还原）**

| 反证 | 变红的断言 | 变红原因 |
|---|---|---|
| 缺字段时默认成 `chrono` | `缺字段 / 未知值 → unknown` | `Expected 'unknown' Actual 'chrono'` |
| 半套属性也发 | `🛡 缺任一个就都不带` | `Expected: empty / Actual: {'feed_tab': 'all'}`（**这条测试是反证逼出来的**） |
| 分类 Tab 也报 `recommend` | L1 `rankModeTellsTheTwoPathsApart` | `expected "chrono" but was "recommend"` |

**10b. ⚠️ 「Feed 相关埋点」的边界：有一个事件我**没有**加，理由如下**

`content_badge_tooltip_opened`（装饰标签 tooltip）在 Feed 卡片上也会触发
（`position: 'feed'`），按字面它算「Feed 相关埋点」。**我没给它加这两个属性。**

理由：它衡量的是**标签本身讲不讲得清**（FR-74/75），不是排序效果 ——
「这一屏是推荐序还是时间倒序」不改变用户看不看得懂那个标签的含义，
而它已经有 `position` 区分展示位。加上去只会让这个事件多两个与它无关的维度。

🔴 **写在这里而不是默默跳过**：日后若真要看「不同排序路径下标签 tooltip 打开率」，
知道它当初是被有意排除的（而不是漏了），才不会白查一轮埋点是否坏了。

⚠️ 另外两类在 Feed 上的动作**压根没有事件**，所以无从加属性：
点卡片进详情、点评论进详情 —— 两者都只是 `context.push`。
这是既有状态，不属于本 story 范围（要补得先立事件，而那需要产品定名）。

**10c. ⚠️ 全量套件红了一条，与本 story 无关 —— 但有我该负责的部分**

`ConsultPayIntegrationTest.payTimeoutEndsRequestAndRecordsTimeout` 红在
`expected: 528L but was: 527L`。隔离跑该类 11 条全绿。

**是什么**：那条断言是「全局计数 +1」型（`failedRequests.count()` 数<b>整张表</b>），
而共享测试库不回滚。`@Scheduled` 的 `ConsultRequestTimeoutScanner` 在
`requests.save(req)` 与显式调用 `endExpiredAcceptances()` 之间**先把那条处理掉了** ——
于是 `failedBefore` 已包含扫描器写的那行，显式调用无事可做，计数没涨。
这是既有的竞态（跑测试时能在日志里看到「支付窗超时结束请求 count=15」），不是本 story 引入的。

**我该负责的部分**：Story 16-4 新加的 `FeedRankP95Scanner` 初始延迟 2 分钟、
而全量套件要跑 7 分钟 ⇒ 它**会在跑测试的中途触发**，并对「近 30 天全部公开内容」
做一遍互动统计。它没有制造这个竞态，但**拉长了竞态窗口**。

**处置**：在 L1 基类关掉它（`p95-recompute-enabled=false`）。
🔴 理由不是「让测试快一点」，而是**去掉一份不确定性** ——
它在测试里跑一遍不验证任何东西（`FeedRankP95ScannerTest` 与
`FeedRankConfigLiveIntegrationTest` 已直接覆盖），却会拉长其他集成测试的竞态窗口、
并改写共享库那一行单行配置。已在基类注释里写明「别为了『更像生产』打开它」。

⚠️ **那条既有断言本身没改** —— 它属于 consult 模块，把它从「全局计数」改成「按本次数据计数」
是一处独立的改进，不该混在本 story 里顺手做。记在这里供日后处理。

**10d. 🔴 第二次重跑又红了另一条 —— 挖出一个会反复咬人的本地环境地雷**

`VetPresenceControllerEndpointTest.getOnlineStatus_defaultOffline` 红在
`$.online expected:<false> but was:<true>` —— 一个**刚造出来的**兽医居然是在线的。

**根因（已坐实，附实测数字，免得下次有人重新推一遍）**：
`vet:online` 是 ZSET，按设计**不设 TTL、不做心跳兜底**（在线态纯显式，见
`VetPresenceService` 类注释）。于是本地 Redis 里的 vet id **跨 run 永久累积**；
而 `vets.newActiveVet(...)` 拿的是 DB 自增 id。
实测：集合里有 **161 个遗留 id（最小 2275）**，而库里 `max(vet_accounts.id) = 2350`
—— 自增序列正走在被污染的区间里，每次运行都可能随机撞上一个"出生即在线"的 id。

⚠️ 这解释了为什么**每次重跑红的是不同的测试**：不是我的改动不稳定，
是共享的本地 Redis / PostgreSQL 里堆着历次运行的残留，而我这几轮大量造数据
把 id 序列推进到了被污染的区间。

**我做的**：清掉本地 dev Redis（**DB0**，非生产 DB2 / 非 stag DB3，已先确认）的
`vet:online` 与 `vet:busy`。清掉等于"本地没有兽医在线"，那本来就是默认态。

**我没做的（留给 vet 模块的人）**：让这条测试自足 —— 断言前先显式 `goOffline`，
或用一个保证未被占用的 id。🔴 **durable fix 在测试侧，不在生产代码侧**：
生产环境的 `vet:online` 不会有"未来的 id"，这是纯测试环境问题。
不顺手改是因为它属于另一个模块的测试，而顺手改别人的断言，出问题时没人知道是谁改的。

**11. 本 story 零迁移**（只加一个响应字段）。flyway-guard 无关。

**12. 留 L2 的**
- 真机上确认事件真的进了 PostHog（属性名与取值）—— 需真实 project token
- 级别 4 真实触发时 `rank_mode=chrono` 的端到端确认（与 16-3 那条 L2 一起做）
- ⚠️ **发版后**用这两个属性做 FR-95 的效果归因与参数校准（OQ-B1）——
  这不是验收项，是本 story 存在的目的

### File List

**修改（后端）**
- `.../content/dto/FeedPageResponse.java`（新增 `rankMode` + 两个常量）
- `.../content/service/FeedService.java`（三处出口分别下发 recommend / chrono / null）

**修改（App）**
- `lib/features/content/domain/feed_item.dart`（`FeedCategory.analyticsTab`、`RankMode`
  含 `merge` / `eventProps`、`FeedPage.rankMode`）
- `lib/features/content/presentation/feed_controller.dart`（`FeedState.rankMode` + 翻页合并）
- `lib/features/content/presentation/feed_view.dart`（`feedTab` / `rankMode` 入参，
  四处顶置事件带上 `_rankContext`，两处卡片透传）
- `lib/features/content/presentation/like_button.dart`（两个可选参数 + 事件属性）
- `lib/shared/widgets/masonry_card.dart`（透传给点赞按钮）
- `lib/features/content/presentation/home_page.dart`（从 category 与 state 取值传下去）

**修改（文档）**
- `docs/analytics-posthog-tracking.md`（新增 §10.8；缺口表新增 G-6，五项→六项）
- `_bmad-output/planning-artifacts/v1.1.6/埋点清单v116.md`（新增「附二」）

**新增（测试）**
- `petgo_app/test/analytics/v116_feed_rank_attribution_test.dart`（L0 · 10）

**修改（测试）**
- `.../support/ApiIntegrationTest.java`（L1 关掉 P95 定时重算，见第 10c 条）
- `.../content/dto/FeedResponseContractTest.java`（信封多一个字段；新增「非 Feed 出口省略」一条）
- `.../content/rank/FeedRankRoutingIntegrationTest.java`（新增两条 rankMode 断言）

---

## Change Log

| 日期 | 变更 |
|---|---|
| 2026-08-24 | 由 1.1.6 补充 PRD 拆出（拆前做过一次代码核查，五处过期陈述已修正），status = ready-for-dev |
| 2026-08-24 | 实现完成：rankMode 由服务端下发（客户端推不出来）、mixed/unknown 两个"说不清"取值、半套属性不发；两份埋点文档同步、曝光缺口记为 G-6；三次反证确认（其中一条测试是反证逼出来的）；status = review |
