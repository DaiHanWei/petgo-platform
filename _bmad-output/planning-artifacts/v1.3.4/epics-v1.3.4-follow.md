---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-follow.md
  - _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-follow-delta.md
  - _bmad-output/planning-artifacts/v1.3.4/决策日志-follow.md
  - _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-follow.html
  - _bmad-output/planning-artifacts/architecture.md                      # 跨版本基线（不重定向）
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md       # 跨 story 契约（不重定向）
version: v1.3.4
theme: follow
status: draft
updated: '2026-10-12'
---

# petgo-platform V1.3.4 follow - Epic Breakdown

## Overview

本文件把 V1.3.4「用户相互关注 · 通知中心优化」PRD（FR-140 / FR-141）、UI 稿与架构增量拆成可实现的 story。范围 App + 后端（无后台改动）。

> 🔴 **口径优先级**：`决策日志-follow.md`（D-1 ~ D-7）> `PRD-v1.3.4-follow.md` > `architecture-v1.3.4-follow-delta.md`（AD-1 ~ AD-16）> UI 稿（`ui-v1.3.4-follow.html`，定稿）。PRD 中 Albar 设计图仅作规则出处，视觉以 UI 稿为准。

## Requirements Inventory

### Functional Requirements

**FR-140 用户相互关注**

- **FR-140.1** 单向关注，无需对方同意；互关无额外标识；宠物私密的用户也可被关注
- **FR-140.2** 按钮三态：Ikuti（未互相关注）/ Ikuti Balik（对方关注我、我未关注）/ Mengikuti（我已关注，再点即取关、不二次确认）
- **FR-140.3** 常驻 Mengikuti 只在对方主页与我自己的 Following 列表；其他位置（Feed 卡、详情、别人的列表、我的 Followers 列表、通知新粉丝条目）已关注不显示按钮；在这些位置点关注后本按钮变 Mengikuti 可立即取关、刷新后隐藏，同一用户其他位置的按钮立即隐藏（App 全局关注状态）
- **FR-140.4** 我的页与他人主页「居中名片」：头像 → 昵称 → 签名 →（他人）加入时间 → 一行四格 Postingan / Suka / Mengikuti / Pengikut（后两格可点进列表对应 Tab）→ 整宽主按钮（我：Edit Profil；他人：三态）→ 宠物区（我：Hewanku 一行卡；他人：Hewan 圆形头像一排、右上 diary 数）→（我）Aktivitas · Pesanan Saya → 帖子区（Postinganku / Postingan）；帖子区表头不再重复数量
- **FR-140.5** 宠物只显示物种不显示品种；本版本只展示一只宠物，组件按列表实现；无宠物不显示宠物区；无公开帖子时帖子 / 获赞为 0、帖子区现有空态；我的页不显示邮箱
- **FR-140.6** 我的页四格帖子数 / 获赞数按我的全部帖子（D-5）；他人主页只算公开帖子
- **FR-140.7** 他人主页关注 / 取关后 Pengikut 即时 ±1
- **FR-140.8** Feed 卡与帖子详情头部：发帖人昵称右侧关注按钮、再右「…」；内容类型标签移到发布时间右侧；只对未关注者显示；自己的帖子不显示
- **FR-140.9** 关注列表页：标题 = 主页用户昵称；Tab Mengikuti / Pengikut；行 = 头像 + 昵称 + 按钮；无「⋮」；长昵称省略；点行进对方主页；可看别人的列表
- **FR-140.10** 我的 Following 列表常驻 Mengikuti，取关后行不消失、按钮变 Ikuti / Ikuti Balik，刷新后消失；我的 Followers 与别人的列表按我与此人关系显示（未关注 Ikuti / Ikuti Balik，已关注无按钮，我自己那行无按钮，我拉黑的人无按钮）
- **FR-140.11** 只有看自己的列表且当前 Tab 为空时显示「Disarankan untukmu」推荐（发过 PUBLIC 内容的账号，虚拟号 + 真人，近 30 天获赞倒序，最多 30，排除自己 / 已关注 / 已拉黑 / 注销封号）；别人的空列表只显示空态
- **FR-140.12** 任何取消关注（主动、拉黑导致）不发任何通知或推送
- **FR-140.13** 拉黑：A 拉黑 B 同步取消 A→B 关注（计数相应减）、B 不知道；B→A 保留；B 看 A 主页关注 / 粉丝数显示真实值、帖子与内容沿用伪装；拉黑期间 A 不能关注 B；A 的 Followers 里 B 那行无按钮；解除拉黑不恢复；REPORT 不影响关注
- **FR-140.14** B 拉黑 A 后 A 关注 B 照常成功，B 不收新粉丝通知（D-4）
- **FR-140.15** 注销 / 封号用户的主页显示「Pengguna tidak ditemukan」
- **FR-140.16** 计数缩写 ≥ 1.000 用 K、≥ 1.000.000 用 M（D-3）
- **FR-140.17** 频率限制：每分钟 30 次、每日新增 500 个，超出提示「Terlalu sering. Coba lagi nanti.」；网络失败按钮回滚 + toast
- **FR-140.18** 虚拟号可被关注、出现在列表；不主动关注真人
- **FR-140.19** 游客点关注 → 登录弹窗，登录后不自动关注
- **FR-140.20** 不做关注 Feed、不影响推荐算法、不做私密账号、不做关注宠物、不做列表搜索与行内菜单
- **FR-140.21** 埋点 `user_followed` / `user_unfollowed {target_user_id, source, is_follow_back}`、`follow_list_viewed {tab, is_self, is_empty}`、`suggested_follow_shown`（D-6）

