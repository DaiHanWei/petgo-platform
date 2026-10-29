# 对抗式评审：PRD-video（短视频第一版）

- 评审日期：2026-10-16
- 评审对象：`PRD-video.md`、`addendum-video.md`、`决策日志-video.md`（同目录）
- 方法：逐条对照仓库现状（`research/video` 分支，HEAD `8ffba7cd`）只读核实。决策 V-0 ~ V-23 视为最终决定，不推翻；只指出这些决策带来的后果 PRD 没有处理的地方。
- 结论：**现在不能进入架构和 story 拆分。** 核心角色「官方号」与代码里的事实冲突，冲突解决前 UJ-1 走不通（见 C-1）。其余问题多数是覆盖面不全或口径不清，补写 PRD 即可，不需要改方向。

| 严重度 | 数量 |
|---|---|
| critical | 1 |
| high | 5 |
| medium | 10 |
| low | 6 |

---

## Critical

### C-1　「官方号」在代码里是不能登录 App 的作者 shim，UJ-1 和免审规则（V-22）都落不了地
- **PRD 位置**：§3 术语「官方号」（"沿用 1.3.4 默认关注官方号中的官方号概念"）；UJ-1（"Sari 用官方号登录 App"）；FR-10 第 4、5 条；§8.2；addendum D（"复用 1.3.4 官方号配置"）
- **问题**：1.3.4 的「官方号」不是一份可维护的名单，而是 `users.role = 'ADMIN'` 的后台内容作者 shim。这种账号**不登录 App**，也不接收推送。运营真正能在 App 里登录的「官方运营号」是普通的 `role=USER` 真实账号。PRD 把这两种账号当成了同一个东西，结果是：
  1. 如果按 1.3.4 的口径（role=ADMIN）判断免审，运营在 App 里登录的号永远不在名单里，**所有官方视频都会被强制送进人工审核**，与 V-22「官方号免审、直接公开」正好相反。
  2. 如果为了进名单把运营号改成 `role=ADMIN`：
     - 所有用户会自动关注它（含存量回填）；
     - 它收不到任何推送；
     - 在 App 里点赞人列表、关注、关注流、关注推荐等端点会直接 403（这些端点都是 `hasRole("USER")`）；
     - 后台用户列表和搜索里也找不到它，FR-2 的「在用户详情页改等级」就没有入口。
  3. 根据 1.3.4 的定义，「官方号名单」根本不存在可配置的形态：没有表，也没有配置项，只能靠改 role。
- **证据**：
  - `petgo-backend/src/main/java/com/tailtopia/auth/service/AccountQueryService.java:158-168`：`isOfficialAccount` 的判据是 `role == ADMIN`，注释写明"它不登录 App"（D-9）
  - `petgo-backend/src/main/java/com/tailtopia/admin/service/AdminBootstrap.java:28-30,89-95`：users(role=ADMIN) 只是"官方内容作者 shim"，由 bootstrap 邮箱创建
  - `petgo-backend/src/main/java/com/tailtopia/auth/service/AuthService.java:101`：App 登录按 `google_sub` 找人或新建，新建的是 USER
  - `petgo-backend/src/main/java/com/tailtopia/content/larksync/LarkContentSyncService.java:9-11`：注释写明"官方运营号是真实注册的账号"，role 为 USER
  - `petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java:195,218,223-227`：likers / follow / following-feed 等端点要求 `hasRole("USER")`
  - `petgo-backend/src/main/java/com/tailtopia/notify/service/NotificationService.java:247`：官方号跳过推送
  - `petgo-backend/src/main/resources/db/migration/V20261014_1600__following_feed_official_follow.sql:31-41`：全员自动关注 role=ADMIN
  - `petgo-backend/src/main/java/com/tailtopia/admin/usermgmt/service/AdminUserService.java:233,267`：后台用户列表和搜索只查 role=USER
- **建议修复**：在 PRD 里新定义一个「视频免审发布者」名单，与 1.3.4 的 role=ADMIN 官方号解耦。这只是为 V-22「按名单判断」定下落地形态，不推翻决策。具体做法：
  - 存储：可以是 users 上的独立布尔字段，或者一张后台配置表；
  - 后台维护：由带审计的后台权限维护，写入审计日志；
  - 名单成员：明确写出首批是哪些 `role=USER` 运营账号；
  - 与 1.3.4 的关系：写明它和 1.3.4 官方号的关系，例如是否也要被默认关注。如果要被默认关注，需要单独决策。
  - 改写 UJ-1、术语表和 addendum D 中"复用 1.3.4 官方号配置"那一句。

