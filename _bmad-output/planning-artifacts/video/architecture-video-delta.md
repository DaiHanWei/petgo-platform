---
title: "专题线 video · 架构增量"
status: complete
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
completedAt: '2026-10-16'
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-10-16'
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: research/video @ 925817bd（= main 5ec8a2e4 + 文档）
inputDocuments:
  - _bmad-output/planning-artifacts/video/PRD-video.md
  - _bmad-output/planning-artifacts/video/addendum-video.md
  - _bmad-output/planning-artifacts/video/决策日志-video.md
  - _bmad-output/planning-artifacts/video/review-adversarial.md
  - _bmad-output/planning-artifacts/research/video-post-01-current-chain-2026-10-16.md
  - _bmad-output/planning-artifacts/research/video-post-02-scope-and-spec-2026-10-16.md
  - _bmad-output/planning-artifacts/research/video-post-03-compressor-spike-2026-10-16.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/project-context.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
---

# 专题线 video · 架构增量

> 只写本专题新增或改变的技术决定，均需对照代码核实。**权威顺序**：`决策日志-video.md` > `PRD-video.md` > 本文件 > `addendum-video.md`。

## 项目上下文分析

### 需求概览
- 功能需求 23 条（FR-1 ~ FR-23，FR-13 已移除），分 8 组：
  - 会员等级与视频免审名单：FR-1 ~ FR-4、FR-22
  - App 选择、规格检查、封面、上传、发布：FR-5 ~ FR-10
  - 服务端校验：FR-11、FR-12
  - 通用上传加固：FR-23
  - 列表与全屏播放：FR-14 ~ FR-16
  - 后台：FR-17
  - 旧版兼容与分享：FR-18、FR-19
  - CDN 与埋点：FR-20、FR-21
- 关键非功能要求：
  - 规格检查 ≤ 1 秒；起播 ≤ 2 秒；
  - 安装包增量：安卓 ≤ 3MB，iOS ≤ 5MB（第一期不引入压缩库，决策 V-33）；
  - 隐私：视频不含位置元数据，日志不含签名 URL；
  - 帖子不可见后，文件直链 ≤ 10 分钟失效；
  - 检查或上传失败都可以重试，且不崩溃。

### 规模与复杂度
- 领域：移动端 + 后端全栈（Flutter / Spring Boot / OSS / CDN）。复杂度：中。
- 涉及组件（约 9 个）：
  - App：发帖页与媒体选择、MP4 规格检查器与抽帧、OSS 直传（流式 / 表单上传）、全屏播放页与列表封面；
  - 后端：上传签名服务、发帖服务与审核路由、Feed 推荐与分类查询、文件可见性与 CDN、后台（等级 / 名单 / 内容）。

### 技术约束与依赖（已核实）
- **基线护栏**：不引入 MQ / 缓存层 / 新中间件；schema 由 Flyway 管理（时间戳号）；对外标识不可枚举；凭证用 env 注入。
- **上传**：
  - 现状：预签名 PUT，无 STS。PUBLIC 域把 public-read 签进签名（`PresignedUploadService:19,61-65`）。签名不限大小，可以覆盖同名对象。content-type 由客户端任意填写，未知类型回落为 `.bin`。
  - upload-url 是全站共用端点。App 内唯一调用点只申请 `image/jpeg`（`media_upload_use_case.dart:107`）。
- **官方号**：1.3.4 的 `isOfficialAccount` 判断的是 role=ADMIN 的后台作者账号，它不登录 App（`AccountQueryService:158-168`），**不能复用**，改用独立的视频免审名单（决策 V-25）。
- **推荐流**：
  - 推荐顺序以快照形式缓存在 Redis（`FeedSequenceStore`），翻页时只读快照。
  - 有三条取数路径：候选池、科普池、降级回落；另有按时间倒序的分类 Tab（`FeedService:230-292`）。
- **能力声明先例**：时间线接口的 `supports=` 参数，客户端没声明的新类型不下发（`TimelineCapabilities`）。
- **不可见事件**：`ContentUnavailableEvent` 覆盖作者删除、审核下架、封号；**没有对应的「恢复可见」事件**。
- **人工审核**：`ManualReviewGate`（默认关闭）和 `UNDER_REVIEW` 状态可以复用；作者视角下不显示「审核中」。
- **版本识别**：后端目前不读取 `X-App-Version`；stag 版本号带 `-stag` 后缀，现有的比较函数遇到会出错（`AppVersionConfigService:96-106`）。
- **发帖最低门槛**：文字和图片不能同时为空（`ContentService:469-473`）。
- **存储**：stag 与生产共用同一个 OSS 桶（key 前缀 `stag/`）；F21 规定文件永不删除。
- **App**：
  - 图片链路全程用 `Uint8List`；独立的 Dio 没有超时，也没有进度回调；`AppImage.ossResized` 只识别 `aliyuncs.com`。
  - 第一期不压缩视频（V-33）。新增依赖只有 `video_player`（播放）和 `fc_native_video_thumbnail`（抽帧）；MP4 规格检查用纯 Dart 自己写。

### 跨组件关注点
1. **文件可见性状态机**：上传（不公开）→ 公开 → 各种不可见 → 恢复，以及对应的视频 CDN 缓存刷新（CDN 可配置）。
2. **客户端能力识别（旧版过滤）**：要贯穿所有返回帖子的接口，并且在生成推荐顺序时就生效。
3. **媒体类型分支**：所有「把首图当图片用」的地方都要区分视频帖，还要处理 `imageUrls` 为空时的兼容（卡片比例、露出位置、发帖最低门槛）。
4. **审核路由**：视频免审名单、强制人工审核、文本审核三者怎么组合。
5. **埋点与指标口径**：按 App 版本切分、游客怎么算、补报事件的时间戳。
6. **直传安全**：上传凭证要限定类型、大小、路径，并禁止覆盖（FR-23）；视频默认不公开；视频 CDN 自有域名要防止被当成网页托管，也要防止被刷流量。