**FR-141 通知中心优化**

- **FR-141.1** 不放图片：左侧沿用类型图标；新增 `USER_FOLLOWED`（person_add 紫）/ `PLACE_COMMENTED`（location_on 打卡色）图标
- **FR-141.2** 未读样式沿用浅紫底 + 紫色圆点，圆点移到类型图标右上角；点开后白底；全部已读沿用
- **FR-141.3** 五段分组 Hari ini / Kemarin / 7 hari terakhir / 30 hari terakhir / Sebelumnya，按手机本地时间；今天内「N menit / jam lalu」，其余「8 Okt, 15:57」
- **FR-141.4** 推送文案与通知中心条目一致（同语言、同触发人、同人数），后端同一模板拼
- **FR-141.5** 文案（UI 稿新增文案表）：点赞「{nama} (dan {n} lainnya) menyukai postinganmu」、评论「{nama} mengomentari postinganmu」+ 评论原文、回复「{nama} membalas komentarmu」+ 原文（共用 CONTENT_COMMENTED）、场所评论「{nama} (dan {n} lainnya) mengomentari tempat yang kamu tambahkan」（无评论内容）、新粉丝「{nama} mulai mengikutimu」；只加粗触发人昵称
- **FR-141.6** 点击：举报已处理无动作；评论 / 回复 / 点赞进帖子；场所评论进场所；新粉丝进对方主页；成就进里程碑页；帖子已删提示「Konten ini sudah tidak ada」
- **FR-141.7** 聚合：点赞与场所评论「同对象未读即合并」（最新触发人、人数 +1、时间刷新移到最上，已读后新起一条）；评论 / 回复、新粉丝不合并
- **FR-141.8** 推送去抖：同帖点赞、同场所评论 10 分钟内最多一条（D-7），文案取推送时刻聚合结果；评论 / 回复 / 关注每次推送
- **FR-141.9** 「and N others」不含已拉黑、已注销；取消点赞不回退
- **FR-141.10** 新粉丝条目未回关时右侧「Ikuti Balik」浅紫小按钮，点击直接回关（不进主页），变 Mengikuti 可立即取关，刷新后隐藏
- **FR-141.11** `USER_FOLLOWED`：同一人取关后再关注 24 小时内不重复通知
- **FR-141.12** `PLACE_COMMENTED`：他人在我创建的场所下评论（审核通过后）通知创建者；打卡 / 收藏不通知；创建者自评不通知
- **FR-141.13** 评论已删除时正文显示「Komentar telah dihapus」；存量无触发人通知按旧通用文案显示，不回填
- **FR-141.14** 评论内容进推送前已过审核（被拦截的评论不发通知）
- **FR-141.15** 其余系统通知只跟随新分组与未读样式，文案图标不变；不做分类 Tab、不做按类型推送开关
- **FR-141.16** 埋点：`app_notification_item_tapped` 加 `actor_count`；新增 `notification_follow_back_tapped`

### NonFunctional Requirements

- **NFR1 一致性**：关注状态在任一处操作后，其他页面一致；刷新后与服务端一致
- **NFR2 不暴露拉黑**：被拉黑方从计数、列表、通知、关注结果中都无法察觉
- **NFR3 性能**：主页 / Feed / 通知列表的关注态与触发人一律批量查询，禁止逐条
- **NFR4 计数正确**：关注 / 取关 / 拉黑取关 / 注销后两端计数正确
- **NFR5 防刷**：关注频率限制
- **NFR6 约束安全**：`ck_notifications_type` DROP + ADD 全集 34 值（D-1）；Flyway 时间戳命名
- **NFR7 回归**：Feed 卡头部改版回归长昵称、运营顶置角标、本人待审帖
- **NFR8 标识**：新接口用不可枚举 token（D-2）

### Additional Requirements

（来自 `architecture-v1.3.4-follow-delta.md`）

