---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
status: complete
updated: '2026-11-14'
version: v1.5.0
theme: onboarding-recall
inputDocuments:
  - _bmad-output/planning-artifacts/v1.5.0/首日与新手任务/1-5-0首日与新手任务prd.md
  - _bmad-output/planning-artifacts/v1.5.0/推送与召回/1-5-0推送与召回prd.md
  - _bmad-output/planning-artifacts/v1.5.0/architecture-v1.5.0-onboarding-recall-delta.md
  - _bmad-output/planning-artifacts/v1.5.0/首日与新手任务/ui-1-5-0-onboarding-newbie-integrated-v1.html（+ 拆页-App/ 21 屏）
  - _bmad-output/planning-artifacts/v1.5.0/推送与召回/ui-1-5-0-push-recall-integrated-v1.html（+ 拆页-App/ 16 屏）
  - _bmad-output/planning-artifacts/v1.5.0/decision-log.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md（2026-11-14 V1.5.0 六会话共用地基）
---

# petgo-platform V1.5.0 onboarding-recall（会话 ③ 首日与召回）- Epic Breakdown

## Overview

两份 PRD 一个分支串行实现：**先**「首日与新手任务」（FR-173 ~ FR-179，Epic 1 ~ 4），**再**「推送与召回」（FR-180 ~ FR-186，Epic 5 ~ 8）。分支 `feat/1.5.0-onboarding-recall`（从 `dev_1.5.0` 切出；PRD 原建议两个分支，本线合成一个）。每个 epic 内按「后端 → App → 联调」推进。