## 技术基线（棕地，无脚手架）

- **沿用现有技术栈，不变**：
  - App：Flutter 3.44 / Dart 3.12（Riverpod、go_router、dio）
  - 后端：Spring Boot 4.0.6 / Java 21（PostgreSQL、Redis、Flyway）
  - 存储与审核：阿里云 OSS 单桶 + 内容安全
- **新增依赖**（版本核实于 2026-10-16）：
  - **App**：
    - `video_player` ^2.14.1：播放，底层用系统 ExoPlayer / AVPlayer
    - `fc_native_video_thumbnail` ^3.0.1：抽取封面，支持 SwiftPM 与 Built-in Kotlin
    - MP4 规格检查器用纯 Dart 自研，不引入依赖
  - **后端**：不新增依赖，复用已有 SDK。
    - `aliyun-sdk-oss` 3.18.1：PostObject 策略、`x-oss-forbid-overwrite`、对象 ACL、HEAD 请求
    - `aliyun-java-sdk-core` 4.7.3：用通用请求调用 CDN 的 RefreshObjectCaches
  - **基础设施**：新增一个阿里云 CDN 加速域名，只服务视频和封面，回源到 OSS。这是配置变更，不属于新中间件。
- **本期不引入**：
  - 压缩库（决策 V-33，留到开放会员时再引入）
  - HLS / 多码率
  - 云端转码
  - 磁盘缓存库

## 核心架构决策（AD-1 ~ AD-12）

> 2026-10-16 已按架构校验报告（`review-architecture.md`）修订：C1–C5、I1–I16、N1–N12。

### 优先级
- **阻塞实现**：AD-1 数据形态、AD-2 字段与约束、AD-5 校验与审核路由、AD-6 文件可见性、AD-7 能力过滤。
- **塑造架构**：AD-3 / AD-4 上传、AD-8 DTO 与签名 URL、AD-9 / AD-10 App、AD-11 CDN 开关、AD-12 后台。
- **推迟到开放会员时**：手机端压缩、视频机审、HLS / 多码率、磁盘缓存。

### 数据
**AD-1 封面即首图。**
- 视频帖的字段约定：
  - `image_urls = [封面完整 URL]`，恰好 1 张；
  - `image_sizes = [{video_width, video_height}]`：由**服务端**用视频的显示宽高填入，不取客户端上报值，也不进兜底测量；
  - `image_crop_offsets = null`：必须是 null，不能是 `[]`。卡片按视频比例显示，封面居中裁切。
- 视频帖不调用 `scheduleSizeBackfill`。原因是封面在发布前是私有的，匿名拉取测不到尺寸。
- 首图、卡片比例、顶置图、发帖最低门槛（文字或图片至少一项）直接复用现有逻辑。
- **归属校验不能复用**（现有发帖链路没有归属校验，见 V-20），只给视频帖新写，见 AD-5。
- 漏掉旧版过滤时，旧版 App 会把这条帖子当作一张封面图展示，属于平稳降级。

**AD-2 字段与约束（时间戳迁移）。**
- `content_posts` 新增：
  - `media_type VARCHAR(8) NOT NULL DEFAULT 'IMAGE'`，`CHECK (media_type IN ('IMAGE','VIDEO'))`
  - `video_object_key VARCHAR(256)`、`video_duration_ms INTEGER`、`video_width INTEGER`、`video_height INTEGER`、`video_size_bytes BIGINT`
  - `media_acl_desired VARCHAR(8)`、`media_acl_applied VARCHAR(8)`，两列都加 `CHECK (… IN ('PUBLIC','PRIVATE'))`
  - `media_acl_desired_at TIMESTAMPTZ`：每次写 desired 时更新，用于告警计时
  - 一致性约束：`CHECK (media_type='IMAGE' OR (video_object_key IS NOT NULL AND media_acl_desired IS NOT NULL AND media_acl_applied IS NOT NULL))`
  - 对账用的部分索引：`ON content_posts (id) WHERE media_type='VIDEO' AND media_acl_desired <> media_acl_applied`
- `users.vip_level INTEGER NOT NULL DEFAULT 1 CHECK (vip_level BETWEEN 1 AND 99)`，不要用 SMALLINT。
- 新建表 `video_trusted_publishers(user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, created_by_admin_id BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())`。
- `review_reason` 的取值新增 `VIDEO_FORCED`，长度 ≤ 24。如果它带 CHECK 约束，要**按当前树中最后一次重建的值全集**重建。

### 安全与上传
**AD-3 视频走 PostObject。**
- 接口：`POST /api/v1/media/video-upload-policy`。
  - 要求等级 ≥ 10；每个账号每天限 50 次，按 **WIB 自然日**计数，超限返回专用错误 `video-upload-quota-exceeded`（429）。
  - 返回 `{host, key, fields}`，`key` 由服务端生成。
- 目标桶：`media.oss.public-bucket`（目前与私有桶是同一个 `tailtopia`）。
- 用 `generatePostPolicy` / `calculatePostSignature`（OSS V1 签名）生成策略，条件如下：
  - `["eq","$key",<服务端生成的完整 key>]`：锁死完整 key，不用前缀匹配，保证 key 不可枚举；
  - `["content-length-range",1,104857600]`
  - `["eq","$Content-Type","video/mp4"]`
  - `["eq","$x-oss-object-acl","private"]`
  - `["eq","$x-oss-forbid-overwrite","true"]`
  - `["eq","$success_action_status","200"]`