- **AR1（AD-1）** `user_follows` + `users.following_count / follower_count` 冗余计数；注销先删关系并扣对端计数
- **AR2（AD-2）** `users.public_token` + 回填；下发到主页 / me / Feed / 详情 / 列表 / 新粉丝通知
- **AR3（AD-3）** `notifications` 加 `aggregate_key` / `comment_id` / `variant` / `updated_at`；新表 `notification_actors`；未读聚合部分唯一索引；CHECK 34 值；枚举末尾按序加 `OPS_ANNOUNCEMENT` / `USER_FOLLOWED` / `PLACE_COMMENTED`
- **AR4（AD-4）** 关注接口 PUT / DELETE `/users/{token}/follow`；拒绝规则；30 / 分钟 + 500 / WIB 日；列表 keyset；推荐接口
- **AR5（AD-5）** 拉黑同事务删 A→B 关注
- **AR6（AD-6）** 主页 / me / Feed / 详情 DTO 扩字段；封号按已注销处理
- **AR7（AD-7）** App `followStateProvider` 全局覆盖与「本按钮保留、其余隐藏」规则
- **AR8（AD-8）** 页面改版；`formatCompactCount`
- **AR9（AD-9）** `sendInteraction` 聚合写入；场所评论审核通过事件
- **AR10（AD-10）** 推送同模板；点赞 / 场所评论 10 分钟 Redis 去抖
- **AR11（AD-11）** 列表读取时批量拼文案；DTO 加 `actorName` / `actorCount` / `actorUserId` / `actorToken` / `actorIFollow`
- **AR12（AD-12）** App 通知中心五段 / 时间格式 / 圆点 / 新图标 / 回关按钮 / 路由
- **AR13（AD-13）** 埋点与前缀登记
- **AR14（AD-14）** 枚举三个新值两分支逐字相同地追加；`OPS_ANNOUNCEMENT` 本主题只占位
- **AR15（AD-15）** 推翻「不做关注」的注释与测试
- **AR16** 迁移 4 个（AD §9）

### UX Design Requirements

（来自 `ui-v1.3.4-follow.html`，定稿 36 屏）

- **UX-DR1（A1）我的页**：头像 84 居中（相机角标）→ 昵称 → 签名 → 四格（tabular 数字）→ 整宽「Edit Profil」→ Hewanku（头像 · 名字 · 物种 · 年龄 · diary 标签）→ Aktivitas → Postinganku 网格；无邮箱
- **UX-DR2（A2 ~ A7）他人主页**：同布局 + 加入时间灰字；整宽 Ikuti / Ikuti Balik（实心紫）/ Mengikuti（白底描边）；Hewan 圆形头像 + 右上 diary 数；无宠物省区；无帖子用现有空态；被拉黑方视角伪装 + 真实关注数
- **UX-DR3（A8 ~ A11）**：注销 / 封号「Pengguna tidak ditemukan」整页；游客 `LoginHardDialog`；频繁 toast；失败回滚 + toast
- **UX-DR4（B1 ~ B10）关注列表**：复用拉黑列表行（LetterAvatar 40 + 名字 15/w600 + 按钮、1px 分隔）；Tab 不带数量；行内浅紫小按钮（高 30、圆角 8、12 号字、最小宽 88；Ikuti / Ikuti Balik 浅紫底紫字，Mengikuti 白底灰框）；空态 + 推荐（浅紫提示条 + 图标 +「Disarankan untukmu」小节标题）；骨架 / 错误态复用拉黑列表
- **UX-DR5（C1 ~ C7）Feed / 详情**：文字按钮「+ Ikuti」/「+ Ikuti Balik」（紫 13 号、无底无框、热区 44），点后灰字「Mengikuti」；类型徽章移到时间右侧；其余尺寸不变
- **UX-DR6（D1 ~ D8）通知中心**：五段分组标题；标题只加粗昵称；评论原文正文行；新粉丝浅紫小按钮（未读行用白底）；圆点在图标右上；新类型图标规格（40×40 圆角 11）；推送标题 = 条目第一行、正文 = 第二行

### FR Coverage Map

| FR | Epic |
|---|---|
| FR-140.1 ~ 140.2、140.4 ~ 140.7、140.12 ~ 140.19 | Epic 1（关注能力 + 主页改版 + 拉黑联动 + 限流 + 游客） |
| FR-140.3、140.8 ~ 140.11 | Epic 2（Feed / 详情入口、关注列表、推荐、全局关注状态） |
| FR-140.20 | Epic 1 ~ 2（不做项，各 story 回归） |
| FR-140.21 | Epic 1 ~ 2（埋点随入口落地） |
| FR-141.1 ~ 141.16 | Epic 3（通知中心改版；新粉丝通知依赖 Epic 1） |

