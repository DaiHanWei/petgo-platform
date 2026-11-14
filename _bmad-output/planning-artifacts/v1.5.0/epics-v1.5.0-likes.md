---
title: "V1.5.0 likes 主题（会话 ⑤ 点赞）· Epics 与 Stories"
status: complete
date: '2026-11-14'
version: v1.5.0
theme: likes
branch: feat/1.5.0-likes
architecture: _bmad-output/planning-artifacts/v1.5.0/architecture-v1.5.0-likes-delta.md
sprint_status: _bmad-output/implementation-artifacts/v1.5.0/sprint-status-v1.5.0-likes.yaml
cloud_rules: _bmad-output/implementation-artifacts/v1.5.0/cloud-rules-likes.md
---

# V1.5.0 likes 主题 · Epics 与 Stories

一个云端会话、一个分支 `feat/1.5.0-likes`（从 `dev_1.5.0` 地基 0.1 之后切出），两份 PRD：

- **PRD A 自动点赞**（`自动点赞/1-5-0自动点赞prd.md`，FR-212 ~ FR-223，AC1 ~ AC50，英文）：只改后端，不等 App 发版。→ **Epic 1、Epic 2（先做）**
- **PRD B Feed 双击点赞**（`Feed双击点赞/1-5-0Feed双击点赞prd.md`，FR-209 ~ FR-211，AC1 ~ AC24 + 定稿 UI 与 `拆页-App/`）：只改 App。→ **Epic 3、Epic 4**

两份 PRD 互不依赖；会话内顺序 Epic 1 → 2 → 3 → 4。AC 引用写作「A-AC*n*」「B-AC*n*」。

## 需求覆盖

| 需求 | 内容 | Story |
|---|---|---|
| FR-212 | 调度、多实例单跑、每槽每帖最多一赞、与自动评论独立 | 2.2、2.3 |
| FR-213 | 一次性检查（36–60h；首轮 36h–7d）、审核中顺延 | 2.1 |
| FR-214 | 不设上限、最老优先、每作者每轮 1 个轮流、3 小时随机、计划落库与补发 / MISSED | 2.2、2.3 |
| FR-215 | 每轮一赞、到 5 离开、复查、7 天离开 | 2.1（7 天）、2.3 |
| FR-216 | 选号：排除隐藏、排除已赞、同物种优先、只用 ACTIVE | 2.3 |
| FR-217 | 通知与推送同真人赞 | 1.1（事件）、2.3（L1 验证） |
| FR-218 | `is_automated`、不给获赞成长值、7 处排序 / 推荐 / 统计排除、AI 查数手册 | 1.1、1.2 |
| FR-219 | 目标集与留档、结果枚举、失败 3 次离开、连续 10 次叫停 | 2.1、2.2、2.3 |
| FR-220 | 日报「自动点赞成功数」 | 2.4 |
| FR-221 | 部署层开关（stag / 生产开，dev 关）、首轮识别 | 2.1（配置 + 首轮）、2.4（上线核对） |
| FR-222 | 全部参数可配 | 2.1 |
| FR-223 | 10% 对照组 | 2.1 |
| FR-210 | 点赞状态按帖子统一管理、各列表同步 | 3.1 |
| FR-209 | 图片区双击点赞（手势、规则、范围） | 4.1 |
| FR-211 | 双心 + 小圆点动效、按钮弹跳、轻震 | 4.2 |
| 合并约定 | 在 ③ 首日之后合；推荐页卡片若复用 `LikeButton` 一起接上 | 4.3 |

---

## Epic 1：自动点赞 · 点赞标记与口径隔离（后端）

**目标**：让「自动点的赞」在库里可识别，并且从一开始就不进任何排序、推荐、热度、运营统计；同时对作者而言和真人赞一样（计数、点赞人、里程碑、@ 候选、新手任务、通知）。本 Epic 合入后即使任务还没开，也不改变任何现有行为（全部存量赞 `is_automated = false`）。

### Story 1.1：点赞自动点赞标记列与事件透传
- 迁移 `V20261115_0500`：`content_likes.is_automated BOOLEAN NOT NULL DEFAULT false`；实体 `ContentLike.automated`。
- `ContentLikedEvent` 加第 5 分量 `automated`（保留 4 参构造器）；新增 `LikeService.likeAutomated`（`INSERT … ON CONFLICT DO NOTHING`，真新建才发事件）。
- `GrowthActivityListener.onLiked` 跳过自动赞；里程碑 / @ 候选 / 通知监听不变。
- 覆盖：FR-218 第 1 项、FR-217、A-AC21、AC30、AC31、AC32、AC42（只验证不排除）、AC46。

### Story 1.2：点赞排除排行推荐与统计口径
- `ContentLikeRepository.countOrganicByPostIdIn`；首页推荐打分与 P95 重算改用它；话题热度、话题 Populer、推荐关注、宠物推荐、后台按点赞时间 5 处 SQL 加过滤。
- 日报「点赞数」加回归测试；AI 查数手册加一行。
- 覆盖：FR-218 第 2 ~ 9 项、A-AC33、AC34、AC39（点赞数一半）。