- 策略有效期：`media.video.upload-ttl-seconds`，默认 1800 秒。OSS 只在请求开始时检查是否过期。
- App 端：
  - 用独立的 Dio，设置 connectTimeout 15s、sendTimeout 600s；
  - 用 `FormData` 上传，表单字段在前，`file` 放最后。`file` 用 `MultipartFile.fromFile(path, contentType: video/mp4)`，流式读取；
  - 支持 `onSendProgress` 和 `CancelToken`。

**AD-4 加固通用的 upload-url（FR-23）。**
- 类型白名单只有图片：jpeg / png / webp / heic。`video/mp4` 等其他类型返回 422 `upload-content-type-not-allowed`。视频只能走 AD-3。
- `x-oss-forbid-overwrite: true` 在签名时加入，**同时放进响应的 `headers` 里**。旧版 App 会把 `ticket.headers` 原样带回，所以旧版不受影响。
- 新增 `purpose=VIDEO_COVER` 参数：此时签成 private，并且要求等级 ≥ 10。不带 purpose 的调用行为不变。

**AD-5 发帖校验与审核路由。** 视频帖**一律走 `publish()` 的主体流程**，包括 @ 名单清洗、幂等、事件、`publishMentioned`，**不要调用 `publishTrusted`**。在 `publish` 里加一个 `skipModeration` 分支。同时更新 `publishTrusted` 的类注释，说明视频帖不走这里。

校验项（`VideoPostValidator`）：

| 校验项 | 错误码 | 在事务内还是外 |
|---|---|---|
| 等级 ≥ 10（发帖时实时校验） | `video-permission-denied` 403 | 内 |
| `mediaType=VIDEO` 时 `imageUrls` 恰好 1 个且 `video` 不为空；`mediaType=IMAGE` 时带了 `video` | `video-mixed-media` 422 | 内 |
| `type ∈ {DAILY, KNOWLEDGE}`；`petId`、`eventDate`、`placeCheckinToken` 为空；`imageCropOffsets` 为 null | `validation` 422 | 内 |
| `visibility` 强制设为 PUBLIC（忽略请求值） | — | 内 |
| 上报时长在 3–90 秒；宽高为正数；显示短边 ≤ 720 | `video-object-invalid` 422 | 内 |
| 视频 key 前缀是 `keyPrefix + "video/" + userId + "/"` | `video-object-invalid` 422 | 外 |
| 封面：`objectKeyOfPublicUrl(imageUrls[0])` → `MediaObjectKeys.belongsTo(PUBLIC, userId)`（新写；图文帖仍按 V-20 不校验） | `video-object-invalid` 422 | 外 |
| HEAD 视频：对象存在，`Content-Type=video/mp4`，大小 ≤ 100MB，记录 `video_size_bytes` | `video-object-invalid` 422 | **外** |

审核路由：
- **名单内**：跳过 `moderation.evaluate`（文本、图片都不审），直接 PUBLISHED。初值 `desired=PUBLIC`，`applied=PRIVATE`。
- **名单外，且等级 ≥ 10**：
  - 先跑**文本审核**，L1 硬拦截照常生效；**跳过图片审核**（封面是私有的，内容安全接口拉不到图）。
  - 然后一律设为 `UNDER_REVIEW`，`review_reason=VIDEO_FORCED`，调用 `manualReviewGate.enqueue`（这个方法本来就不读开关）。
  - 初值 `desired=PRIVATE`，`applied=PRIVATE`。
  - **超时扫描**：`ManualReviewService.scanTimeouts` 的判断条件改为 `postGateEnabled || review_reason=VIDEO_FORCED`。也就是说，总开关关着时，这类帖子仍按 3 天超时丢弃。
- 视频帖跳过 Gemini 自动评论和运营暖评。
- 不要复用 `isOfficialAccount`。

### 文件可见性
**AD-6 期望状态由实体推导 + 定时对账。**
- **推导规则**（纯函数）：`desiredOf(post) = (deletedAt == null && status == PUBLISHED && visibility == PUBLIC) ? PUBLIC : PRIVATE`。
- **谁来重算**：`ContentPost` 所有会改变状态的方法，在**实体内部**同步重算 desired 并更新 `media_acl_desired_at`，调用方不需要逐个记忆。涉及的方法：`publish` 初始化、`softDelete`、`restore`、`approveReview`、`discardReview`、`applyReportHold`、`releaseReportHold`。只对 `media_type=VIDEO` 生效。
  - `restore` 之后如果帖子仍是 UNDER_REVIEW，推导结果自然是 PRIVATE。这是按状态推导而不是按操作类型的好处。
- **批量 SQL 要显式写 desired**：注销时的 `deactivateByAuthor` 批量 UPDATE，要同时写 `media_acl_desired='PRIVATE'` 和 `media_acl_desired_at=now()`。
- **实际入口**（核实过，用于测试覆盖）：

  | 入口 | 代码位置 |
  |---|---|
  | 作者删除 | `ContentApiController:60 → deleteByAuthor` |
  | 下架 | `AdminContentManageService:619`、`AdminModerationService:107` |
  | 删除用户（违规） | `AdminUserService:135 → takedownAllByAuthor → softDelete` |
  | 恢复 | `AdminContentManageService:633` |
  | 审核通过 / 丢弃 | `ManualReviewService:127/154/200` |
  | 举报预处置 | `ReportService:81` |
  | 解除预处置 | `AdminModerationService:135/159` |
  | 注销 | `AccountDeletionService:162` |

  **单纯停用账号**（`suspend` / `deactivate`）不改动帖子，视频也保持可见。这与图文帖现有行为一致（决策 V-39）。