## Epic List

### Epic 1: 关注一个人——主页上的关注与新主页
用户在他人主页可以关注 / 取关（三态按钮、粉丝数即时变化），我的页与他人主页换成「居中名片」并显示帖子 / 获赞 / 关注 / 粉丝四格；拉黑会同步取消我对对方的关注且不暴露；注销 / 封号用户显示不存在。
**FRs covered:** FR-140.1、140.2、140.4 ~ 140.7、140.12 ~ 140.19、140.21（主页入口部分）
**依赖：** 无。含关注表、用户 token、计数、关注接口与限流、主页接口扩字段。

### Epic 2: 到处都能关注，也能看关注了谁
Feed 卡与帖子详情头部出现关注按钮（只对未关注者），点关注后各处状态一致；我和别人的关注 / 粉丝列表可看、可关注，自己的空列表给出推荐关注。
**FRs covered:** FR-140.3、140.8 ~ 140.11、140.21（列表 / Feed / 推荐部分）
**依赖：** Epic 1（关注接口、token、计数）。

### Epic 3: 通知中心说清楚「谁做了什么」
互动通知写明触发人、同帖点赞与同场所评论在未读时合并、新增「新粉丝」与「场所评论」两种通知、按五段时间分组、推送与列表同文案；新粉丝条目可直接回关。
**FRs covered:** FR-141.1 ~ 141.16
**依赖：** Epic 1（`USER_FOLLOWED` 由关注触发、回关按钮用关注接口）。通知表扩展与 CHECK 重建（34 值，D-1）在本 epic 首条 story。

---

## Epic 1: 关注一个人——主页上的关注与新主页

（FR-140.1 / .2 / .4 ~ .7 / .12 ~ .19 / .21；AD-1、AD-2、AD-4 ~ AD-6、AD-8、AD-15）

### Story 1.1: 关注关系、用户 token 与关注接口

As a 用户,
I want 关注 / 取关另一个用户,
So that 我能和喜欢的人建立持续的关系。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看库表
**Then** 存在 `user_follows(follower_id, followee_id, created_at, PK(follower_id, followee_id))`（两 FK → users ON DELETE CASCADE、CHECK 不自关注、`(followee_id, created_at DESC)` 与 `(follower_id, created_at DESC)` 索引）；`users` 新增 `following_count` / `follower_count`（NOT NULL DEFAULT 0）与 `public_token VARCHAR(32) NOT NULL UNIQUE`（存量随机 base62 回填，新注册用户生成）（AD-1、AD-2）

**Given** 登录用户 A 与用户 B（B 的 token）
**When** `PUT /api/v1/users/{tokenB}/follow`
**Then** 建立 A→B 关注，A.following_count 与 B.follower_count 各 +1（同事务）；返回 `{ iFollow: true, followsMe, followerCount }`；重复调用幂等、计数不重复加
**When** `DELETE /api/v1/users/{tokenB}/follow`
**Then** 删除关系、两端计数 −1（不低于 0）；幂等；**不发任何通知或推送**（FR-140.12）

**Given** 关注自己 / A 拉黑了 B / B 已注销、封号或 token 不存在
**When** 关注
**Then** 分别 422 / 409 `follow-blocked` / 404 `not-found`；`REPORT` 来源的隐藏不影响
**And** B 拉黑了 A 时 A 关注 B 照常成功、B 粉丝数 +1（D-4）

**Given** 同一用户 1 分钟内第 31 次关注操作，或当日（WIB 自然日）新增关注达 500 后再关注
**When** 请求
**Then** 429 `rate-limited`（`rl:social:follow:{userId}` 30 / 分钟；每日上限按 `user_follows` 当日行数计）（FR-140.17）

**Given** 用户注销
**When** 注销完成
**Then** 其全部关注 / 粉丝关系被删除，所有对端的计数同步扣减（AD-1，NFR4）

**Given** 本 story
**When** 交付
**Then** 不发 `USER_FOLLOWED` 通知（Epic 3 Story 3.2 接入）；集成测试覆盖关注 / 取关 / 幂等 / 计数 / 拒绝规则 / 限流 / 注销扣减

### Story 1.2: 拉黑联动与主页接口返回关注信息

As a 用户,
I want 主页显示关注 / 粉丝数与我们的关注关系，拉黑某人时我对他的关注自动取消,
So that 我能看到彼此关系，拉黑后也不会继续关注对方。

**Acceptance Criteria:**

**Given** A 关注了 B
**When** A 拉黑 B（`UserHideRelationService.block`）
**Then** 同事务删除 A→B 关注、两端计数扣减；B→A 关注保留；不发任何通知（AD-5，FR-140.13）
**And** 解除拉黑不恢复 A→B