---

## High

### H-1　「审核中」和「仅自己可见」的视频，文件本身是公网可读的
- **PRD 位置**：FR-10 第 5 条（名单外的等级 10 账号，审核通过前只有作者可见）；FR-17 / V-21（只在下架、删除时把文件改为不公开）
- **问题**：现有直传在 PUBLIC 域签名时就带上了 `x-oss-object-acl: public-read`，文件落桶的那一刻就公网可读。名单外账号的视频在人工审核期间、被拒绝之后，以及上传了但没发布的视频（FR-9 明确不清理，F21 也不删），都可以通过 URL 直接访问，并且会被 CDN 缓存。「审核通过前只有作者能看到」只在 App 列表层面成立，文件层面不成立。V-21 只处理了下架和删除，没有处理「从来没公开过」的情况。
- **证据**：
  - `petgo-backend/src/main/java/com/tailtopia/shared/media/PresignedUploadService.java:19,61-65`：PUBLIC 域签入 public-read
  - `petgo-backend/src/main/java/com/tailtopia/content/service/ContentService.java:534-565`：挂起帖照常落库，入队
- **建议修复**：二选一，并写进 FR-10 / FR-12：
  - (a) 视频上传时一律签 `private`，发布成功并且状态为 PUBLISHED 后再改成 public-read；进入审核或被拒时保持 private。
  - (b) 承认这个风险。理由是对象 key 有 128 bit 随机段不可枚举，且 V-6 已限定等级 10 只给内部账号。把它写进 §8.2 已知风险，并在 FR-10 加一句"文件层面不保证"。

### H-2　ACL 私有化的触发点只写了「下架 / 删除」，现有 6 条「内容不可见」路径没有逐条覆盖
- **PRD 位置**：FR-17 第 3、4 条；addendum D「下架 / 删除时」
- **问题**：代码里让帖子对他人不可见的路径至少有 6 条，PRD 只点名了运营下架。其余几条是否也要把文件改为不公开、恢复时是否改回公开，都没有写：
  1. 作者本人删除：FR-17 的「删除」是否包含作者删除？语义含糊。
  2. 举报预处置：10 人举报会自动把帖子转回挂起，不删除。FR-17 只说"沿用"。
  3. 人工审核拒绝或超时丢弃：走的是软删。
  4. 封号时批量下架作者的全部内容。
  5. 注销时隐藏作者的全部内容。
  6. 解除举报预处置、恢复上架时，是否改回公开？
- **证据**：`ContentService.java`
  - `:106` `deleteByAuthor`、`:121` `softDelete`
  - `:159-170` `deactivateAuthorContent`（注销联动）
  - `:324` `restore`
  - `:364-372` `applyReportHoldIfPublished`
  - `:385` `releaseReportHold`
  - `:400-418` `discardReview`
  - `:425-436` `takedownAllByAuthor`
  - 现成的挂钩点：`petgo-backend/src/main/java/com/tailtopia/content/event/ContentUnavailableEvent.java`（顶置联动 `ContentPinTakedownListener` 已经在用）
- **建议修复**：在 FR-17 里加一张「帖子状态迁移 → 文件 ACL」表，6 条路径逐行写清楚是改为 private、恢复为 public 还是不动。实现上统一挂在 `ContentUnavailableEvent` 上，外加一个对应的「恢复可见」事件，不要在各个入口分别写。

### H-3　FR-18 列出的过滤范围漏掉了真实存在的列表入口，推荐流的 Redis 序列快照没有被考虑
- **PRD 位置**：FR-18「覆盖范围」；addendum D「旧版过滤 … 写在查询的 WHERE 里」
- **问题**：
  1. **分类 Tab 漏了**：推荐流的「全部」之外，还有 DAILY / GROWTH_MOMENT / KNOWLEDGE 三个按时间倒序的分类 Tab。视频帖属于 DAILY 或 KNOWLEDGE，一定会出现在这两个 Tab 里。
  2. **推荐的多条路径都要过滤**：推荐流有候选池、科普池（eduPool）两条拉取路径，还有一条「降级回落到时间倒序」的路径（Redis 异常时走）。三条都要加过滤，回落路径最容易漏。
  3. **序列快照**：推荐序生成后存在 Redis 里（`feed:seq:u:{userId}:{seed}`），翻页时只读缓存，不重新查询。"写在 WHERE 里"只适用于生成序列时拉候选池那一步。如果开发在读取序列时再过滤，就会出现 PRD 想避免的短页和提前到底。
  4. **其他端点**：详情页之外，点赞人列表和评论列表（`/content-posts/{id}/likers`、`/comments`）对旧版客户端仍然可以访问视频帖。顶置位如果只有一条视频，旧版会看到什么？是空位、换成下一条，还是不显示？PRD 没写。