- **对账器 `MediaAclReconciler`**（`@Scheduled`，默认每分钟一次）：
  - 每轮最多处理 100 条 `desired ≠ applied` 的帖子。逐条执行：设置视频对象 ACL → 设置封面对象 ACL → `CdnCacheRefresher.refresh(视频 URL)`。无论改成 PUBLIC 还是 PRIVATE 都要刷新 CDN，避免 CDN 缓存了 403。
  - 写回时用**条件单列 UPDATE**：`UPDATE content_posts SET media_acl_applied=:v WHERE id=:id AND media_acl_desired=:v`。影响 0 行就留到下一轮处理。**禁止 `save()` 整个实体**，参照 `updateImageSizes` 的先例。
  - 单条失败不阻塞其他条。对象不存在（404）时打 ERROR，并把 applied 写成 desired，避免无限重试。
  - `now() - media_acl_desired_at > 5 分钟` 且仍不一致时，打 ERROR 日志，事件名为 `video_acl_reconcile_stuck`。告警只走日志，不走 App 通知（项目约定）。
- **即时对账**：发布、下架、恢复这几个接口在**事务提交后、Controller 层（事务外）**，同步对这一条做一次「尽力而为」的对账（调用 `MediaAclReconciler.reconcileOne(postId)`，逻辑与定时任务相同）。失败就交给定时任务兜底。这一步发生在事务外，不违反「禁止用 AFTER_COMMIT 写库」的规定。这样可以避免发布后约 1 分钟内出现坏图。

### 接口
**AD-7 能力声明识别旧版。**
- App 端：`ClientInfoInterceptor` 增加请求头 `X-App-Capabilities: video`。
- 后端：只在 **Controller 层**解析 `ClientCapabilities`，得到 `boolean includeVideo`，再作为**显式参数**一路传给服务和仓库。服务层不注入请求级 bean，避免在请求线程之外被调用时出错。
- 过滤出口：

| 端点 | 服务方法 | 仓库 / 做法 |
|---|---|---|
| `GET /content-posts`（推荐） | `FeedRecommendationService` 候选池、科普池 | WHERE 排除 VIDEO；序列键改为 `feed:seq:{ns}:v{0|1}:{seed}`（**只改序列键，不改 namespace**，曝光记录 `FeedSeenStore` 不拆分） |
| 同上 · 作者本人置顶插入 | `findOwnPendingPosts` / `findOwnRecentPosts`（`FeedRecommendationService:176、254`） | WHERE 排除 |
| 同上 · 降级回落、分类 Tab | `FeedService:230-292` | WHERE 排除 |
| 同上 · 顶置 | `FeedService.loadPinnedSlot` / `pinnedContentIdToYield`（`:159-171、417-433`） | 按 id 取单条，**例外：取出后判断**，是视频就当作没有顶置 |
| `GET /me/following-feed` | 关注流查询 | WHERE 排除 |
| `GET /users/{id}/posts`、`GET /me/posts` | `FeedService.userPublicPosts / myPosts` | WHERE 排除 |
| `GET /content-posts/{id}` | `ContentDetailService.requireVisiblePost` | 视频 → 统一 404 |
| `GET /content-posts/{id}/likers` | `PostLikersService`（经 `requireVisiblePost`） | 同上 |
| `GET /content-posts/{id}/comments` | `ContentDetailController:60 → CommentQueryService` | 同上 |
| `GET /comments/{parentId}/replies` | `ContentDetailController:66 → CommentQueryService.replies:193` | 先取 parent 所属帖子，是视频 → 404 |
| `GET /public/shared-posts/{token}` | `ContentShareApiController:46-51` | 视频 → 404，提示「已不存在」 |

- 不过滤的地方：
  - **写接口**（点赞、评论、生成分享链接）：旧版本来就看不到视频帖。
  - **`findRankableByIds`**：它从序列快照回填，而序列已经按能力分开了。
- 通知中心**没有**帖子缩略图（`NotificationItem` 没有图片字段），所以这一项不适用。通知深链进入详情时，由详情的 404 处理。

**AD-8 DTO 与签名 URL。**
- 新增 `mediaType` 和 `video{url,durationMs,width,height}` 的地方：
  - `FeedItemResponse.of()`、`ContentDetailResponse` 的 2 个 `of()`；
  - `SharedPostResponse`：**新增 `of()` 工厂**，并改造 `ContentShareService` 里的直接构造；
  - `ContentPostResponse.from()`。
- 图片帖不下发 `video`（NON_NULL）。
- `VideoUrlResolver` 负责给出视频地址：
  - 帖子公开、ACL 已生效时：配了 CDN 用 `media.video.cdn-base-url`，没配就用 `media.oss.cdn-base-url`（即 `AliyunOssClient.publicUrl`），后面拼对象 key。
  - **作者本人看到 PRIVATE 状态的视频帖**（审核中、预处置中、ACL 尚未生效）：视频和 `imageUrls[0]` / `firstImageUrl` 都换成签名 URL。有效期取 max(15 分钟, 时长 × 3)。
  - **后台**（审核详情、内容抽屉、评论审核里的帖子缩略图）：通过 `VideoUrlResolver.forAdmin(post)` 拿签名 URL。
  - `SignedUrlService` 新增 `sign(bucket, key, ttl)` 重载。现有方法写死了私有桶，单桶环境下用法一样，但要显式传桶名。
