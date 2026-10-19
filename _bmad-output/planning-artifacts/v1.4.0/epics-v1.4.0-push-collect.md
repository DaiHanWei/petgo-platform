---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/1-4-0推送开关与收藏prd.md
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-push-collect-delta.md
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md                         # ④ 节
  - _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
version: v1.4.0
theme: push-collect
status: complete
updated: '2026-10-18'
---

# petgo-platform V1.4.0 push-collect - Epic Breakdown

## Overview

V1.4.0 ④「推送分类开关 · 收藏」PRD（FR-150 / FR-151，无后台条目）拆分。分支 `feat/1.4.0-push-collect`（从 `dev_1.4.0` 切出，与 ①②③ **并行开发**，P-1）。

> 🔴 **口径优先级**：`decision-log.md` ④ 节 > PRD > `architecture-v1.4.0-push-collect-delta.md`（AD-1 ~ AD-17、P-1 ~ P-8）> UI 稿（定稿）。
> 🔴 **并行约束**：②「我的」页 Hewanku 改版、③ 详情页礼物区、① 成长值监听在本分支不存在。本线只改自己负责的区域；Epic 3 对齐 story **不在本功能分支做**，①②③ 均合入 `dev_1.4.0` 后在对齐分支补做。

## Requirements Inventory

### Functional Requirements
- **FR-150.1** 9 个推送分类（点赞 / 评论与回复 / @ 提及 / 新粉丝 / 礼物 / 宠物提醒 / 成就与等级 / 活动与优惠 / 订单与账号），每个通知类型属于且只属于一类；「订单与账号」不可关；默认全开。
- **FR-150.2** 开关只管系统推送：通知中心照常落库、铃铛照常 +1；运营公告（绕过 `NotificationService`）同样受控。
- **FR-150.3** 设置存服务端、跟账号走、只存关闭项；旧版 App 不受影响。
- **FR-150.4** 通知设置页（系统权限状态卡、分组开关、切换即保存、失败回弹）；设置页「通知」改入口行 + 状态右值。
- **FR-150.5** 注销显式清理推送设置。
- **FR-151.1** 收藏公开帖子（详情页操作栏）与场所（详情页右上角），游客提示登录，收藏 Toast 可跳「我的」，取消不提示，失败回弹。
- **FR-151.2** 「我的」页 Postinganku / Disimpan Tab（锁 + 仅自己可见），Disimpan 内帖子 / 场所分段，按收藏时间倒序。
- **FR-151.3** 不可见条目保留（帖子：删除 / 下架 / 作者注销 / 举报预处置 / 拉黑；场所：删除 / 下架），可移除，恢复后自动正常。
- **FR-151.4** 帖子收藏数仅作者在详情页可见、不计自己；场所收藏数不展示；被收藏不通知。
- **FR-151.5** 发 `ContentCollectedEvent` / `ContentUncollectedEvent`（① 入账 / 扣回），移除不可见条目不发；场所收藏无事件。
- **FR-151.6** 收藏不接入推荐；注销显式清理收藏。
- **FR-AN** 埋点 8 个（PRD §4）。

### NonFunctional Requirements
- **NFR1 闸门完整**：所有可关类型的推送出口都经 `PushPreferenceService`；映射编译期穷尽 + 单测全覆盖。
- **NFR2 隐私**：收藏仅本人可见；不可见帖子不下发原标题 / 作者 / 封面；对外不外露新自增 id。
- **NFR3 并行**：不引用、不自造 ①②③ 的类；地基契约只引用。
- **NFR4 护栏**：不引新依赖 / MQ / 缓存；迁移预分配 `V20261018_1800` / `1801`；新 `/me/**` 端点显式 `hasRole("USER")`。
- **NFR5 本地化**：分类名称与说明在 App（不下发显示串）；新增 arb EN / ID 两份。

### UX Design Requirements
A1–A2 设置页入口 · B1–B6 通知设置页 · C1–C5 帖子详情底栏收藏 · D1–D4 场所详情收藏 · E1–E9「我的」页 Disimpan。

### FR Coverage Map
FR-150.1 / .3 / .5 → 1.1 · FR-150.2 → 1.2 · FR-150.4 → 1.3 · FR-151.1 / .4 / .5 / .6 → 2.1 / 2.3 · FR-151.2 / .3 → 2.2 / 2.4 · 对齐 → 3.1 · FR-AN → 1.3 / 2.3 / 2.4