- **证据**：
  - `petgo-backend/src/main/java/com/tailtopia/content/service/FeedService.java:230-243`（分类 Tab）、`:265-292`（降级回落）
  - `petgo-backend/src/main/java/com/tailtopia/content/rank/FeedRecommendationService.java:312-316`（候选池和科普池）
  - `petgo-backend/src/main/java/com/tailtopia/content/rank/FeedSequenceStore.java:6-24`、`FeedRankCacheKey.java:20`
- **建议修复**：
  - FR-18 的覆盖清单补上：分类 Tab（DAILY / KNOWLEDGE）、推荐降级回落、科普池、点赞人和评论端点，并写明顶置位只有视频时的行为。
  - addendum D 写明：过滤必须在序列生成时（候选池查询）生效，不能在读取序列时过滤。
  - 另外请注意，仓库里已经有「客户端声明能力」的先例：时间线的 `supports=place_checkin` 参数（`profile/service/TimelineCapabilities.java`，`ProfileApiController.java:144`）。版本号判断可以与它并存，架构阶段可以参考。

### H-4　视频帖（日常 / 科普）没有「可见范围」可选，FR-10 要求的是一个不存在的功能
- **PRD 位置**：FR-10 第 2 条"文案、@ 用户、可见范围（公开 / 仅自己）的规则与图文帖相同"
- **问题**：现有 App 只有成长日记（Diary）有「同步到 Moment」开关，关掉后才是 PRIVATE。日常（Moment）和科普（Tips）根本不渲染这个开关，永远公开。视频帖又不能发成长日记（V-12），所以"与图文帖相同"的实际含义是**没有可见范围选项**。开发照 PRD 字面去做，会新增一个原本不存在的「仅自己」选项。而且即使做了，文件也是公开的（见 H-1）。
- **证据**：
  - `petgo_app/lib/features/content/domain/publish_controller.dart:59-63`："仅对 Diary 有意义 —— Moment / Tips 本就公开进 Feed，不渲染该开关"
  - `petgo_app/lib/features/content/presentation/publish_compose_page.dart:904-908`
- **建议修复**：把这一条改成"视频帖一律公开，不提供可见范围选项"，同时删掉 §2 / UJ 里所有隐含「仅自己」的表述。

### H-5　安卓新增 `READ_MEDIA_VIDEO` 权限有 Google Play 政策风险，而且大概率用不上
- **PRD 位置**：FR-6 第 3 条（申请相册访问权限，包含视频）；addendum E（"Android Manifest 加 `READ_MEDIA_VIDEO`"）
- **问题**：Google Play 的「照片和视频权限」政策要求：只有核心功能需要广泛访问媒体的 App 才能声明 `READ_MEDIA_VIDEO`，而且要在 Play Console 提交声明。这个功能只有内部运营号用，不可能算全体用户的核心功能，声明很可能被驳回，连带整个版本无法上架。另外，项目用的 `image_picker` 在 Android 13+ 走系统 Photo Picker，选视频本来就不需要这个权限。
- **证据**：
  - `petgo_app/android/app/src/main/AndroidManifest.xml:5-7`：目前只声明了 READ_MEDIA_IMAGES
  - `petgo_app/pubspec.yaml:68-71`：使用 image_picker
- **建议修复**：FR-6 改成"使用系统照片选择器选择视频，不新增 READ_MEDIA_VIDEO"。L0 冒烟时验证 Android 13+ 不申请权限也能选到视频。iOS 走 PHPicker 同理，`Info.plist` 文案可以不改。

---

## Medium