- 请求体新增：`mediaType`、`video{objectKey,durationMs,width,height}`。封面放在 `imageUrls[0]`，文案允许为空。`ContentPostCreateRequest` 现有的 **6 个**兼容构造器全部补齐。

### App
**AD-9 发帖。**
- 媒体项改为互斥的两种：「图片列表」或「单个视频」。
- 选择视频：用 `ImagePicker.pickVideo(source: gallery)`。**冒烟时要验证** iOS 的 PHPicker 会不会把文件转码或改写（会破坏「原样上传」和 faststart）。如果会，改用能保留原文件的方式，并作为风险记录下来。
- 选中之后的流程：
  1. 纯 Dart 检查 MP4 头，1 秒内完成（见 addendum A）；
  2. 用 `fc_native_video_thumbnail` 取第 1 秒作为默认封面，可以从相册替换；
  3. 视频（AD-3）和封面（AD-4，`purpose=VIDEO_COVER`）并行上传。
- 入口限制：
  - 已选视频时，「成长日记」类型不可选；
  - 从成长日历、打卡后发帖、`/publish?preset=growth-calendar` 进入时，不显示视频入口。
- 后端返回 403 `video-permission-denied` 时，提示「你暂时没有发布视频的权限」，退出前保留已填写的内容。
- App 切到后台再回来，上传会继续或失败；失败时显示「重试」，发帖页不能卡死。

**AD-10 播放。**
- **路由**：`/content/:id` 不变。`ContentDetailPage` 先拉详情，再按 `mediaType` 在页面内渲染 `VideoPostPage`。从列表点进来时，如果已知是视频，用 `extra` 直接进入，跳过一次加载态。`focus=comments` 进入视频页时自动打开评论抽屉。App 内的分享页 `SharedPostPage` 遇到视频帖，跳到 `/content/:id`。
- **播放器**：`video_player`。
  - 有声自动播放；静音按钮的状态在本次会话内记住；
  - 单击暂停；有进度条；循环播放；
  - 15 秒内没出画面算超时，显示重试；
  - 离开页面停止；App 切到后台时暂停，回到前台不自动续播。
- 复用详情页的作者区、互动、评论抽屉组件。游客可以播放，互动仍沿用现有的登录墙。
- 列表卡片显示封面、播放角标和时长。
- **埋点**（以 PRD FR-21 为准，取代 addendum 旧表）：

| 事件 | 属性 |
|---|---|
| `video_card_impression` | postId、source |
| `video_play_start` | postId、source、ttffMs |
| `video_progress` | postId、source、pct（25 / 50 / 75） |
| `video_complete` | postId、source、loop |
| `video_buffering` | postId、durationMs |
| `video_play_error` | postId、errorType |
| `video_watch_time` | postId、source、seconds、occurredAt |
| `video_spec_check` | ok、failedItems |
| `video_upload_error` | errorType |
| `video_publish` | durationMs、sizeBytes、uploadMs |

  - `source` 的取值：`feed | category | following | profile | my_posts | pinned | notification | push | share | other`。由列表通过路由的 `extra` 或 query 参数 `src` 传入，缺省时为 `other`。
  - 观看秒数：只统计画面实际在播放的时间（暂停、后台、缓冲都不算），单次上限 = 时长 × 3。
  - 补报：离开播放页时上报；如果 App 被杀，`shared_preferences` 里只保存最后一条未上报的 `video_watch_time`（带 `occurredAt`），下次启动时补报后删除。
  - 所有事件都带 App 版本。

### 基础设施
**AD-11 视频 CDN（可配置，生产上线的前提，决策 V-38）。**
- **开关**：在 `@Configuration` 里用一个 `@Bean` 方法根据 `StringUtils.hasText(media.video.cdn-base-url)` 返回 `AliyunCdnCacheRefresher` 或 `NoopCdnCacheRefresher`。**不要用 `@ConditionalOnProperty`**：yml 里写的是 `${ENV:}`，值为空字符串时它也会匹配。要写一条测试覆盖「空串 → 用 Noop」。
- stag 不配 CDN 也能部署和验收。生产必须配好 CDN 才能上线；阿里云 CDN 需要企业认证，见 PRD §10 第 2 条。
- 配好 CDN 后的要求：
  - 回源到 OSS，**不开启私有 Bucket 回源**；
  - 只放行 `(stag/)?video/` 前缀下的 `.mp4`；
  - Range 回源；
  - 响应头加 `nosniff`；
  - 配置 IP 访问频控，以及每日流量告警和封顶。
- 刷新缓存：通过 `aliyun-java-sdk-core` 的通用请求调用 `RefreshObjectCaches`。CDN 刷新本身是异步的，10 分钟失效的目标在 L2 实测。权限只申请 `cdn:RefreshObjectCaches`。
- 存储生命周期：stag 的规则**只作用于 `stag/video/` 前缀**，不影响 stag 的图片；具体规则等 PRD §10 第 3 条定下来再配。生产遵守 F21。
- H5 分享页的视频帖：显示封面、播放角标，以及「需更新到最新版 App 观看」。按钮仍然尝试拉起 App，与 PRD FR-19 的现有逻辑一致；拉起失败时跳转商店。

**AD-12 后台。**
- **等级**：用户抽屉里设置等级。服务端校验 1 ≤ v ≤ 99，审计摘要格式为 `vip_level old→new`。需要权限 `user.vip_level.edit`。
- **免审名单**：
  - 用户抽屉里加入或移出名单，需要权限 `video.trusted.manage`，写审计日志；
  - 新增「视频免审名单」列表页（`/admin/video-trusted`，需要同一个权限），列出账号、昵称、会员等级、加入人、加入时间。