**Given** 查看他人主页 `GET /api/v1/users/{userId}/profile`
**When** 返回
**Then** `PublicProfileResponse` 新增 `userToken`、`followingCount`、`followerCount`、`iFollow`、`followsMe`（游客两布尔为 false）（AD-6）

**Given** B 被 A 拉黑、B 查看 A 的主页
**When** 返回
**Then** 帖子数 / 获赞数仍为 0、帖子为空（现有伪装），**关注数与粉丝数为真实值**；B 仍关注 A 时 `iFollow = true`

**Given** 目标用户已封号（`status = DEACTIVATED`）或已注销
**When** 查看主页
**Then** 返回 `deactivated()`（App 显示「Pengguna tidak ditemukan」）（FR-140.15）

**Given** `/api/v1/me`
**When** 返回
**Then** 新增 `userToken`、`postCount`（我的全部帖子，含私密）、`likeCount`（我全部帖子的获赞）、`followingCount`、`followerCount`（D-5）

**Given** 本 story
**When** 交付
**Then** `PublicProfileControllerTest`、`UserHideRelationIntegrationTest`、`/me` 契约测试按新字段更新；`FeedService.java:35`「无关注」注释更新（AD-15）

### Story 1.3: 他人主页改版与关注按钮

As a 用户,
I want 在别人的主页一眼看到他的帖子、获赞、关注、粉丝，并能关注他,
So that 我可以决定要不要持续关注这个人。

**Acceptance Criteria:**

**Given** 他人主页
**When** 渲染
**Then** 按 UI 稿 A2「居中名片」：头像 84 → 昵称 → 签名 → 加入时间（灰字）→ 四格 Postingan / Suka / Mengikuti / Pengikut（tabular 数字，`formatCompactCount`：≥ 1.000 → K、≥ 1.000.000 → M，D-3）→ 整宽按钮 → Hewan（圆形头像 + 右上 diary 数 + 名字 + 物种 · 年龄，点头像进宠物档案；本版本一只、组件按列表）→ Postingan 网格；帖子区表头不再显示数量（UX-DR2）

**Given** 按钮三态
**When** 我未关注且对方未关注我 / 对方关注我 / 我已关注
**Then** 分别「Ikuti」（实心紫）/「Ikuti Balik」（实心紫）/「Mengikuti」（白底描边）；点 Ikuti(Balik) → 关注、Pengikut 即时 +1；点 Mengikuti → 立即取关（无确认）、Pengikut −1（FR-140.2 / 140.7）

**Given** Mengikuti / Pengikut 两格
**When** 本 story 完成
**Then** 暂不可点（关注列表页由 Story 2.2 交付并接上点击）

**Given** 无宠物 / 无公开帖子 / 被拉黑方视角 / 注销封号
**When** 渲染
**Then** 分别隐藏 Hewan 区 / 帖子与获赞为 0 + 现有空态 / 伪装 + 真实关注数 + 按钮按真实关系 / 整页「Pengguna tidak ditemukan」（UI 稿 A5 ~ A8）

**Given** 游客点「Ikuti」
**When** 触发
**Then** 弹 `LoginHardDialog`，登录后不自动关注（FR-140.19）

**Given** 关注失败（网络）/ 频繁（429）
**When** 返回
**Then** 按钮回滚到点击前 + toast「Gagal mengikuti, coba lagi」/「Gagal berhenti mengikuti, coba lagi」/「Terlalu sering. Coba lagi nanti.」（UI 稿 A10 / A11）

**Given** 关注 / 取关成功
**When** 上报
**Then** `user_followed` / `user_unfollowed {target_user_id, source: profile, is_follow_back}`（D-6）

**Given** 既有测试
**When** 本 story 完成
**Then** `public_profile_posts_test` 的 `follow` 禁词与 FR-118.7「明确不做关注」注释按新需求改写；`mini_profile_test` 中「无 Follow」断言按现状调整（AD-15）；新增 `formatCompactCount` 单测

### Story 1.4: 我的页改版为居中名片

As a 用户,
I want 我的页也显示帖子、获赞、关注、粉丝,
So that 我知道自己的影响力，也能从这里看关注列表。

**Acceptance Criteria:**

**Given** 我的页
**When** 渲染
**Then** 按 UI 稿 A1：头像 84 居中（相机角标，换头像行为不变）→ 昵称 → 签名 → 四格（我的全部帖子数 / 全部获赞 / 关注 / 粉丝，D-5，`formatCompactCount`）→ 整宽「Edit Profil」→ Hewanku（自己的宠物一行卡：头像 · 名字 · 物种 · 年龄 · diary 小标签）→ Aktivitas「Pesanan Saya」→ Postinganku 网格（UX-DR1）
**And** 不显示邮箱（PRD OQ-6）；宠物只显示物种不显示品种