### M-1　文案是否必填没有写，现有服务端规则会拒绝「只有视频、没有文字」的帖子
- **PRD 位置**：FR-10、FR-12
- **问题**：服务端现有的最低门槛是"文字和图片不能同时为空"。视频帖的 imageUrls 为空，如果文案也留空，会被 422 拒绝，提示"请填写文字或上传图片"。PRD 没有说视频帖的文案是否可以为空。
- **证据**：`ContentService.java:469-473`
- **建议修复**：在 FR-10 写明文案是否必填。如果允许为空，FR-12 要加一条"视频帖满足最低门槛"，并要求同步修改服务端门槛。

### M-2　发帖页上「视频」和「成长日记」类型的互斥没有定义
- **PRD 位置**：FR-5、FR-6、FR-10
- **问题**：FR-5 只定义了视频和图片互斥。没有定义的情况有：
  - 已经选了视频的用户切换到「成长日记」类型时会怎样；
  - 先选了成长日记、再点「视频」会怎样；
  - 从深链 `/publish?preset=growth-calendar` 进入时，类型被预设成了日记。
  
  这些状态组合不写清楚，开发只能自己猜。
- **证据**：`petgo_app/lib/core/router/deep_link_routes.dart:30`、`features/profile/presentation/growth_archive_page.dart:634,1057`、`milestone_list_page.dart:844`
- **建议修复**：加一条可测条件："已选视频时，成长日记类型不可选；当前类型是成长日记时，不显示视频入口。"另外，FR-6 里的「打卡后发帖」和「成长日历」应写成具体的路由或 preset。

### M-3　名单外等级 10 账号的人工审核路径，与现有行为和运营实际情况都对不上
- **PRD 位置**：FR-10 第 5 条；addendum D（"强制入队，不受人工审核总开关影响"）
- **问题**：
  1. PRD 说"与现有「审核中」帖子一致"。但现有挂起帖在作者视角是「已提交成功」，**不显示审核中标签**，App 跳转的也是发布成功页，「去 Feed 看」找不到这条帖子。
  2. 生产环境的人工审核总开关默认关闭，运营平时不一定盯着审核队列。强制入队之后，谁来审、多长时间内审完，PRD 没有说。超时会被丢弃，作者收到 `CONTENT_REVIEW_TIMED_OUT` 通知。
  3. V-6 说等级 10 只给官方号，但 FR-2 允许后台把任何人设成 1–99。这条路径存在的唯一原因，是 FR-2 没有限制。
- **证据**：
  - `ContentService.java:534-537`：注释"作者视角=已提交成功，无「审核中」标签"
  - `petgo-backend/src/main/java/com/tailtopia/content/service/ManualReviewGate.java:7-17`：默认 enabled=false
  - `ContentService.java:400-418`：超时或拒绝都走丢弃
- **建议修复**：二选一：
  - 收窄：FR-2 只允许把名单内的账号设为等级 ≥10，从根上去掉这条路径；
  - 补全：写清楚审核责任人和 SLA、超时后的行为，以及作者端的展示（是否需要新增「审核中」标签）。

### M-4　「视频最低版本」的版本比较会被 stag 包的版本后缀弄崩；1.3.4「已上线」的说法也与事实不符
- **PRD 位置**：§3「旧版客户端 / 视频最低版本」；FR-18；§8.4
- **问题**：
  1. App 上报的 `X-App-Version` 在 stag 包上带 `-stag` 后缀。现有的版本比较函数假定版本号是三段纯数字，直接 `Integer.parseInt`，遇到后缀会抛出 NumberFormatException。
  2. 后端目前完全没有读取这两个请求头的代码。
  3. App 读取包信息失败时不带头，会被当成旧版客户端，视频被**静默隐藏**，用户和运营都察觉不到。
  4. `X-App-Version` 的提交 5ec8a2e4 在 1.3.4 合入 main（afa63397）之后。按项目记录，截至 10-15，1.3.4 生产后端尚未部署。所以 §8.4 的"1.3.4 已上线"不成立。功能本身不受影响，因为真正依赖请求头的只有视频版本本身。但 UJ-3 里"版本更新提醒持续引导 ≤1.3.3 用户升级"所依赖的，是旧版 App 自带的那套更新检查，这一点需要另外核实。
- **证据**：
  - `petgo_app/lib/core/network/client_info_interceptor.dart:8,47-52`
  - `petgo-backend/src/main/java/com/tailtopia/config/service/AppVersionConfigService.java:96-106`
  - 后端 grep `X-App-Version` 结果为空