- **内容**：
  - 内容列表、内容抽屉、审核详情：识别视频帖，用签名 URL 预览（AD-8）；
  - 评论审核里的帖子缩略图也用签名 URL。

### 实现顺序与依赖
1. 迁移、等级与免审名单（AD-2、AD-12）
2. 上传（AD-3、AD-4）
3. 发帖校验与审核路由（AD-5）
4. 可见性推导与对账（AD-6，用 Noop 刷新器就能验收；CDN 刷新器可以并行开发）
5. 能力过滤与 DTO（AD-7、AD-8）
6. App 发帖（AD-9）
7. App 播放与列表、埋点（AD-10）
8. 后台展示与预览（AD-12）

依赖关系：
- AD-7 的联调，需要 App 先加上能力头。
- AD-1 让 AD-8 里与图片相关的字段不用改。
- AD-6 不依赖 CDN。

## 实现规范（视频专题补充；基线规范照旧适用）

### 命名
- **枚举**：`MediaType { IMAGE, VIDEO }`、`MediaAcl { PUBLIC, PRIVATE }`。落库为大写 varchar；JSON 中写作 `mediaType: "VIDEO"`。
- **对象 key**：
  - 视频：`{keyPrefix}video/{userId}/{随机16字节}.mp4`
  - 封面：沿用 `{keyPrefix}public/{userId}/{随机}.jpg`，ACL 为私有
  - CDN 只放行 `video/` 前缀。视频不得放在其他前缀下。
- **请求头**：`X-App-Capabilities`，值为逗号分隔的小写能力名。本期只有 `video`，遇到未知能力值直接忽略。
- **权限码**：`user.vip_level.edit`、`video.trusted.manage`。放进现有的后台权限常量类，默认不分配给任何角色。
- **错误码**（ProblemDetail `type` 的后缀）：

  | 错误码 | HTTP 状态 |
  |---|---|
  | `video-permission-denied` | 403 |
  | `video-object-invalid` | 422 |
  | `video-mixed-media` | 422 |
  | `upload-content-type-not-allowed` | 422 |
  | `video-upload-quota-exceeded` | 429 |

- **配置键**：`media.video.cdn-base-url`、`media.video.upload-ttl-seconds`、`media.video.max-bytes`、`media.video.daily-policy-limit`、`media.video.acl-reconcile-cron`。凭证一律走 env 注入。
- **埋点**：
  - 事件名以 `video_` 开头，用 snake_case（见 addendum F）；
  - 统一走 Analytics 门面，并加入白名单；
  - 属性 key 用 camelCase。
- **App 文件**：放在 `features/content/video/` 下，文件名用 snake_case，不新建顶层 feature。

### 结构
- **后端**：放进已有模块，不新建顶层包。
  - `shared/media/`：`VideoUploadPolicyService`
  - `shared/web/`：`ClientCapabilities`
  - `content/service/`：`VideoPostValidator`、`MediaAclReconciler`
  - `admin/usermgmt/`：会员等级与免审名单
- **测试**：
  - 测试目录沿用现有结构。
  - App 端的 MP4 检查器必须有基于真实样片的单元测试。样片放在 `petgo_app/test/fixtures/video/`，每个 ≤ 1MB。

### 格式
- 时长统一用毫秒整数（`durationMs`）。
- 宽高按**显示方向**存储和下发，不下发旋转角度。
- `video` 字段只在视频帖里出现（NON_NULL）。App 用 `mediaType` 判断是不是视频帖，**不要**用 `video != null` 判断。
- 文件体积统一用字节整数。

### 一致性规则（agent 必须遵守）
1. **可见性由实体推导**：`media_acl_desired` 只能由 `ContentPost` 的状态方法（以及注销批量 SQL）按 `desiredOf` 写入。修改 OSS ACL、刷新 CDN 只能由 `MediaAclReconciler`（定时任务或 `reconcileOne`）执行。
2. **能力判断只在 Controller 层解析**：通过 `ClientCapabilities` 得到 `includeVideo`，再作为显式参数传下去；服务层不注入请求级 bean。新增任何下发帖子的出口，评审时都要对照 AD-7 的出口表。
3. **视频过滤写在查询里**：在 SQL / JPQL 的 WHERE 条件里排除视频帖。禁止在内存里过滤，也禁止在读取 Redis 序列时过滤。唯一例外是按 id 取单条的顶置，见 AD-7。
4. **DTO 单一工厂**：`mediaType` 和 `video` 只在各 DTO 的 `of()` / `from()` 里组装（`SharedPostResponse` 要先补上 `of()`）。
5. **显式区分媒体类型**：凡是把 `image_urls` 当照片处理的代码，都必须先判断 `mediaType`。包括 Gemini 自动评论、暖评、访客 / 名片的 EXIF 处理等。
6. **免审只看名单**：判断依据只能是 `video_trusted_publishers`。禁止用 `isOfficialAccount` 或 `role` 判断。
7. **HEAD 放在事务外**：OSS 网络调用不得在持有数据库事务时发生。
8. **App 不读入整个视频**：禁止把视频读进 `Uint8List`。检查、抽帧、上传都基于文件路径或流。
9. **不新增系统权限**：不加 `READ_MEDIA_VIDEO`、`RECORD_AUDIO`，也不加麦克风相关的权限描述。