## Epic List

### Epic 1: 按分类关掉不想要的推送
用户在「我的 → 设置 → 通知设置」按 9 个分类开关推送，关掉的分类只进通知中心不推送，订单与账号类始终推送。
**FRs covered:** FR-150.1 ~ .5 · **迁移:** `V20261018_1800`

### Epic 2: 收藏帖子与场所
用户在帖子 / 场所详情页收藏，在「我的」页 Disimpan 查看与移除；作者看到自己帖子的收藏数，被收藏的作者由 ① 得成长值。
**FRs covered:** FR-151.1 ~ .6 · **迁移:** `V20261018_1801`

### Epic 3: 与 ①②③ 对齐（对齐分支）
①②③ 合入后合并「我的」页与详情页、补齐新推送出口闸门、端到端验收被收藏成长值。

**依赖：** Epic 1 → 2（功能分支，云端串行）；Epic 3 在对齐分支，前置 ①②③④ 均合入 `dev_1.4.0`。

## Epic 1: 按分类关掉不想要的推送

### Story 1.1: 推送分类映射与设置接口

As a 嫌推送太吵的用户,
I want 我的推送分类设置存在服务端、跟着账号走,
So that 换手机也不用重新设置。

**范围：** 后端。迁移 `V20261018_1800`（`user_push_mutes`，AD-2）；`PushCategory` + 穷尽映射（AD-1）；`PushPreferenceService`（AD-3）；`GET / PATCH /api/v1/me/push-settings`（AD-4）；注销清理（AD-6）。

**Acceptance Criteria:**

**Given** `NotificationType` 全部 39 个值
**When** 跑映射单测
**Then** 每个值都属于且只属于一个分类，映射与 PRD §3.1 表一致（AC6）；`switch` 无 `default`，新增枚举值未映射即编译失败

**Given** 从未设置过的用户
**When** `GET /api/v1/me/push-settings`
**Then** 9 项全部 `enabled = true`，`ORDER_ACCOUNT.lockable = false`

**Given** 用户在手机 A `PATCH {LIKE:false, FOLLOWER:false}`
**When** 从手机 B（同账号）`GET`
**Then** 两项为关闭，其余开启（AC4）；库里只有两行

**Given** `PATCH {ORDER_ACCOUNT:false}`
**When** 请求
**Then** 422 `push-category-locked`，未知 code 400；兽医 token 访问 403

**And** 注销后该用户 `user_push_mutes` 无行，且清理在 `authDeletion` 之前（`inOrder`）；`mvn -B clean package` 绿。

### Story 1.2: 推送闸门

As a 关掉了某类推送的用户,
I want 这类通知照样进通知中心但手机不再弹推送,
So that 不错过信息，也不被打扰。

**范围：** 后端。AD-5：`NotificationService` 的 `write`（`send`）/ `sendWithCopy` / `pushInteraction` 三处闸门（`pushInteraction` 先于去抖抢键）；`OpsNotificationDispatcher` 按批预取 `PROMO` 关闭名单（P-4）。

**Acceptance Criteria:**

**Given** B 关掉「点赞」
**When** A 赞 B 的帖子
**Then** 通知中心有这条、未读 +1，pusher 未被调用、去抖键未被占；B 打开后再被赞恢复推送（AC1）

**Given** B 关掉「活动与优惠」
**When** 运营公告发到 B
**Then** 通知中心有这条，`pushToUserNow` 未对 B 调用、不计失败数（后台「触达」口径不变）（AC2）；工单结案时 `TICKET_RESOLVED` 照常推送、`CSAT_SURVEY` 不推送（AC2a）

**Given** B 关掉「@ 提及」、开着「评论与回复」
**When** 被 @、被评论
**Then** @ 不推送、评论照常推送（AC2b）

**Given** B 关掉除「订单与账号」外全部 8 类
**When** 订单发货 / 名额到期 / 账号处罚
**Then** 照常推送（AC3）

**And** 闸门查询异常时照常推送并记 warn；`sendWithoutPush` / `sendToVet` / IM 系统消息行为不变。

### Story 1.3: App 通知设置页与设置页入口

As a 用户,
I want 在设置里进入通知设置、按分类切换推送，并看到系统通知权限状态,
So that 我能自己决定收哪些推送。