- **建议修复**：
  - FR-18 写明版本解析规则：先剥掉 `-` 后缀，解析失败按旧版处理并计数告警。
  - §8.4 改为"1.3.4 的视频版本发版时必须带上请求头"。
  - 核实 ≤1.3.3 客户端是否能收到软更新提示；收不到就从 UJ-3 删掉这句承诺。

### M-5　时间线、日历、访客页、分享名片根本不会出现视频帖，FR-14 / FR-18 / addendum D 里有无效范围
- **PRD 位置**：FR-14 位置清单（"访客页、分享名片，以及作者本人的时间线和日历"）；FR-18 覆盖范围（"访客页、时间线"）；addendum D（"访客 / 名片的 `exifStrippedDeliveryUrl`、推荐封面 SQL `image_urls->>0`"）
- **问题**：这些地方只取 `GROWTH_MOMENT`。V-12 规定视频帖不能是成长日记，所以视频帖永远不会出现在这些位置。把它们写进清单，会让开发和测试为不存在的场景写代码、写用例；"如果出现在这些地方"这种条件句也没法验收。
- **证据**：
  - `petgo-backend/src/main/java/com/tailtopia/profile/service/TimelineService.java:234,479,577`
  - `petgo-backend/src/main/java/com/tailtopia/profile/visitor/VisitorProjectionService.java:156,211,242`
  - `petgo-backend/src/main/java/com/tailtopia/content/repository/ContentPostRepository.java:147-160`（`type = 'GROWTH_MOMENT'`）
- **建议修复**：从 FR-14 / FR-18 删掉这四个位置，改成一句"视频帖不进入成长档案相关视图（由 V-12 保证）"。addendum D 的分支清单也相应删掉这两项。

### M-6　SM-4 依赖的列表曝光埋点在客户端不存在；其他几个指标的口径也有缺口
- **PRD 位置**：§7 SM-1、SM-4、SM-5、SM-6；FR-21
- **问题**：
  1. **SM-4**：客户端没有任何列表曝光事件。后端的 `FeedSeenStore` 记录的是「下发即曝光」，用于曝光衰减，并不代表用户真的看到了卡片。如果拿它当分母，点击率会被严重低估。
  2. **SM-1**：分母"当日活跃用户（只统计新版）"取自哪个系统没有写（PostHog 还是日报用的 GA4，两者口径不同）。游客能否播放、是否计入，也没有写。
  3. **SM-6**：「一次打开 App 期间」没有定义（冷启动到退到后台？还是 30 分钟无操作算一次？）。
  4. **SM-5**：分母是「处理开始次数」，会把取消、重新选择、重试都算进去，失败率会被放大。
  5. **FR-21**：「App 被杀掉时下次启动补报」需要把待上报的数据持久化到本地，addendum F 没有覆盖。补报事件的时间戳会落到下一次启动的那一天，造成 SM-3 跨日。
- **证据**：
  - `petgo_app/lib/features/content/` 下没有任何 impression 类 `Analytics.capture`（grep 结果为空）
  - `petgo-backend/src/main/java/com/tailtopia/content/rank/FeedSeenStore.java`
  - `petgo_app/lib/core/analytics/analytics.dart:163`
- **建议修复**：
  - SM-4：要么在本版补「视频卡片可见 ≥50% 持续 ≥500ms」的曝光事件，要么明确把 SM-4 降级为不做（这会影响 V-18 的重启判据，需要同步改）。
  - SM-1：写明数据源和游客口径。
  - SM-6：定义「一次打开」。
  - SM-5：分母改为「去重后进入处理的视频数」或「每次发帖会话」。
  - 补报事件带上原始发生时间。

### M-7　FR-17「下架后所有端立即不可见」，对文件直链做不到；CDN 配置有一个开关会让私有化失效
- **PRD 位置**：FR-17；addendum D「CDN 配置要点」
- **问题**：
  1. CDN 刷新是异步的，通常需要几分钟。这段时间内拿着原链接仍然能访问。所以「立即」只对 App 列表和详情成立，对文件直链不成立。
  2. 阿里云 CDN 的「私有 Bucket 回源」如果开启，CDN 会用授权身份回源，object ACL 改成 private 也挡不住访问，V-21 等于失效。addendum 里没有写「必须关闭」。
  3. 封面缩略图走 `x-oss-process` 并进入缓存键，刷新时只刷原 URL，带参数的缩略图缓存仍然有效。必须按目录或前缀刷新，或者刷新时带上参数。