**Given** 无宠物 / 无帖子
**When** 渲染
**Then** 宠物区按现有引导卡逻辑；帖子区现有空态

**Given** Mengikuti / Pengikut 两格
**When** 本 story 完成
**Then** 暂不可点（Story 2.2 接上点击）

**Given** 既有测试
**When** 本 story 完成
**Then** `me_page_test` 按新布局更新（邮箱断言移除、四格新增）

---

## Epic 2: 到处都能关注，也能看关注了谁

（FR-140.3 / .8 ~ .11 / .21；AD-4、AD-6、AD-7、AD-8）

### Story 2.1: Feed 卡与帖子详情的关注按钮，全局关注状态

As a 刷首页的用户,
I want 在帖子上直接关注发帖人,
So that 遇到喜欢的人不用先进主页。

**Acceptance Criteria:**

**Given** Feed 列表与帖子详情接口
**When** 返回
**Then** `FeedItemResponse` / `ContentDetailResponse` 增加 `authorToken` 与 `authorFollowState { iFollow, followsMe }`，每页一次批量查询，游客恒 false（AD-6，NFR3）

**Given** App 全局关注状态 `followStateProvider`（按 userId，登录态切换重建）
**When** 任一处关注 / 取关
**Then** 记录覆盖值；各处按钮读「服务端初值 + 覆盖」；刷新数据后覆盖清除（AD-7，NFR1）

**Given** Feed 卡 / 详情头部，发帖人未被我关注且不是我自己
**When** 渲染
**Then** 昵称行右侧（「…」左侧）文字按钮「+ Ikuti」（对方关注我时「+ Ikuti Balik」），紫 13 号无底无框、热区 44；内容类型徽章从昵称后移到发布时间右侧；头像 / 昵称 / 时间尺寸不变（UX-DR5，FR-140.8）
**And** 已关注者、自己的帖子不显示按钮

**Given** 在某张卡点「+ Ikuti」
**When** 成功
**Then** 该卡按钮变灰字「Mengikuti」可立即再点取关；同一作者在 Feed 其他卡片与其他页面的按钮立即隐藏；下拉刷新后该卡按钮也隐藏（FR-140.3）
**And** 失败 / 429 回滚 + toast；游客弹登录、登录后不自动关注

**Given** 首页回归
**When** 长昵称 / 运营顶置卡 / 本人待审帖
**Then** 头部不溢出、顶置角标与待审标识不受影响（NFR7）

**Given** 关注 / 取关
**When** 上报
**Then** `user_followed` / `user_unfollowed`，`source` = `feed_card` / `content_detail`

### Story 2.2: 关注 / 粉丝列表

As a 用户,
I want 查看我和别人关注了谁、被谁关注，并在列表里关注,
So that 我能发现更多值得关注的人。

**Acceptance Criteria:**

**Given** 列表接口 `GET /api/v1/users/{token}/following?cursor=` 与 `…/followers?cursor=`
**When** 请求
**Then** keyset 分页（`created_at DESC`，每页 20）；行 `{ userId, userToken, nickname, avatarUrl, deleted, iFollow, followsMe, blockedByMe, self }`，关系相对当前查看者（游客全 false）；已注销用户不在列表（CASCADE）；封号用户照常列出（AD-4）

**Given** 我的页与他人主页的 Mengikuti / Pengikut 两格（Story 1.3 / 1.4 交付时不可点）
**When** 本 story 完成
**Then** 两格可点，进入该用户的关注列表并定位到对应 Tab（FR-140.4）

**Given** 列表页
**When** 渲染
**Then** 标题 = 主页用户昵称；Tab「Mengikuti / Pengikut」（不带数量），从四格进入定位到对应 Tab；行复用拉黑列表样式（LetterAvatar 40 + 名字 15/w600 + 右侧浅紫小按钮：高 30、圆角 8、12 号字、最小宽 88）；长昵称省略；**无「⋮」**；点行进入对方主页（UX-DR4，FR-140.9）

**Given** 我自己的 Mengikuti 列表
**When** 渲染 / 点「Mengikuti」
**Then** 每行常驻「Mengikuti」（白底灰框），点即取关，该行保留、按钮变「Ikuti」/「Ikuti Balik」，下拉刷新后该行消失（FR-140.10）

**Given** 我的 Pengikut 列表、别人的任一列表
**When** 渲染
**Then** 按我与此人的关系：未关注「Ikuti」/ 对方关注我「Ikuti Balik」/ 已关注无按钮 / 我自己那行无按钮 / 我拉黑的人无按钮；点关注后按 Story 2.1 全局规则（本按钮变 Mengikuti 可取消，其他位置隐藏）

**Given** 别人的列表为空
**When** 渲染
**Then** 只显示空态（「Belum mengikuti siapa pun」/「Belum ada pengikut」），不显示推荐