**范围：** App。AD-14、UI A1–A2、B1–B6；埋点 `push_settings_viewed` / `push_category_toggled`；新增 arb。

**Acceptance Criteria:**

**Given** 系统通知权限开启 / 关闭
**When** 打开设置页
**Then** AKUN「Notifikasi」是一行入口，右值「Aktif」/ 红色「Notifikasi sistem nonaktif」+ 红点，点击进通知设置页（AC5a）；其余 10 行不动

**Given** 系统权限关闭
**When** 打开通知设置页
**Then** 顶部浅红卡 + 说明 +「Buka Pengaturan」，点击调 `openPushSettings`，从系统设置返回后自动刷新（AC5）；9 个开关仍可操作（B3）

**Given** 通知设置页
**When** 查看
**Then** 4 组 9 行、图标与颜色取通知中心同类、「Pesanan & akun」为「🔒 Selalu aktif」胶囊无开关、底部说明一行

**Given** 切换「Suka」
**When** 保存失败
**Then** 开关弹回原状 + Toast「Gagal menyimpan, coba lagi」（B4）；加载中骨架屏（B5）、加载失败可重试（B6）

**And** `pushSettingsProvider` 登记 `resetUserScopedCaches`；埋点过命名守卫与 V1.4.0 清单；`flutter analyze` / `flutter test` 绿。

## Epic 2: 收藏帖子与场所

### Story 2.1: 帖子与场所收藏后端

As a 用户,
I want 收藏公开的帖子和场所，详情页显示我是否已收藏,
So that 以后能再找到它们。

**范围：** 后端。迁移 `V20261018_1801`（AD-7）；`CollectService` 帖子收藏 / 取消与事件（AD-8，P-2 / P-7）；场所收藏 / 取消（AD-9，P-6）；详情字段 `collected` / `collectCount`（AD-10）；推荐不接入结构测试（AD-12）；注销清理（AD-13）。

**Acceptance Criteria:**

**Given** A 收藏 B 的公开帖子
**When** `POST /api/v1/content-posts/{id}/collect`
**Then** `collected = true`，发布一次 `ContentCollectedEvent(postId, A, B)`，不产生任何通知；重复收藏幂等、不重复发事件

**Given** B 收藏自己的帖子
**When** 收藏
**Then** 照常发 `ContentCollectedEvent(postId, B, B)`（AC11a 由 ① 不入账）

**Given** A 取消收藏一条对 A 可见的帖子
**When** `DELETE …/collect`
**Then** 发 `ContentUncollectedEvent`；帖子已下架 / 作者注销 / 双向拉黑时取消 → 不发事件（AC11c，P-7）；本就未收藏 → 不发

**Given** 私密帖 / 已删除帖
**When** 收藏
**Then** 422 `collect-not-public` / 404

**Given** 作者 B 的帖子被 A、C 和 B 自己收藏
**When** B 看详情 / A 看详情
**Then** B 看到 `collectCount = 2`（不计自己）、A 的响应 `collectCount = null`（AC10）；A 的 `collected = true`

**Given** 场所 P
**When** 收藏 / 取消
**Then** `PlaceDetailResponse.collected` 随之变化，无事件、无成长值；下架场所收藏 404；MERGED 场所落在保留场所上

**And** 推荐代码不读收藏表（AC12 结构测试）；注销删本人两表行且在 `authDeletion` 之前；`mvn -B clean package` 绿。

### Story 2.2: 我的收藏列表后端

As a 用户,
I want 按收藏时间倒序分页看我收藏的帖子和场所，看不到的内容也保留一条,
So that 我知道它还在、可以自己移除。

**范围：** 后端。`GET /api/v1/me/collections/posts|places?cursor=`（AD-11，P-5 / P-6），批量组装无 N+1。

**Acceptance Criteria:**

**Given** 收藏 25 条帖子
**When** 翻页
**Then** 按收藏时间倒序 20 / 5、无重复无遗漏、末页 `nextCursor = null`

**Given** 收藏的帖子被删除 / 下架 / 作者注销 / 举报预处置 / 我拉黑作者 / 作者拉黑我
**When** 拉列表
**Then** 该行 `available = false`、`post = null`（不含摘要、作者、封面）；内容恢复后 `available = true`（AC9）