- **建议修复**：
  - FR-17 改为"App 端立即不可见；文件直链在 CDN 刷新完成后（≤N 分钟）失效"。
  - addendum D 加上「禁止开启私有 Bucket 回源授权」，以及「刷新时覆盖带 `x-oss-process` 的变体」。

### M-8　FR-12 服务端只核对了文件大小，没有核对文件类型；上传类型白名单是共享端点，改了会影响全站
- **PRD 位置**：FR-11、FR-12；addendum D「上传签名」
- **问题**：
  1. 用 HEAD 只核对了大小。时长和宽高都是客户端上报的，服务端没有核对真实对象的 Content-Type 是否为 `video/mp4`（封面是否为 `image/jpeg`）。持有视频凭证的人可以在 mp4 的 key 下存任意字节，然后通过 CDN 分发。
  2. `/api/v1/media/upload-url` 是所有功能共用的端点，目前对未知类型回退到 `.bin` 并照常签发。FR-11 要改成白名单，需要先确认所有调用方，包括旧版 App、兽医端、工单附件。目前 App 内只发现 `image/jpeg` 一处调用。
- **证据**：
  - `petgo-backend/src/main/java/com/tailtopia/shared/media/PresignedUploadService.java:55-57,85-92`
  - `petgo-backend/src/main/java/com/tailtopia/shared/media/web/MediaController.java:30-35`
  - `petgo_app/lib/features/media/domain/media_upload_use_case.dart:107`
  - `MediaObjectKeys.java:37-44`：只校验前缀，不证明对象真实存在
- **建议修复**：
  - FR-12 加一条"HEAD 核对 Content-Type 与扩展名一致"。
  - FR-11 加一条前置条件"上线前盘点 upload-url 的全部调用方及其类型，白名单只对新增的视频类型生效，图片类型行为不变"。

### M-9　封面的尺寸来源和露出位置没有定义，瀑布流卡片比例可能算错
- **PRD 位置**：FR-14 第 3 条；addendum C
- **问题**：现有瀑布流卡片的比例、露出位置（1.3.4 新增的 `imageCropOffsets`）和尺寸回填，都是对齐 `imageUrls` / `imageSizes` 计算的。视频帖的 imageUrls 为空，`video_width/height` 是视频的显示宽高，封面尺寸从哪里来、下发时放在哪个字段、`ImageSizeBackfillService` 跳过视频之后卡片怎么拿到比例，都没有写。露出位置的校验遇到空图片列表时会怎样，也没有写。
- **证据**：
  - `ContentService.java:482`：`cropOffsets.validate(imageUrls, ...)`
  - `ContentService.java:578-583`：尺寸归一与回填
  - `petgo_app/lib/features/content/domain/image_exposure.dart:45-52`
- **建议修复**：addendum C 写明"视频帖的 `imageSizes` 用封面尺寸（等于视频显示宽高）单元素下发，露出位置固定居中，不走选露出位置页"。

### M-10　旧版用户从分享页跳进 App 后看到「内容不存在」，体验是死路
- **PRD 位置**：UJ-3；FR-18 倒数第 2 条；FR-19
- **问题**：旧版用户在 H5 分享页看到视频封面，点「在 App 中观看」后跳进旧版 App，却看到「这条内容已不存在」。他刚刚亲眼看到这条内容存在，所以这个提示是误导，也浪费了一次引导升级的机会。H5 页面不知道用户装的 App 是什么版本，但它可以直接写一句「需要最新版 App 才能观看」。
- **建议修复**：FR-19 在视频帖的分享页上加文案"需更新到最新版 App 观看"，并附上商店链接（`app_version_config` 里已有双端商店地址）。

---

## Low

### L-1　旧版和新版双设备、会员等级变化、上传中途被降级时的提示都没写
- **PRD 位置**：FR-3、FR-4、FR-9
- **问题**：等级从 10 降到 1 时，正在上传或刚上传完的发布者点「发布」会收到 403，PRD 没有定义这时的提示文案和页面去向。同一个账号在新旧两台设备上看到的内容不同，属于预期行为，但应该写进 FR-18 的说明，避免被当成 bug 报上来。
- **建议修复**：FR-4 补一条"发布时权限已被收回 → 提示 X，保留本次填写的内容 / 直接退出"。