**Given** 加载中 / 失败
**When** 渲染
**Then** 复用拉黑列表骨架与错误态（「Gagal memuat daftar」+「Coba lagi」）

**Given** 进入或切换 Tab
**When** 上报
**Then** `follow_list_viewed {tab, is_self, is_empty}`；列表内关注 `source = follow_list`

### Story 2.3: 自己的空列表推荐关注

As a 刚开始用的用户,
I want 我的关注列表为空时看到值得关注的人,
So that 我能快速开始关注。

**Acceptance Criteria:**

**Given** 推荐接口 `GET /api/v1/me/follow-suggestions`
**When** 请求
**Then** 返回最多 30 个：`role = USER`、未注销、`enabled`、`status = ACTIVE`、至少一条 PUBLIC 已发布帖子；排除自己、已关注、我对其有任何隐藏关系；按近 30 天其 PUBLIC 帖子收到的赞倒序；虚拟号与真人都包括（FR-140.11，AD-4）

**Given** 我自己的 Mengikuti 或 Pengikut Tab 为空
**When** 渲染
**Then** 浅紫提示条 + 图标 + 空态文字（「Kamu belum mengikuti siapa pun」/「Belum ada pengikut」）+ 小节标题「Disarankan untukmu」+ 推荐列表（行同列表样式，按钮「Ikuti」）（UI 稿 B4 / B5）

**Given** 在推荐里点「Ikuti」
**When** 成功
**Then** 该行变「Mengikuti」可立即取消；刷新后该账号从推荐中消失（UI 稿 B6）

**Given** 推荐出现 / 关注
**When** 上报
**Then** `suggested_follow_shown`；关注 `source = suggested`

---

## Epic 3: 通知中心说清楚「谁做了什么」

（FR-141.1 ~ 141.16；AD-3、AD-9 ~ AD-14）

### Story 3.1: 通知数据模型扩展与点赞 / 评论通知带触发人、点赞合并

As a 发帖用户,
I want 点赞和评论通知告诉我是谁、同一帖子的多个赞合成一条,
So that 通知不再是一串看不出内容的「有人赞了你」。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看库表
**Then** `notifications` 新增 `aggregate_key`、`comment_id`、`variant`、`updated_at`；新表 `notification_actors(notification_id, actor_user_id, acted_at, PK)`（两 FK CASCADE）；部分唯一索引 `uq_notifications_unread_aggregate (recipient_user_id, aggregate_key) WHERE is_read = false AND aggregate_key IS NOT NULL`（AD-3）
**And** `ck_notifications_type` DROP + ADD 全集 34 值（现有 31 + `OPS_ANNOUNCEMENT` + `USER_FOLLOWED` + `PLACE_COMMENTED`），开工当天对全部远端分支并集核查并在迁移注释记录；`NotificationType` 枚举末尾按序追加 `OPS_ANNOUNCEMENT`、`USER_FOLLOWED`、`PLACE_COMMENTED`（与 app 主题逐字相同，`MentionNotifyTest` 两边都成立）（D-1，NFR6）

**Given** `NotificationService.sendInteraction(...)`
**When** 同一帖子（`aggregate_key = LIKE:POST:{postId}`）有未读点赞通知时又被点赞
**Then** 不新建行：追加 actor（同人不重复）、时间刷新移到最上、未读角标不再 +1；已读后再有新赞 → 新建一条（FR-141.7，AD-9）
**And** 取消点赞不回退

**Given** 评论 / 回复
**When** 审核通过发通知
**Then** 每条单独一行，带 actor、`comment_id`；回复一级评论作者时 `variant = REPLY`；自评不发、隐藏关系照旧抑制

**Given** 推送
**When** 发送点赞 / 评论 / 回复推送
**Then** 文案用后端模板按接收方语言拼（点赞「{nama} (dan {n} lainnya) menyukai postinganmu」、评论 / 回复 + 评论原文），与列表同模板（FR-141.4）；同帖点赞推送 10 分钟内最多一条（Redis `NX EX 600`），评论 / 回复每次推送（FR-141.8）

**Given** 本 story
**When** 交付
**Then** `ContentNotifyListenerTest`、`NotificationServiceTest` 更新；并发同帖点赞不产生两条未读（索引 + 冲突重试）

### Story 3.2: 新粉丝与场所评论两种新通知

As a 用户,
I want 有人关注我、有人在我创建的场所下评论时收到通知,
So that 我能及时回关、看到别人对我推荐的地方的反馈。

**Acceptance Criteria:**