**Given** 收藏的场所被下架
**When** 拉列表
**Then** `available = false`、仍下发 `name`；恢复后正常（AC9a）；MERGED 按保留场所显示、重复去重

**And** 只返回本人数据、兽医 token 403；查询次数与页大小无关（N+1 测试）。

### Story 2.3: App 帖子与场所收藏按钮

As a 用户,
I want 在帖子详情底栏和场所详情右上角一点就收藏,
So that 操作顺手、状态清楚。

**范围：** App。AD-15、UI C1–C5、D1–D4；埋点 `content_collected` / `content_uncollected` / `place_collected` / `place_uncollected`；新增 arb。

**Acceptance Criteria:**

**Given** 已登录用户在公开帖子详情页
**When** 点收藏
**Then** 立即变实心紫、Toast「Disimpan」+「Lihat」（跳「我的 → Disimpan → Postingan」）；再点取消不提示（AC7）；失败弹回 + 对应 Toast

**Given** 游客点帖子或场所的收藏
**When** 点击
**Then** 弹登录框，与点赞一致（AC7a）

**Given** 作者看自己的帖子
**When** 打开详情
**Then** 收藏图标后显示数量 + 小锁，他人看不到（AC10）；私密帖不显示收藏按钮

**Given** 场所详情
**When** 场所存在
**Then** 「⋯」左侧显示收藏按钮，404 空态不显示；收藏后 Toast「Lihat」跳 Tempat（AC8）

**And** 埋点过守卫；`flutter analyze` / `flutter test` 绿。

### Story 2.4: App「我的」页 Disimpan Tab

As a 用户,
I want 在「我的」页切到 Disimpan 看我收藏的帖子和场所,
So that 想再看时能找到。

**范围：** App。AD-16、UI E1–E9；埋点 `collection_list_viewed` / `collection_item_opened`；新增 arb。

**Acceptance Criteria:**

**Given** 「我的」页
**When** 查看帖子区
**Then** Tab 行「Postinganku」/「Disimpan 🔒」，默认 Postinganku 且内容不变；Disimpan 内「Postingan / Tempat」分段 + 「🔒 Hanya kamu yang bisa melihat」（AC8a）；他人主页无此 Tab

**Given** 刚收藏一条帖子
**When** 进 Disimpan → Postingan
**Then** 它在最上方（AC7）；场所同理（AC8）

**Given** 不可见帖子 / 场所
**When** 查看与点击
**Then** 帖子只显示虚线框 +「Konten tidak tersedia lagi」；场所保留灰名称、缩略图灰度；点击弹危险确认抽屉，确认后移除 + Toast「Dihapus dari Disimpan」（AC9 / AC9a）

**Given** 无收藏 / 加载失败
**When** 查看
**Then** 对应空态（无按钮）/ Tab 内容区「Gagal memuat」+「Coba lagi」，名片照常

**And** 两个列表 provider 登记 `resetUserScopedCaches`；`/me?saved=post|place` 落到对应子分类；`flutter analyze` / `flutter test` 绿。

## Epic 3: 与 ①②③ 对齐（对齐分支）

### Story 3.1: 推送收藏与 ①②③ 对齐

As a 用户,
I want 「我的」页、详情页在四条线合并后布局正确，收藏能给作者带来成长值,
So that 四条线一起上线时体验完整。

**范围：** 后端 + App，**对齐分支 `feat/1.4.0-push-collect-align`，前置 ①②③④ 均已合入 `dev_1.4.0`**（云端不执行）。架构 §6 全部内容。

**Acceptance Criteria:**

**Given** 合并后的「我的」页与帖子详情页
**When** 对照 UI 稿
**Then** Hewanku（②）与 Postinganku / Disimpan 共存；操作栏「点赞 · 分享卡 · 收藏」间距正确、礼物区（③）在正文后；还原度 ≥ 95%

**Given** ①③ 新增的推送出口
**When** 检查
**Then** 均经过 `PushPreferenceService`；关掉「礼物」后收礼不推送、关掉「成就与等级」后升级不推送；名额到期（订单与账号）照常推送

**Given** ① 已合入
**When** A 收藏 B 的帖子 / 取消 / 再收藏 / B 自收藏 / 下架后移除
**Then** B 依次 +2 / −2 / +0、自收藏无流水、移除不可见条目不扣（AC11 / 11a / 11b / 11c）；B 当天被动已满 50 时不入账