## Epic 2：自动点赞 · 定时任务（后端）

**目标**：按 PRD A 把「检查 → 目标集 → 每轮排期 → 3 小时内逐条派发 → 留档 → 日报」做全，多实例安全，部署即生效（stag / 生产），本机默认关。

### Story 2.1：自动点赞配置目标集与检查
- `AutoLikeProperties` + `application.yml` / `application-dev.yml`；`AutoLikeHoldout`；迁移 `V20261115_0510` `auto_like_targets`。
- `AutoLikeCheckStep`：首轮识别、7 天关闭、失败满 3 次关闭、检查入集合（对照组 / 已满 5 / 入集合）。
- 覆盖：FR-213、FR-215（7 天）、FR-221（首轮）、FR-222、FR-223；A-AC4 ~ AC13、AC41（检查侧）、AC47 ~ AC50。

### Story 2.2：自动点赞计划与多实例防重
- 迁移 `V20261115_0520` `auto_like_runs` + `auto_like_logs`；`AutoLikeJob` 早晚两个 cron；计划事务（advisory 锁、槽唯一、上一轮未完跳过、号池为空叫停）；每作者轮流取 1 帖、随机 `planned_at`、写 `PLANNED`。
- 覆盖：FR-212、FR-214（排期侧）、FR-219（计划侧）；A-AC1、AC2、AC14、AC15、AC26（计划侧）、AC40、AC43、AC44。

### Story 2.3：自动点赞派发复查与留档
- 每分钟派发（Redis 锁 + 行级认领）：MISSED 收口、复查、选号（排除已赞 / 非 ACTIVE）、`likeAutomated`、各结果落档、离开集合、失败计数、连续 10 次叫停与撤回。
- 覆盖：FR-214（派发侧）、FR-215、FR-216、FR-217、FR-219；A-AC3、AC16 ~ AC29、AC35 ~ AC38、AC45。

### Story 2.4：自动点赞日报行与上线核对
- 日报「自动点赞成功数」+ 脚注；全链路 L1 场景测试（UJ-1 Sari 的帖子跨 5 轮）；stag 上线核对清单。
- 覆盖：FR-220、FR-221（环境核对）；A-AC39、AC40（环境侧）、SM-2 的数据来源。

## Epic 3：Feed 双击点赞 · 点赞状态统一（App）

**目标**：先把点赞状态从 `LikeButton` 里搬出来，按帖子统一管理；顺带修好「切页签后点赞回退」「顶置卡与普通卡不同步」「话题页与首页不同步」。点爱心的现有测试一条不改预期。

### Story 3.1：Feed点赞状态按帖子统一管理
- `postLikeStoreProvider`（覆盖 + 点赞版本号）；`LikeButton` 改为读 store；各快照打 `likeEpoch`；落定后 `syncFeedCounts` 照写；换账号清空。
- 覆盖：FR-210；B-AC11 ~ AC17。

## Epic 4：Feed 双击点赞 · 手势、动效与合并回归（App）

**目标**：在 Feed 卡片图片区加双击点赞，复用大图页 220ms 窗口；加双心 + 小圆点动效、按钮弹跳与轻震；最后在 ③ 合入后对首日推荐页做一次对齐回归。

### Story 4.1：Feed图片区双击点赞手势
- `FeedTapArbiter`（220ms、超距、三连击、滚动取消）；`MasonryCard` 图片区接入；游客 `entrySource = double_tap_like`；埋点 `trigger`；读屏退回单击。
- 覆盖：FR-209；B-AC1 ~ AC10；§4 埋点。

### Story 4.2：Feed双击点赞红心动效与触感
- `likeHeartLight / likeHeartDeep`；`FeedLikeBurst`（670ms，双心 + 5 圆点，重播、减少动态效果、离屏结束）；`FeedImage` 中央 `IgnorePointer` 层；爱心按钮弹跳 + 轻震；AD-8 Rule 6 补一句 + 结构断言。
- 覆盖：FR-211；B-AC18 ~ AC24。

### Story 4.3：Feed双击点赞与首日推荐页合并回归
- **不在云端会话里做**：等 ③ `feat/1.5.0-onboarding` 合进 `dev_1.5.0` 后，在从 dev 切的对齐分支 `feat/1.5.0-likes-align` 上把本分支合进去；若 FR-173 推荐页宠物卡复用了 `LikeButton`，接上 `postLikeStoreProvider` 与快照版本号并回归 PRD B §6 全部回归范围。
- 覆盖：PRD B §6「合并顺序」「回归范围」。

---

## 依赖与顺序