### L-2　游客是否能播放视频没有写
- **PRD 位置**：FR-14、FR-15、§7
- **问题**：游客可以浏览推荐流和详情（`GET /content-posts/**` 对未登录用户开放）。游客能否进入全屏播放、互动时会不会触发登录墙、埋点的 distinctId 怎么算，都没有写。
- **证据**：`SecurityConfig.java:197-200`
- **建议修复**：在 FR-15 / FR-21 写明游客的行为和指标口径。

### L-3　stag 和生产共用同一个 OSS 桶
- **PRD 位置**：§8.3、FR-17、addendum D
- **问题**：stag 与生产共用一个桶，只靠 key 前缀 `stag/` 区分。stag 测试产生的视频体积会直接计入生产存储，而且永不删除（F21）。ACL 修改、CDN 刷新的测试也是在生产桶上进行的。stag 是否也要接视频 CDN，PRD 没有说。
- **证据**：`petgo-backend/src/main/resources/application.yml:207-209`
- **建议修复**：§8.3 / §10 第 3 条补充 stag 的存储策略，例如给 `stag/` 前缀单独配置生命周期规则。

### L-4　UJ-2 提到的「标题」在数据模型里不存在
- **PRD 位置**：UJ-2（"标题是「猫咪为什么踩奶」"）
- **问题**：帖子没有标题字段，只有正文。卡片上显示的是正文摘要。写成「标题」会让 UX 去设计一个不存在的字段。
- **建议修复**：改成"卡片文案"。

### L-5　官方号正文被判为高风险时，与「直接公开」相冲突
- **PRD 位置**：FR-10 第 3、4 条；§8.2
- **问题**：正文照常走文本审核。如果文本被判为 RISKY 或审核服务降级，同时人工审核开关是打开的，帖子会进入 UNDER_REVIEW，不会"直接公开"。PRD 的两句话在这种情况下互相矛盾。
- **证据**：`ContentService.java:530-565`
- **建议修复**：FR-10 第 4 条写明"文本被判为高风险时，按现有规则挂起，与视频免审无关"。

### L-6　上传凭证过期后「用户无感知」的说法过于乐观
- **PRD 位置**：FR-9 最后一条；addendum D（"视频单独加长到 1800 秒"）
- **问题**：OSS 预签名只在请求开始时校验是否过期，传输中途过期不影响本次上传。真正会遇到的是失败重试时凭证已经过期，而重试会从 0 重新上传 50MB，因为没有做分片续传。另外，申请凭证的接口有每分钟 30 次的限流，自动重试时需要退避。
- **证据**：
  - `application.yml:212`（`upload-ttl-seconds: 600`）
  - `MediaController.java:20-21,33`
- **建议修复**：FR-9 改成"重试时自动重新申请凭证，从头上传"，不承诺续传，并写明重试的退避策略。

---

## 附：已核实、无冲突的说法

以下说法经核实与代码一致，记录在这里，避免重复核查：

- **人工审核**：`ManualReviewGate.enqueue` 和 `UNDER_REVIEW` 状态都存在，可以复用（`content/service/ManualReviewGate.java`）。
- **后台用户详情**：用户详情抽屉存在（`/admin/users/{userId}/drawer`）；新增权限码不需要迁移。
- **顶置**：顶置功能存在（`admin/pin/`，`ContentPinTakedownListener` 已联动下架）。
- **举报预处置**：阈值为 10 人（`ReportService.java:34`）。
- **对象 key 归属校验**：`MediaObjectKeys.requireAllOwned` 存在；`objectKeyOfPublicUrl` 只认一个 CDN 前缀，这与 addendum C 的说法一致。
- **缩略图参数**：`AppImage.ossResized` 只对 `aliyuncs.com` 域名拼参数（`petgo_app/lib/shared/widgets/app_image.dart:29`）。换成视频 CDN 域名后，封面不会自动生成缩略图，addendum E 已经识别到这一点。
- **App 版本配置**：`app_version_config` 表存在，有双端商店地址，可以复用来存放「视频最低版本」。注意它是固定字段的单行表，新增一列需要迁移，并在后台 AB-22 页面增加对应字段。