> 🔴 **口径优先级**：`decision-log.md` > `CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」> PRD（变更记录最晚一条为准）> `architecture-v1.5.0-onboarding-recall-delta.md`（OR-1 ~ OR-26）> UI 稿（定稿 + 拆页）。

## Requirements Inventory

### Functional Requirements

**FR-173 首日推荐页**
- FR-173.1 所有新用户引导出口（发出首条后 / 跳过建档 / 发布页不发 / 没宠物）都进推荐页；推荐页结束沿用原落点（有宠物 `/profile`，否则 `/home`）；建档庆祝页次按钮也先进推荐页。
- FR-173.2 一屏 6 张宠物卡（头像 / 名字 / 品种 / 追更 + 最新带图公开帖的图可点赞）；卡片头像 / 名字点击不跳走；右上角与底部「跳过」，追更或点赞 ≥1 次后底部变「完成」。
- FR-173.3 每账号只出一次（`OnboardingMarkKey.FIRST_DAY_RECOMMEND`）；中途退出不回引导；接口失败或空直接进落点；只有走引导的人会看到（1.5.0 之前注册但 `onboardingCompleted=false` 的视同新用户）。
- FR-173.4 埋点 `onboarding_recommend_viewed / _action / _exited`。

**FR-174 推荐接口**
- FR-174.1 排序同品种 → 同物种其他品种 → 全站；多宠按默认宠物；没宠物 / 游客只看全站；排除自己的宠物；只读照推；不限同主人。
- FR-174.2 游客可调用（无拉黑 / 已追更排除），结果服务端缓存 10 分钟。
- FR-174.3 返回补封面帖 `postId`、首图、是否已赞、该帖赞数（大图与点赞同一条、纯文字帖不当封面）。
- FR-174.4 推荐卡第二行改「品种 · 陪伴天数」（无品种回落物种），所有 `RecommendedPetCard` 位置一起变。

**FR-175 游客展示页真实宠物区**
- FR-175.1 展示页介绍之后、注册按钮之前加横滑 6 张；点图进帖子详情；点追更 / 点赞弹硬登录、登录后不自动补做；失败或空整块不显示。
- FR-175.2 埋点 `guest_real_pets_viewed / _tapped`，登录来源新值 `guest_real_pets`。

**FR-176 新手任务 7 项**
- FR-176.1 7 项按账号判定、行为发生时记账号「做过」、完成后不撤：① 发 Diary ② 被别人点赞（不排除虚拟号 / 自动点赞）③ 关注人或追更宠物 ④ 给别人点赞 ⑤ 别人帖子下一级评论 ⑥ 回复别人的评论 ⑦ 注册满 7 天（每日判定 + 读时判定）。
- FR-176.2 接口没宠物也返回；「去完成」落点表；任务卡收起只显示进度 + 下一项；已解锁 Lulus 的账号直接显示完成横幅。
- FR-176.3 埋点 `newbie_task_card_viewed / newbie_task_go_tapped`，后端 `newbie_task_completed`。

**FR-177 Lulus Pemula 账号级**
- FR-177.1 7 项全完成解锁；全部 ACTIVE 宠物同时完成，之后新建 / 恢复直接带上；无 ACTIVE 宠物照常解锁并发通知。
- FR-177.2 完成当下不弹，只发 1 条 `MILESTONE_SM_NODE`；点通知或进里程碑页（含账号级里程碑页）补弹一次，按账号只弹一次；新增庆祝正文；无 ACTIVE 宠物时「Bagikan ke Komunitas」预选 Moment + 新预填句。
- FR-177.3 成长值按账号一次 +20，新规则 `NEWBIE_GRADUATED`（后台可调）。

**FR-178 里程碑入口与账号成就区**
- FR-178.1 没宠物：「我的」页 Edit Profil 下加入口 → 账号级里程碑页（新手任务卡 + 账号成就 3 项）；建宠后入口消失。
- FR-178.2 有宠物：宠物里程碑页 L/M/S 上方加「账号成就」，S 组不重复，总进度不计这 3 个；所有显示宠物里程碑数的地方同一口径。

**FR-179 存量补算**
- FR-179.1 已解锁不收回并补到其他 ACTIVE 宠物（不发分不通知）；未解锁按新 7 项回溯，满了解锁 + `LAUNCH_BACKFILL +20`，不弹不通知；可重复执行不重复发放。

**FR-180 关注的人发帖推送**
- FR-180.1 关注的人发公开帖（三类）触发；收件人排除同 `PET_DIARY_POSTED`；官方号 / 虚拟号 / 自动关注目标发帖不推也不写通知。
- FR-180.2 同收件人 × 同作者 × WIB 日最多推 1 条；通知中心每次都写；按收件人语言存标题、无正文、爪印图标；点开进帖子详情；归 `PET_DIARY` 分类，分类改名「Yang kamu ikuti」。

**FR-181 同帖去重 + 每人每天上限**
- FR-181.1 同一帖子命中追更就只走 `PET_DIARY_POSTED`，不改发 FR-180。
- FR-181.2 两类合计每人每 WIB 日推 3 条（配置项），超出只写通知中心；关了分类不占名额。

**FR-182 纪念日催发布**
- FR-182.1 满 30 / 100 / 365 天当天 19:00 WIB；只推 ACTIVE 宠物；同主人同天只推建档最早的，其余只写通知。
- FR-182.2 落点发布页预选 Diary + 选中这只宠物；新文案；通知中心存按语言渲染的文案、正文 2 行；旧通知照旧显示、点开也进发布页。

**FR-183 新用户前 7 天召回**
- FR-183.1 A–E 五层分层（发送时判定）；节点 × 分层落点与文案表；D3 E 层不推；D7 接新手任务（刚毕业 / 还差 N 项）；D1 / D3 当天来过不推不补；日期一律 WIB。
- FR-183.2 通知中心存渲染文案、正文 2 行；D7 图标毕业帽；第 7 天两句豁免宠物名规则。

**FR-184 直达 + 发布页按宠物预选**
- FR-184.1 `USER_POST_PUBLISHED`、`PET_DIARY_POSTED`、`COMPANION_ANNIVERSARY`、`LIFECYCLE_D1/D3/D7` 点推送直达。
- FR-184.2 `/publish?preset=growth-calendar&petId=` 选中该宠物；非本人 / 已删 / 只读忽略。

**FR-185 开关与放量**
- FR-185.1 D1/D3/D7 与召回节点分开开关，本版召回关；每日上限 2,000（第 1 天 300）；关注更新、纪念日各有回滚开关。
- FR-185.2 FR-180 与 FR-183 只发 App 最后上报版本 ≥ 1.5.0 的用户；纪念日照发。

**FR-186 生日推送改 11:00 WIB**
- FR-186.1 生日（前 1 天）与第一个生日里程碑推送同批 11:00 WIB；其余不变。

### NonFunctional Requirements

- NFR-1 不引 MQ / 通用缓存 / 新中间件（游客 10 分钟快照为 PRD 拍板的进程内单值，OR-5）。
- NFR-2 Flyway 时间戳号段 `V20261115_0300` ~ `_0359`（预分配见 sprint-status 头）；`ck_notifications_type` 不动；`ck_user_milestone_flags_key` DROP + ADD 重列全集。
- NFR-3 写库监听 `AFTER_COMMIT` + `REQUIRES_NEW`；扇出 `@Async` 分批。
- NFR-4 日志不含 PII / 文案 / 昵称 / 宠物名。
- NFR-5 旧版 App（< 1.5.0）兼容见架构 §7。
- NFR-6 每条 AC 标验证层级：L0 静态（云端可跑）/ L1 Docker postgres + redis / L2 真机或模拟器 + 真推送。

### Additional Requirements（来自架构增量）

- 新表 `user_newbie_marks`（OR-1）；`user_milestone_flags.celebrated_at` + 第 3 个 flag（OR-6）；三张关注更新推送表（OR-14 / OR-17）。
- 新端点：`/api/v1/public/pet-recommendations`、`/api/v1/me/account-milestones`（+ `/celebrations`）。
- `NotificationService.sendRendered`（OR-19）；`RetentionPushCalendar.anniversaryPushPets` 方法体（OR-20）。

### UX Design Requirements

- UX-1 首日稿 21 屏（A 推荐页 5 / B 游客区 4 / C 任务卡 5 / D 我的页与账号级里程碑页 3 / E 毕业通知与庆祝 4）逐屏照拆页；新增文案 ID / EN 逐条照整合稿「新增文案」表。
- UX-2 推送稿 16 屏（A 关注更新 5 / B 纪念日 3 / C 召回 8）；通知中心纪念日 / 召回正文 2 行、关注更新爪印图标、D7 毕业帽图标。
- UX-3 点赞胶囊、追更按钮、「Yuk!」热区 44×44；数字 `FontFeature.tabularFigures()`。

### FR Coverage Map

| FR | Story |
|---|---|
| FR-173 | 1-2（App），1-1（标记键），4-1（联调） |
| FR-174 | 1-1（后端），1-2（卡片第二行） |
| FR-175 | 1-3（App），1-1（游客接口） |
| FR-176 | 2-1（后端），3-1（App） |
| FR-177 | 2-2（后端），3-3（App） |
| FR-178 | 2-3（后端），3-2（App） |
| FR-179 | 2-4 |
| FR-180 / FR-181 | 5-1（后端），7-2（App） |
| FR-182 / FR-186 | 5-2（后端），7-1 / 7-2（App） |
| FR-183 | 6-1（后端），7-1 / 7-2（App） |
| FR-184 | 7-1 |
| FR-185 | 6-2（后端开关 / 门槛），5-1 / 5-2（回滚开关），8-1（上线清单） |

## Epic List

| Epic | 名称 | Story | 依赖 |
|---|---|---|---|
| 1 | 首日推荐（FR-173 / 174 / 175） | 1-1 后端 · 1-2 App · 1-3 App | 地基 0.1 |
| 2 | 新手任务与 Lulus Pemula 后端（FR-176 / 177 / 178 / 179） | 2-1 · 2-2 · 2-3 · 2-4 | Epic 1（同分支串行） |
| 3 | 新手任务与里程碑 App | 3-1 · 3-2 · 3-3 | Epic 2 |
| 4 | 首日与新手联调 | 4-1 | Epic 1 ~ 3 |
| 5 | 推送后端：关注更新、纪念日、生日（FR-180 / 181 / 182 / 186） | 5-1 · 5-2 | Epic 4 |
| 6 | 召回后端（FR-183 / 185） | 6-1 · 6-2 | Epic 2（标记）、Epic 5（`sendRendered`） |
| 7 | 推送 App（FR-184 + 通知中心 + 设置） | 7-1 · 7-2 | Epic 5 / 6 |
| 8 | 推送召回联调与上线清单 | 8-1 | Epic 5 ~ 7 |

共 **8 个 epic、18 条 story**。

---

## Epic 1：首日推荐

让新用户首日就和别人互动：推荐接口按品种 / 物种排序并支持游客，首日引导末尾接推荐页，游客展示页加真实宠物区。

### Story 1.1：首日推荐宠物接口排序与游客访问（后端）
文件 `1-1-首日推荐宠物接口排序与游客访问.md`。三段 keyset 排序（OR-3）、封面改「最近带图公开帖」并带 `coverPostId / coverLikeCount / coverLikedByViewer / breed / match`（OR-4）、游客端点 `/api/v1/public/pet-recommendations` + 10 分钟进程内快照（OR-2 / OR-5）、`OnboardingMarkKey.FIRST_DAY_RECOMMEND`（OR-7）。PRD AC7 ~ AC10。

### Story 1.2：首日推荐页与引导出口（App）
文件 `1-2-首日推荐页与引导出口.md`。新页 `/onboarding/recommend`（A1 ~ A5）、四个引导出口改接、发布页 `from=onboarding` 成功 / 返回去向、只出现一次、`RecommendedPetCard` 第二行品种 + 点赞胶囊（`LikeButton`）+ 追更按钮、三个埋点。PRD AC1 ~ AC6。

### Story 1.3：首日游客展示页真实宠物区（App）
文件 `1-3-首日游客展示页真实宠物区.md`。`diary_guest_page.dart` 横滑区（B1 ~ B4）、登录门控 `entry_source=guest_real_pets`、失败不渲染、埋点。PRD AC11 ~ AC13。

## Epic 2：新手任务与 Lulus Pemula 后端

### Story 2.1：新手任务账号记录与七项判定（后端）
文件 `2-1-新手任务账号记录与七项判定.md`。迁移 ①、`NewbieTask` 7 项、6 个事件监听写 `user_newbie_marks`、`NewbieWeekJob`（00:05 WIB）、接口新形状 + 旧版兼容（OR-9）、`newbie_task_completed` 埋点、注销清理。PRD AC14 ~ AC20。

### Story 2.2：新手毕业账号级里程碑与成长值（后端）
文件 `2-2-新手毕业账号级里程碑与成长值.md`。迁移 ② ③、`LULUS_PEMULA` flag、`NewbieGraduationService`、删除按宠物判定、`NEWBIE_GRADUATED` +20、通知（无宠物也发）、庆祝回报同时置 flag。PRD AC21 ~ AC23。

### Story 2.3：新手账号成就接口与里程碑计数口径（后端）
文件 `2-3-新手账号成就接口与里程碑计数口径.md`。`/api/v1/me/account-milestones`（+ `/celebrations`）、里程碑列表 `accountGroup` + 新计数口径 + 旧版兼容、Diary 统计 / 访客 / H5 同口径（OR-10 / OR-11）。PRD AC24 ~ AC26 后端部分。

### Story 2.4：新手存量补算（后端迁移）
文件 `2-4-新手存量补算.md`。迁移 ④（OR-12）。PRD AC27 ~ AC29。

## Epic 3：新手任务与里程碑 App

### Story 3.1：新手任务卡改版（App）
文件 `3-1-新手任务卡改版.md`。7 项、收起只显示下一项（C1）、展开带「Yuk!」/「Buat profil」/ 提示 / 剩余天数（C2 / C3）、完成横幅（C4）、失败态（C5）、去完成落点、埋点。

### Story 3.2：新手账号成就区与我的页入口（App）
文件 `3-2-新手账号成就区与我的页入口.md`。宠物里程碑页「Pencapaian akun」区、页头计数新口径、「我的」页入口（D1）、账号级里程碑页（D2 / D3）、`/me/milestones-entry` 分发路由。

### Story 3.3：新手毕业庆祝与通知落点（App）
文件 `3-3-新手毕业庆祝与通知落点.md`。Lulus 编码并入账号级、庆祝文案、补弹（含账号级页）、无 ACTIVE 宠物分享预选 Moment + 新预填句、通知中心毕业通知点开落点、成长值明细文案。

## Epic 4：首日与新手联调

### Story 4.1：首日与新手联调验收
文件 `4-1-首日与新手联调验收.md`。L1 全链路（真库）+ L2 模拟器逐屏还原度 ≥ 95%；里程碑合并检查点（见待拍板 Q-1）。

## Epic 5：推送后端

### Story 5.1：推送关注更新与每日上限（后端）
文件 `5-1-推送关注更新与每日上限.md`。迁移 ⑤、`UserPostNotifyListener` / Writer、`sendUserPostPublished`、同帖去重、作者 / 天去重、两类合计上限（含改 `sendPetDiaryPosted`）、版本门槛、回滚开关、清理任务扩表。PRD AC1 ~ AC9。

### Story 5.2：推送纪念日催发布与生日改时（后端）
文件 `5-2-推送纪念日催发布与生日改时.md`。拆两个日扫、WIB 日期、`AnniversaryPlan`（只读剔除、同主人合并）、`sendRendered`、新文案、`RetentionPushCalendar.anniversaryPushPets` 方法体、回滚开关。PRD AC10 ~ AC14、AC27 ~ AC29。

## Epic 6：召回后端

### Story 6.1：召回分层节点与文案（后端）
文件 `6-1-召回分层节点与文案.md`。快照 + 上下文端口、A–E 分层、节点表、当天活跃作废、WIB 日期、新变体、文案键、`LifecyclePushCopyTest` 例外、改用 `sendRendered`。PRD AC15 ~ AC21。

### Story 6.2：召回放量开关与版本门槛（后端）
文件 `6-2-召回放量开关与版本门槛.md`。召回节点单独开关（默认关）、上限默认 2,000、版本门槛（旧版跳过不写 mark）、`recallNodeUsers` 一致性测试、配置与 `.env.example`。PRD AC25 ~ AC26。

## Epic 7：推送 App

### Story 7.1：推送直达与发布页按宠物预选（App）
文件 `7-1-推送直达与发布页按宠物预选.md`。`kPushDirectTypes` + 6、落点映射（新变体 / 纪念日 / 关注更新 / Lulus 通知）、`/publish?petId=` 透传与校验、`diary_published.entry`。PRD AC22 ~ AC24、AC10 / AC11 / AC14 App 部分。

### Story 7.2：推送通知中心新类型与设置改名（App）
文件 `7-2-推送通知中心新类型与设置改名.md`。`USER_POST_PUBLISHED` 行（A3）、`_postGone`、纪念日 / 召回读后端文案 + 正文 2 行（B2 / C7）、D7 毕业帽、推送设置改名（A5）。

## Epic 8：推送召回联调与上线清单

### Story 8.1：推送召回联调与上线清单
文件 `8-1-推送召回联调与上线清单.md`。L1 全链路 + L2 真机推送；更新 `docs/ops/retention-push-copy-matrix.md`；上线清单（env 开关、第 1 天 300、第 3 天 2,000、回滚）。

---

## 待拍板（均已按推荐默认写进 story，不阻塞；操作者如改口径，回写对应 story）

> ✅ **2026-11-14 操作者拍板：本节全部按推荐默认执行。** story 已按默认写好，无需回改。

| # | 问题 | 推荐默认（story 已按此写） | 涉及 story |
|---|---|---|---|
| Q-1 | 两份 PRD 合成一个分支后，「Feed 双击点赞在首日之后合」怎么落实 | Epic 4 完成且 L0 绿后，云端在 Completion Notes 标「首日检查点」；操作者可在此时把 `feat/1.5.0-onboarding-recall` **正向合入** `dev_1.5.0` 一次（不反向合并），之后继续在同一分支做 Epic 5 ~ 8 | 4-1 |
| Q-2 | 游客推荐「服务端缓存 10 分钟」与护栏「禁止通用缓存层」 | 进程内单值快照（OR-5），不引 Caffeine / Redis；类注释写明是 PRD 拍板的唯一例外 | 1-1 |
| Q-3 | 游客接口路径：UI 稿写「`/me/pet-recommendations` 允许匿名」 | 新开 `/api/v1/public/pet-recommendations`（已 permitAll 前缀），`/me` 保持只认 USER | 1-1 / 1-3 |
| Q-4 | 推荐卡封面口径：现为「最近带图 Diary」，PRD 写「最近一条带图的公开帖子」 | 不限类型（Diary / Moment / Tips 都可当封面），与点赞同一条；旧版 App 也随之换图 | 1-1 |
| Q-5 | 召回 / 推荐文案里「物种词」：附录 A 只给了 Kucing / Anjing、cat / dog | OTHER 用 ID「Anabul」、EN「pet」 | 6-1 |
| Q-6 | 旧版（< 1.5.0）用户的召回：不推时要不要写通知中心 | 不发、不写通知、不写 mark（升级后若仍在窗口内还能收到）；关注更新旧版写通知中心不推（PRD 原文） | 5-1 / 6-2 |
| Q-7 | D1 / D3「当天已来过」作废后，窗口第二天还补不补 | 不补：作废时写 `lifecycle_push_marks`（variant `SKIPPED_ACTIVE`），不推送、不写通知 | 6-1 |
| Q-8 | D7「刚毕业」的判定窗口 | 有 Lulus flag，或 ①–⑥ 标记齐全（⑦ 在 D7 窗口必然满足）——不依赖 00:05 ⑦ 任务是否已跑 | 6-1 |
| Q-9 | 新手任务 ⑥「有人评论过」的判据 | 我作为作者、未删 PUBLISHED 帖子下，存在别人发的未删评论（一级或回复都算），取最近一条所在帖 | 2-1 / 3-1 |
| Q-10 | 新手任务旧版兼容 | < 1.5.0 走旧 6 项旧逻辑（含无宠物 404），只把 `lulusPemulaUnlocked` 改读账号 flag | 2-1 |
| Q-11 | 访客视图 / H5 名片里程碑分母 | 一律新口径（猫狗 32 / 其他 17），不按版本分流 | 2-3 |
| Q-12 | 纪念日与召回同一用户同一天 19:00 都命中 | 两条都发（PRD 只限「一人一天 1 条生命周期推送」，纪念日不在其内） | 5-2 / 6-1 |
| Q-13 | 推荐页埋点 `position` / `match` 取值 | `position` 从 0 起的卡片序号；`match` 由后端 `match` 字段直出（`same_breed / same_species / global`，小写） | 1-2 |
| Q-14 | Lulus 通知在无任何宠物时的 `targetRef` | 账号最早一只宠物（含只读）的 Lulus 编码，再没有用 `C-S16`；App 识别三种 Lulus 编码一律按账号级处理 | 2-2 / 3-3 |
| Q-15 | ⑦ 每日任务时刻 | 00:05 WIB（远早于 19:00 召回） | 2-1 |