**Given** A 关注 B（Story 1.1 的关注成功后）
**When** 关注行新建
**Then** 给 B 发 `USER_FOLLOWED`（actor = A，不聚合，每次推送，文案「{nama} mulai mengikutimu」）；24 小时内同一 A→B 已有该通知则不再发；B 隐藏了 A（含 B 拉黑 A，D-4）则不发；取关不发（FR-141.11）

**Given** 他人在场所 P 下的评论审核通过（`PlaceCommentService.approve`）
**When** 评论人 ≠ P 的创建者（`marked_by_user_id`）、创建者未注销、未隐藏评论人
**Then** 发布 `PlaceCommentApprovedEvent`，给创建者发 `PLACE_COMMENTED`（`aggregate_key = COMMENT:PLACE:{placeId}`、未读即合并、`target_ref = placeToken`），文案「{nama} (dan {n} lainnya) mengomentari tempat yang kamu tambahkan」不含评论内容（FR-141.12）
**And** 同场所推送 10 分钟内最多一条（D-7）；打卡、收藏不发；创建者自评不发

**Given** 本 story
**When** 交付
**Then** 集成测试覆盖：24 小时去重、拉黑抑制、场所评论合并与去抖、创建者自评、审核未通过不发

### Story 3.3: 通知列表返回拼好的文案与触发人

As a 用户,
I want 通知中心显示「谁 + 做了什么」以及合并人数,
So that 我不用点开就知道发生了什么。

**Acceptance Criteria:**

**Given** `GET /api/v1/notifications`
**When** 条目为点赞 / 评论 / 新粉丝 / 场所评论且有 actor 行
**Then** 按接收方语言用同一模板现拼 `title` / `body`；新增字段 `actorName`、`actorCount`、`actorUserId`、`actorToken`、`actorIFollow`（AD-11）
**And** 最新触发人与 N 取「未注销、未被我拉黑」的 actor；N 为其余同样过滤后人数（FR-141.9）

**Given** 评论 / 回复对应的评论已删除
**When** 拼文案
**Then** 正文为「Komentar telah dihapus」/「Comment deleted」（FR-141.13）

**Given** 上线前的存量互动通知（无 actor 行）
**When** 返回
**Then** 原样返回，App 按旧通用文案显示（不回填）

**Given** 一页 20 条
**When** 拼文案
**Then** actor、昵称、评论状态、新粉丝关注态各一次批量查询，禁止逐条（NFR3）；`NotificationCenterServiceTest` 覆盖查询次数

### Story 3.4: App 通知中心改版

As a 用户,
I want 通知按时间分段、写清是谁、可以直接回关,
So that 通知值得点开。

**Acceptance Criteria:**

**Given** 通知中心
**When** 渲染
**Then** 按手机本地时间分五段 Hari ini / Kemarin / 7 hari terakhir / 30 hari terakhir / Sebelumnya；今天内「N menit / jam lalu」，其余「8 Okt, 15:57」（FR-141.3）

**Given** 有后端拼好文案的类型
**When** 渲染
**Then** 标题用 `title`、只加粗 `actorName`；正文用 `body`（评论原文单行截断）；不显示任何头像 / 帖子图 / 场所图；存量无 actor 条目显示原通用文案（FR-141.5 / 141.13，UX-DR6）

**Given** 未读条目
**When** 渲染
**Then** 浅紫底 `cream2` + 紫色圆点位于类型图标右上角；点开变白底、圆点消失；「全部已读」沿用（FR-141.2）

**Given** 新类型
**When** 渲染
**Then** `USER_FOLLOWED` 图标 `person_add`（紫 #845EC9 / 底 #EFE6FF），`PLACE_COMMENTED` 图标 `location_on`（#1B7F8C / 底 #E6F5F7），40×40 圆角 11（FR-141.1）

**Given** 新粉丝条目且我未回关（`actorIFollow = false`）
**When** 渲染 / 点「Ikuti Balik」
**Then** 右侧浅紫小按钮（未读行用白底）；点击直接回关、不进主页，按钮变「Mengikuti」可立即取关；刷新后隐藏；全局关注状态同步（FR-141.10）

**Given** 点条目
**When** 按类型
**Then** 举报已处理无动作；点赞 / 评论 / 回复进帖子（评论类定位评论区）；场所评论进场所详情（`target_ref` = 场所 token）；新粉丝进对方主页（`actorUserId`）；成就进里程碑页；帖子已删 toast「Konten ini sudah tidak ada」；`deep_link_routes` 补两种新类型（FR-141.6）

**Given** 上报
**When** 点条目 / 回关
**Then** `app_notification_item_tapped` 加 `actor_count`；新增 `notification_follow_back_tapped`；回关另报 `user_followed {source: notification}`（FR-141.16）

**Given** 其余系统通知
**When** 渲染
**Then** 只跟随新分组与未读样式，文案图标不变；既有通知中心测试按新分组更新