```
Epic 1（1.1 → 1.2） → Epic 2（2.1 → 2.2 → 2.3 → 2.4） → Epic 3（3.1） → Epic 4（4.1 → 4.2） ┆ 4.3（③ 合入 dev 后，本地 / 对齐分支）
```

- 1.2 用 1.1 的列；2.3 用 1.1 的 `likeAutomated`；2.2 用 2.1 的检查步骤；2.3 用 2.2 的日志表与排期；2.4 用 2.3 的 LIKED 行。
- 3.1 必须先于 4.1（PRD B「改造顺序：先 FR-210，点爱心测试全绿后再接双击」）；4.2 用 4.1 的双击回调与 3.1 的弹跳信号。
- 跨线：③ 新手任务 ② 的 `ContentLikedEvent` 监听**不得**排除 `automated`（A-AC42，决策 2026-11-12）；③ 首日推荐页复用的 `findRecommendablePets` 在 1.2 之后排序会排除自动赞（这是 PRD A 要的效果，不是回归）。

## 迁移预分配

| 版本 | Story |
|---|---|
| `V20261115_0500__add_content_likes_is_automated.sql` | 1.1 |
| `V20261115_0510__init_auto_like_targets.sql` | 2.1 |
| `V20261115_0520__init_auto_like_runs_and_logs.sql` | 2.2 |
| `V20261115_0530` ~ `V20261115_0559` | 预留 |

---

## 待拍板（均已按推荐默认写进 story，不阻塞开发；主会话 / 产品如推翻，回改对应 story）

> ✅ **2026-11-14 操作者拍板：本节全部按推荐默认执行。** story 已按默认写好，无需回改。

| # | 问题 | 推荐默认（已写入） | 影响 story |
|---|---|---|---|
| Q-1 | PRD FR-222 把「每帖每轮赞数」列为可配，但 FR-212 又要求「每槽每帖最多一赞」，两者冲突 | 保留配置项 `likes-per-run`，**启动校验只接受 1**（其他值启动失败并提示）；`auto_like_logs` 唯一键 `(post_id, run_date, slot)` 不放开 | 2.1、2.2 |
| Q-2 | 派发时选中的虚拟号恰好已赞（并发竞态），`INSERT … ON CONFLICT` 返回 0，记什么结果 | 记 `FAILED` + `error_code = 'ALREADY_LIKED'`；**不**计入帖子失败数、**不**计入连续失败数；帖子留在集合，下轮再来 | 2.3 |
| Q-3 | 号池整体不可用（A-AC26）时，本轮的「检查」还做不做 | 检查照做并提交（帖子按规则入集合，避免它们因两轮都没检查而永远错过），只是不排期；`runs` 记 `STOPPED / NO_POOL` | 2.2 |
| Q-4 | 「第 3 次 FAILED 后离开」的时点 | 在**下一轮计划开始时**统一关闭（`TOO_MANY_FAILURES`），以便被叫停的轮次先撤回自己的失败数；效果上第 3 次失败后不会再被排期 | 2.1、2.3 |
| Q-5 | 后台数据看板 `MetricSql` 的 ALL 口径互动源包含自动赞（PRD 未列） | 不改：REAL 口径已因「互动者须为真人」排除自动赞；ALL 口径定义即全量 | 1.2（只记录） |
| Q-6 | PRD B FR-210「写回所有持有该帖的列表」——顶置 / 话题页是只读 `FutureProvider` 与 autoDispose family，无法逐个原地改 | 用「按帖覆盖 + 快照点赞版本号」统一（同关注态 AD-7）；「发现」「关注」仍经 `syncFeedCounts` 写回 item；验收以 B-AC11 ~ AC17 的可见行为为准 | 3.1 |
| Q-7 | PRD A 要求尽快上线，PRD B 须等 ③ 首日合入后才能合；同一分支怎么分批上 | Epic 2 做完时在分支上打一个提交点（Completion Notes 记 sha）；主会话可以把分支**截到该提交**单独 `merge` 进 `dev_1.5.0`（前缀合并，不 cherry-pick）先走 stag → main；B 部分之后整支再合 | 2.4、cloud-rules |
| Q-8 | 作者是 REAL 但被运营停用（`users.status = DEACTIVATED`）的帖，PRD「real post」定义没提 status | 检查与复查都加 `u.status = 'ACTIVE'`（停用作者看不到通知，赞白点）；复查不满足 → `POST_GONE`。与自动评论的候选条件相比多这一条 | 2.1、2.3 |
| Q-9 | 已赞再双击 / 请求进行中再双击时，下方爱心按钮要不要也弹一下 | 不弹：弹跳只跟「真的发起了点赞」走（未赞 → 已赞）；只播大心 | 4.1、4.2 |
| Q-10 | 自动点赞的 `holdout-salt` 是否需要按环境不同 | 不需要：各环境同一个固定串，写在 `application.yml` 默认值，不进 env（改盐 = 换对照组，会污染 SM-1 对比） | 2.1 |