### 过程
- **上传失败**：App 不自动重试，由用户点「重试」触发。重新申请上传凭证时做指数退避（1s / 2s / 4s）。
- **对账失败**：每 1 分钟重试一次。连续 5 分钟仍不一致，打 ERROR 日志并走运维告警。日志里不记录签名 URL。
- **播放页状态**：加载态显示「封面 + 转圈」；失败态显示「点击重试」，不用 toast。

## 项目结构与边界

### 需求 → 组件映射

| FR 组 | 后端 | App | 后台 |
|---|---|---|---|
| 会员等级 / 免审名单（FR-1~4、22） | users 迁移、/me DTO、免审名单服务 | `/me` 模型加 vipLevel | 用户抽屉：等级 + 名单 |
| 规格检查 / 封面 / 上传（FR-5~9） | 视频上传策略接口 | `features/content/video/` | — |
| 发布与审核路由（FR-10、12） | `ContentService.publish` + 视频校验 | 发帖页、发帖控制器 | 审核详情识别视频 |
| 上传加固（FR-11、23） | `PresignedUploadService` | 封面上传带 purpose | — |
| 列表 / 播放 / 互动（FR-14~16） | 帖子 DTO 工厂 | 卡片角标、VideoPostPage | — |
| 后台展示 / 下架（FR-17） | 可见性写 desired + 对账 | — | 内容列表 / 抽屉 / 审核模板 |
| 旧版过滤（FR-18） | ClientCapabilities + 各查询 | 拦截器加能力头 | — |
| 分享（FR-19） | `content_share.html` | — | — |
| CDN / 埋点（FR-20、21） | 可配置的 CDN 刷新、视频 URL 拼接 | Analytics 事件 | — |

### 后端（`petgo-backend/src/main/java/com/tailtopia/`）

    shared/
      web/ClientCapabilities.java                   【新】
      media/
        PresignedUploadService.java                 改：类型白名单、forbid-overwrite、purpose=VIDEO_COVER
        AliyunOssClient.java                        改：PostObject 策略、HEAD、setObjectAcl
        VideoUploadPolicyService.java               【新】
        CdnCacheRefresher.java                      【新】接口
        NoopCdnCacheRefresher.java                  【新】未配置 CDN 时装配
        AliyunCdnCacheRefresher.java                【新】配置了 CDN 时装配（sdk-core 通用请求）
        MediaProperties.java                        改：media.video.*
        web/MediaController.java                    改：POST /video-upload-policy
        dto/VideoUploadPolicyResponse.java          【新】
    content/
      domain/ContentPost.java                       改：mediaType、video*、mediaAcl*
      domain/MediaType.java, MediaAcl.java          【新】
      dto/ContentPostCreateRequest.java             改：mediaType、video{}（6 个兼容构造器全部补齐）
      dto/FeedItemResponse.java                     改：of() 加 mediaType、video
      dto/ContentDetailResponse.java                改：同上
      dto/SharedPostResponse.java                   改：同上
      dto/VideoView.java                            【新】
      service/ContentService.java                   改：publish 视频分支；所有可见性迁移点写 desired
      service/VideoPostValidator.java               【新】
      service/VideoTrustedPublisherService.java     【新】只读端口
      service/MediaAclReconciler.java               【新】
      service/VideoUrlResolver.java                 【新】有 CDN 时返回 CDN 地址，否则返回 OSS 源站地址；审核中返回作者签名 URL
      service/FeedService.java                      改：分类 Tab / 降级 / 我的发布 / 主页过滤
      rank/FeedRecommendationService.java           改：候选池 / 科普池过滤
      rank/FeedRankCacheKey.java                    改：+ 能力维度
      service/ContentDetailService.java             改：旧版 404
      service/PostLikersService.java                改：旧版 404
      service/FeedService.java（loadPinnedSlot）     改：顶置在旧版下取出后判断（AD-7 例外）
      service/CommentQueryService.java              改：comments / replies 旧版 404
      web/ContentShareApiController.java（或服务）   改：分享帖旧版 404；SharedPostResponse 改为 of() 工厂
      dto/ContentPostResponse.java                  改：+ mediaType
      service/ManualReviewService.java              改：scanTimeouts 对 VIDEO_FORCED 不受总开关影响
      shared/media/SignedUrlService.java            改：sign(bucket, key, ttl) 重载
      web/FollowingFeedController.java（或服务）     改：关注流过滤
      autocomment/AutoCommentService.java           改：视频帖跳过
    admin/
      account/domain/AdminPermissions.java          改：+ user.vip_level.edit、video.trusted.manage
      usermgmt/service/AdminUserService.java        改：等级、名单（写审计日志）
      usermgmt/web/…（用户抽屉控制器）                改：两个操作端点
      usermgmt/web/AdminVideoTrustedController.java 【新】/admin/video-trusted 列表页
      warmreply/service/VirtualCommentService.java  改：视频帖跳过
      pin/service/AdminContentPinService.java       改：视频标识
      moderation/…（通过 / 拒绝 / 超时）              改：经 ContentService 统一入口写 desired
    auth/…（/me 响应）                               改：+ vipLevel

    resources/
      db/migration/V<时间戳>__video_media_columns.sql       【新】
      db/migration/V<时间戳>__video_trusted_publishers.sql  【新】
      templates/content_share.html                          改
      templates/admin/fragments/content-list.html           改
      templates/admin/fragments/drawer-content.html         改
      templates/admin/fragments/review-detail.html          改
      templates/admin/fragments/drawer-users.html           改
      templates/admin/video-trusted.html                    【新】免审名单列表页
      application.yml                                       改：media.video.*（cdn-base-url 默认空）

### App（`petgo_app/lib/`）

    core/network/client_info_interceptor.dart       改：+ X-App-Capabilities: video
    core/analytics/…（白名单）                        改：+ video_* 事件
    features/content/
      video/                                        【新目录】
        video_spec_checker.dart / video_spec.dart
        video_cover_service.dart
        video_upload_repository.dart
        video_post_page.dart
        video_player_controller_provider.dart
        video_analytics.dart
        video_card_badge.dart
      domain/publish_controller.dart                改：图片列表 | 单视频
      domain/feed_item.dart、content_detail.dart、shared_post.dart  改：+ mediaType、video
      data/content_repository.dart                  改：请求体
      presentation/publish_compose_page.dart        改：视频入口 / 检查结果 / 封面替换 / 进度
      presentation/content_detail_page.dart         改：按 mediaType 分流
      presentation/feed_view.dart、shared/widgets/masonry_card.dart、post_grid_tile.dart、promo_pinned_card.dart  改：角标
    features/media/data/media_repository.dart       改：purpose
    features/media/data/oss_uploader.dart           改：超时 + 进度
    core/router/app_router.dart                     改：视频帖路由
    features/auth/…（UserProfile）                   改：+ vipLevel
    l10n/*.arb                                      改：id / en / zh 文案
    pubspec.yaml                                    改：+ video_player、fc_native_video_thumbnail
    test/fixtures/video/                            【新】≤1MB 样片
    test/features/content/video/                    【新】

### 架构边界
- **业务事务 ↔ OSS / CDN**：只能通过 `MediaAclReconciler`、`VideoPostValidator`（HEAD）和 `CdnCacheRefresher` 接口访问。
- **请求 → 能力判断**：只能通过 `ClientCapabilities`。
- **content ↔ admin**：admin 只写自己的表，并记审计日志；content 通过 `VideoTrustedPublisherService` 只读访问。
- **App 视频模块 ↔ 发帖页**：发帖页只依赖 `video/` 对外暴露的接口。以后接入压缩库时，只需替换这一层。

### 外部集成
- **阿里云 OSS**：PostObject 策略、对象 ACL、HEAD。开发对账功能时，给 RAM 用户追加 `oss:PutObjectAcl` 和 `oss:GetObjectAcl`。
- **阿里云 CDN**（可配置，生产上线前提）：前提是完成企业认证，之后开通 CDN、配置加速域名，再给 RAM 用户追加 `cdn:RefreshObjectCaches`。没配置 CDN 时自动降级到 OSS 源站，stag 不依赖它。

### 测试策略（每条标注验证层级）

| 测试 | 内容 | 层级 |
|---|---|---|
| MP4 规格检查器 | `test/fixtures/video/` 中的样片：avc / hevc / HDR / 非 faststart / 带位置 / 旋转 / 无音轨 / 超时长，以及剪映导出的样片 | L0 |
| `desiredOf` 推导 | 状态 × deletedAt × visibility 的组合表 | L0 |
| 对账器 | OSS 和 CDN 打桩：并发修改 desired 时的条件 UPDATE、单条失败不阻塞、对象 404、5 分钟告警 | L0 + L1 |
| CDN 开关 | `cdn-base-url` 为空串时装配 Noop，有值时装配 Aliyun | L0 |
| 能力过滤矩阵 | 对 AD-7 表中的每个出口，分别在「有 / 无」`X-App-Capabilities` 时断言视频帖是否出现，以及分页不出现空页 | L1 |
| 发帖校验 | AD-5 校验表逐行：每种失败对应的错误码 | L1 |
| 强制审核超时 | 总开关关闭时，`VIDEO_FORCED` 帖子在 3 天后被丢弃 | L1 |
| 可见性迁移 | AD-6 入口表逐个触发，desired 都正确 | L1 |
| upload-url 兼容 | 带 forbid-overwrite 头时，按 1.3.4 `OssUploader` 的方式直传成功；重复上传被拒绝 | L2（真实 OSS） |
| PostObject | 超出大小、类型错误、key 不一致、覆盖，都被 OSS 拒绝 | L2（真实 OSS） |
| 播放与起播 | `ttffMs` ≤ 2 秒（4G）；拖动进度；后台切换 | L2（真机） |
| 安装包增量 | 冒烟时记录 APK / IPA 大小差：安卓 ≤ 3MB，iOS ≤ 5MB | L2 |
| iOS 选择器 | PHPicker 返回的文件与原文件逐字节一致（未被转码） | L2（真机） |

## 架构校验结果（2026-10-16）

- **方法**：由独立 agent 对照 PRD、决策日志和代码，以只读方式逐项核实，输出报告 `review-architecture.md`（critical 5 条、important 16 条、nice-to-have 12 条）。所有问题都已按建议修订，对应决策 V-39、V-40。
- **一致性**：架构、PRD、决策日志三者已对齐。addendum 只保留 A、B 两节，C–F 已作废，改为指向架构文档。
- **需求覆盖**：
  - FR-1 ~ FR-23 均有对应的落点（FR-13 已移除）。
  - §8.1 中的起播时间、安装包增量、iOS 选择器是否原样返回文件，都纳入了 L2 测试。
- **实现就绪**：以下内容已经齐备，可以进入 epics 拆分：
  - AD-5 校验表
  - AD-6 推导规则与入口表
  - AD-7 出口表
  - AD-10 埋点表
  - 测试策略
- **遗留问题**（都不阻塞开发）：
  - CDN 企业认证（会阻塞生产上线）
  - iOS PHPicker 是否会改写文件（冒烟时验证）
  - F21 下的视频存储策略
  - 视频供给节奏
