# 架构校验报告 · 专题线 video · architecture-video-delta.md

- 校验日期：2026-10-16
- 校验对象：`video/architecture-video-delta.md`（draft，AD-1 ~ AD-11）
- 依据：`PRD-video.md`（final）、`addendum-video.md`、`决策日志-video.md`（V-0 ~ V-38；V-34 已被 V-35 作废）
- 代码基线：`research/video` 工作区（= main 5ec8a2e4 + 文档），只读核实
- 结论：**方向正确，但暂不能直接交给 dev agent。** 有 5 处 critical 必须先改：其中两处（归属校验、审核超时）是架构对现有代码的说法与事实不符；一处（publishTrusted）照写会造成功能缺失；一处（AD-6）是可见性迁移点的清单与代码不一致；另一处是 addendum 整体停留在旧方案。

| 级别 | 数量 |
|---|---|
| critical | 5 |
| important | 16 |
| nice-to-have | 12 |

---

## Critical

### C1. AD-1 / AD-5 所说「复用现有归属校验」并不存在
- **位置**：架构 AD-1 第 111 行（「归属校验这些现有逻辑全部直接复用」）、AD-5 第 142 行（「封面走现有的归属校验」）。
- **问题**：发帖链路目前**完全没有**图片归属校验，这正是 V-20「图文帖图片归属漏洞先不修」所指的漏洞。照架构字面实现，封面可以是任意外部 URL 或他人的对象，FR-12「视频和封面都是本人上传」不成立。另外，现有工具类按 `MediaScope`（`public/` / `private/`）判断前缀，视频的 `video/` 前缀不是一个 scope，无法直接复用。
- **证据**：
  - `ContentService.java:452-482`：`publish()` 对 `imageUrls` 只检查数量（≤9）和最低门槛，没有任何归属判断。
  - `MediaObjectKeys.java:37-45`：`belongsTo` 的前缀写死为 `keyPrefix + scope.prefix() + "/" + userId + "/"`。
  - `MediaScope.java:11-13`：只有 PUBLIC("public") 和 PRIVATE("private") 两个值。
  - `AliyunOssClient.java:97-123`：`imageUrls` 存的是完整 URL，必须先用 `objectKeyOfPublicUrl` 转成对象 key 再判断；`cdn-base-url` 为空时它返回 empty。
- **建议修复**：在 AD-5 写明，`VideoPostValidator` **新增**两项校验：
  1. 封面：`objectKeyOfPublicUrl(imageUrls[0])` 取得 key，再执行 `MediaObjectKeys.belongsTo(PUBLIC, userId)`；
  2. 视频：新写一个前缀判断 `keyPrefix + "video/" + userId + "/"`，或者给 `MediaObjectKeys` 增加一个按任意前缀判断的重载。

  删除 AD-1 里「归属校验直接复用」的说法，改成「只对视频帖新增校验；图文帖仍按 V-20 不校验」。

### C2. AD-5「强制入队 + 沿用 3 天超时」：入队可行，但超时在总开关关闭时不会触发
- **位置**：AD-5 第 145 行。
- **问题**：
  - 入队本身可行：`enqueue()` 不读开关，并且已有先例（`enqueueComment` 也是绕过开关无条件入队）。
  - 超时扫描对**帖子类队列项**有开关门控：总开关关闭时（生产默认状态）帖子项永远不会超时丢弃。结果是，名单外账号发的视频帖在没人处理时会一直停在 UNDER_REVIEW，FR-10「超时按现有规则丢弃」的验收条件不成立。
  - 后台审核页的总开关只是页面上的一个属性，不影响列表展示，这部分没问题。
- **证据**：
  - `ManualReviewGateImpl.java`：`enqueue()` 直接 `queue.save(ManualReviewItem.pending(...))`，不判断 `enabled()`。
  - `ManualReviewGate.java`：`enqueueComment` 的注释写明「不受 enabled() 开关门控」。
  - `ManualReviewService.java:188-206`：`boolean postGateEnabled = settingsService.isManualReviewEnabled(); … else if (postGateEnabled) { discardReview … }`。
- **建议修复**：
  1. AD-5 补一个标记：给队列项加 `forced`（或 `reason=VIDEO_FORCED`）字段，需要一条迁移；也可以在帖子的 `review_reason` 上写 `VIDEO_FORCED`（长度 24 够用）。
  2. `scanTimeouts` 的条件改为 `postGateEnabled || 帖子是强制入队项`。
  3. 把「总开关关闭时视频帖仍按 3 天超时」写成一条 L1 测试。

### C3. AD-5「名单内直接 PUBLISHED（与 publishTrusted 相同）」：照抄会丢掉 @ 名单、尺寸和露出位置
- **位置**：AD-5 第 144 行。
- **问题**：`publishTrusted` 是专门给 Lark 运营内容源用的，和 `publish` 有多处差异：
  - 刻意不写 `mentionedUserIds`，也不发 @ 通知；
  - 不写 `imageSizes` / `imageCropOffsets`，也不调用 `scheduleSizeBackfill`；
  - 不支持打卡关联。

  视频帖在 App 内由用户输入，而 FR-10 要求「文案、@ 用户的规则与图文帖相同」。照 publishTrusted 实现，会导致 @ 失效、封面没有尺寸（卡片比例错误）。此外，publishTrusted 的类注释明写「UGC 一律走 publish，任何面向用户输入的路径接入本方法都是违规」，与本方案直接冲突。
- **证据**：
  - `ContentService.java:630-641`：类注释写明「仅限运营策划内容源…UGC 一律走 publish」。
  - `ContentService.java:670-682`：只调用 `ContentPost.publish(...)` 和 `setVisibility` 后就保存，注释写「运营内容源刻意不写 @ 名单」。
- **建议修复**：AD-5 改写为：视频帖一律走 `publish()` 的主体流程（@ 名单清洗、尺寸、幂等、事件、`publishMentioned` 全部保留），只在「名单内」时跳过 `moderation.evaluate` 这一步；在 `publish` 内加一个 `skipModeration` 分支，不要调用 `publishTrusted`。同时更新 `publishTrusted` 的类注释，避免后人误解。

### C4. addendum（以及 PRD 个别句子）仍是旧方案，与 AD 正面冲突
- **位置**：`addendum-video.md` C / D / E / F 节；`PRD-video.md` §3 术语表。
- **问题**：架构声明的权威顺序是「决策日志 > PRD > 架构 > addendum」，但 addendum 是 story 拆分时被引用最多的「技术细节」来源，下列条目与 AD 直接相反，agent 很可能照着 addendum 写：

| addendum / PRD 原文 | 冲突对象 |
|---|---|
| C 第 29 行：新增 `video_url`、`video_cover_url`；「**不要把视频塞进 `image_urls`**」 | AD-1 / V-36：封面就是 `image_urls[0]`，没有 `video_cover_url`；AD-2 用的是 `video_object_key` |
| C 第 33 行：「视频和封面在库里存对象 key」 | AD-1：封面存在 `image_urls`（完整 URL） |
| D 第 36 行：upload-url 白名单含 `mp4`，预签名 PUT，发帖时 HEAD 兜底大小 | AD-3：视频改走 PostObject；AD-4：upload-url 白名单不含 mp4 |
| D 第 37 行：视频凭证 1800 秒 | 架构没有给出 `media.video.upload-ttl-seconds` 的默认值 |
| D 第 38 行：归属校验「复用 `requireAllOwned`」 | 见 C1，无法直接复用 |
| D 第 40 行：「建议统一挂在帖子可见性变化的**事件**上」 | AD-6：同一事务写 desired，禁止 AFTER_COMMIT |
| D 第 46 行：配置键 `media.oss.video-cdn-base-url` | AD-11 / 命名规范：`media.video.cdn-base-url` |
| D 第 47-48 行、E 第 56 行：CDN 透传 `x-oss-process`、对视频 CDN 域名下的封面拼缩略图参数、「MP4 和封面设置长缓存」 | V-38 / FR-20：封面走 OSS 源站，CDN 只放行 `.mp4` |
| E 第 57 行：「`OssUploader` 改为流式上传」 | AD-3：视频走新的 PostObject 上传器，封面仍是字节 PUT |
| F：`video_process_start` / `video_process_result`（压缩处理事件），`video_publish` 带 `bytesAfter` / `processMs` | V-33 不做压缩；PRD FR-21 要的是「视频规格检查结果」事件 |
| PRD §3 第 76 行：「视频分发地址：视频文件**和封面**对外下发时使用的 CDN 地址」 | V-38 / FR-20：封面走 OSS 源站 |

- **建议修复**：按 V-33 / V-36 / V-38 重写 addendum 的 C / D / E / F 节，或者在 addendum 顶部加「C/D/E/F 已被架构 AD-1~AD-11 取代」并逐条删改；同时修正 PRD 术语表第 76 行。改完后在决策日志补一条记录。

### C5. AD-6 的可见性迁移点清单与代码不一致，且缺少「desired 怎么算」的规则
- **位置**：AD-6 第 151 行，以及结构图第 315、335 行。
- **问题**：
  1. **「封号」在代码里不是帖子层面的迁移**。账号停用（举报处置 SUSPEND 和后台「停用」）都只把 `users.status` 置为 DEACTIVATED，不改任何帖子；推荐流 / 分类 Tab / 主页的查询也**不按作者状态过滤**，只有关注流过滤。所以「封号后帖子不可见」在现有代码里并不成立，「封号 → 写 desired=PRIVATE」也找不到挂载点。真正会改帖子的是后台「删除用户（违规）」，它走 `takedownAllByAuthor` → `softDelete`。另外清单里也没有「重新激活」这条恢复路径。
  2. 注销走的是 JPQL 批量 UPDATE（`deactivateByAuthor`），拿不到单条实体，要在同一条 SQL 里写 desired。
  3. `restore()` 只清 `deletedAt`，如果帖子之前是「审核中被丢弃」的，恢复后仍然是 UNDER_REVIEW，不应该公开。按「操作类型」写 desired 会写错，必须按**帖子的最终状态**推导。
  4. 新帖插入时 desired 和 applied 的初值没有定义。
- **证据**：
  - `AccountDisposalService.java:221-234`：`suspend()` 只调用 `authService.deactivateUser`，不碰内容。
  - `AdminUserService.java:150-170`：`deactivate()` / `reactivate()` 同样不碰内容。
  - `ContentPostRepository.java:504-516`：`findFeed` 没有作者状态条件；`:543-546` 只有 `findFollowingFeed` 带 `u.status = ACTIVE`。
  - `ContentPostRepository.java:720-728`：注销是批量 UPDATE。
  - `ContentService.java:324-331`：`restore()` 只清 deletedAt。
  - 真实入口：
    - `ContentService.deleteByAuthor`：`ContentApiController:60`
    - `softDelete(ADMIN_TAKEDOWN)`：`AdminContentManageService:619`、`AdminModerationService:107`、`takedownAllByAuthor`（`AdminUserService:135`）
    - `restore`：`AdminContentManageService:633`
    - `approveReview` / `discardReview`：`ManualReviewService:127/154/200`
    - `applyReportHoldIfPublished`：`ReportService:81`
    - `releaseReportHold`：`AdminModerationService:135/159`
    - `deactivateAuthorContent`：`AccountDeletionService:162`
- **建议修复**：
  1. 在 AD-6 定义一个纯函数 `desiredOf(post) = (deletedAt==null && status==PUBLISHED && visibility==PUBLIC) ? PUBLIC : PRIVATE`，并规定 `ContentPost` 的所有状态变更方法（`softDelete`、`restore`、`approveReview`、`applyReportHold`、`releaseReportHold`）在实体内部同步重算 desired。这样调用方不用逐个记忆，也不会漏。
  2. 批量 UPDATE 显式加上 `media_acl_desired = 'PRIVATE'`。
  3. 新帖初值：名单内 desired=PUBLIC、applied=PRIVATE；审核中 desired=PRIVATE、applied=PRIVATE。
  4. 「封号」请产品确认：(a) 只指「删除用户（违规）」→ 已被 softDelete 覆盖；还是 (b) 停用账号也要隐藏视频 → 那就需要在用户状态变更处批量写 desired，并且推荐流也要补作者状态过滤，这已超出视频专题范围。建议选 (a)，并同步修改 PRD FR-17 的措辞。

---

## Important

### I1. AD-7 过滤出口清单不完整，部分落点写错
- **位置**：AD-7 第 164-166 行，结构图第 323-327 行。
- **问题与证据**：

| 漏掉或写错的出口 | 证据 | 影响 |
|---|---|---|
| `GET /api/v1/public/shared-posts/{token}`（App 内打开分享链接） | `ContentShareApiController.java:46-51`；App 调用点 `shared_post_repository.dart:25` | UJ-3「Rina 在 App 里打开分享链接看到『已不存在』」和 FR-18 最后一条都做不到 |
| `GET /api/v1/comments/{parentId}/replies` | `ContentDetailController.java:66` → `CommentQueryService.replies:193`（没有经过 `requireVisiblePost`） | 旧版仍能拉到视频帖下的回复 |
| 作者本人置顶插入：`findOwnPendingPosts` / `findOwnRecentPosts` | `FeedRecommendationService.java:176、254`；`ContentPostRepository.java:676、699` | 规则 2 说「每个出口都要核对」，这两处没列出 |
| 顶置的实际位置是 `FeedService.loadPinnedSlot` 和 `pinnedContentIdToYield`，**不是 `ContentPinService`** | `FeedService.java:159-171、417-433`（`posts.findById`） | 落点写错；而且这里是按 id 取单条，没法遵守规则 3「写在 WHERE 里」，需要写明例外 |
| 评论 GET 在 `ContentDetailController` / `CommentQueryService`，**不是 `CommentController`**（后者只有写操作） | `CommentController.java:41-78`、`ContentDetailController.java:60` | 落点写错 |
| `POST /content-posts/{id}/share-link`、点赞 / 评论写接口 | `ContentShareApiController.java:39`、`LikeController.java:39` | 旧版看不到视频帖，影响小；建议写明「写接口不过滤」这一决定 |
| 「通知中心的帖子缩略图」（PRD FR-14 / FR-18） | `NotificationItem.java:32-49` 没有任何图片字段；App 的 `features/notify` 也没有帖子缩略图 | PRD 描述了一个不存在的功能。通知只是深链到详情，详情 404 已经覆盖。建议架构写明「不适用」，PRD 删掉这条 |

  另外，`findRankableByIds`（`ContentPostRepository.java:653`）是从序列快照回填帖子的入口。序列键已经按能力区分，所以这里可以不加过滤，但应该写一句理由，免得 reviewer 误以为漏了。
- **建议修复**：把 AD-7 改成按「端点 → 实际服务方法 → 仓库查询」三列的表，补上上面的条目，并修正结构图第 325-327 行的类名。

### I2. 名单内账号「直接 PUBLISHED」后，最长约 1 分钟内文件仍是私有
- **位置**：AD-5 和 AD-6。
- **问题**：帖子事务提交时 applied=PRIVATE，要等下一轮 `@Scheduled`（每分钟）才改成 public-read。在这段时间里：发布成功页「去 Feed 看」看到的封面是坏图、视频返回 403；App 的图片缓存可能把失败结果也缓存下来；如果配了 CDN，CDN 还可能缓存 403。UJ-1「回到首页下拉刷新能看到」会出现偶发性失败。
- **证据**：`AppImage.ossResized`（`app_image.dart:25-29`）对 aliyuncs 地址直接拼 `x-oss-process`，私有对象匿名访问会返回 403。
- **建议修复**：发布接口在事务提交之后、**在 Controller 层（事务外）**同步做一次「尽力而为」的对账，只处理这一条，失败就交给定时任务。这一步不写库，不违反「禁止 AFTER_COMMIT 写库」。也可以由发布接口唤醒对账器立即跑一轮。同时规定「改成 PUBLIC 后也要刷新 CDN」（防止缓存了 403），这一点 AD-6 第 154 行已隐含，建议写明。

### I3. 审核中 / 预处置中的封面是私有的，作者看到坏图；后台预览私有视频也没有方案
- **位置**：AD-8 第 170 行（只给视频签名 URL）、FR-17「详情里可以直接播放」。
- **问题**：
  1. 封面就是 `image_urls[0]`，审核中的 ACL 是 private，而作者在推荐流（作者例外）、「我的发布」、置顶插入里都能看到这条帖子，封面会是坏图。
  2. 运营在审核详情、下架后的内容抽屉里，需要预览私有的视频和封面，架构没有给后台签名方案。
  3. `SignedUrlService.sign` 写死用私有桶，如果视频放在公开桶，签不出来。
  4. 签名 URL 有效期 300 秒，视频循环播放时后续的分段请求可能 403。
- **证据**：`SignedUrlService.java:36-49`（`ossClient.privateBucket()`）；`application.yml:211`（`ttl-seconds:300`）；`ContentPostRepository.java:456-466、504-509`（作者能看到 UNDER_REVIEW）。
- **建议修复**：
  - AD-8 规定，状态为 UNDER_REVIEW 时，下发给作者的 `imageUrls[0]` / `firstImageUrl` 也换成签名 URL。
  - 后台模板通过 `VideoUrlResolver.forAdmin(post)` 获取签名 URL。
  - `SignedUrlService` 增加 `sign(bucket, key, ttl)` 重载。
  - 作者和后台的视频签名有效期取 ≥ 视频时长 × 3，或者 15 分钟。
  - 写明视频放在哪个桶（`public-bucket`）。

### I4. AD-4 的 `x-oss-forbid-overwrite` 必须同时放进返回的 headers，否则所有版本的发图都会失败
- **位置**：AD-4 第 137 行。
- **问题**：OSS V1 预签名会把 `x-oss-*` 请求头计入签名。如果只在签名时加上、却没放进响应里的 `headers`，客户端 PUT 时不会带这个头，签名对不上，所有图片上传（包括 1.3.4 及以前的旧版）都会失败。好在旧版 App 会把 `ticket.headers` 原样带回，所以只要放进 headers 就能兼容。另外，PRD FR-23 第 214 行的白名单包含 `video/mp4`（并且「需要发视频权限」），而 AD-4 的白名单不含 mp4，两者不一致。
- **证据**：`PresignedUploadService.java:58-68`（headers 是手工组装的）；`AliyunOssClient.java:56-66`（`req.addHeader` 进签名）；`oss_uploader.dart:30-33`（`...ticket.headers` 原样带回）。
- **建议修复**：AD-4 写明「`x-oss-forbid-overwrite: true` 同时签入，并加进响应 `headers`」，并补一条测试：用 1.3.4 的 `OssUploader` 行为直传成功。PRD FR-23 改成「upload-url 白名单只含图片，`video/mp4` 一律拒绝（422）；视频走独立接口（FR-11）」。

### I5. AD-6 对账器缺少关键实现约束
- **位置**：AD-6 第 152-157 行，AD-2。
- **问题与建议**：
  1. 「连续 5 分钟不一致才告警」需要一个时间戳，AD-2 没有对应列 → 建议加 `media_acl_desired_at timestamptz`（写 desired 时更新），或者加 `media_acl_attempts int`。
  2. 并发问题：对账过程中 desired 被再次改动（例如 PUBLIC→PRIVATE→PUBLIC）。写回 applied 必须用条件更新 `UPDATE … SET applied=:v WHERE id=:id AND desired=:v`，影响 0 行就留到下一轮。
  3. 写回只能用**定向单列 UPDATE**，不能 `save()` 整个实体，否则会覆盖同时提交的审核或下架结果。代码里已有先例：`ContentPostRepository.java:739-746` 的 `updateImageSizes` 注释专门讲了这个坑。
  4. 扫描查询加部分索引：`WHERE media_type='VIDEO' AND media_acl_desired <> media_acl_applied`。
  5. 「运维通知」用哪个通道？项目约定后台告警走日志，不走 App 通知。请写清楚。
  6. 每轮的批量上限、单条失败不阻塞其他条目、对象不存在（404）时怎么处理。

### I6. `@ConditionalOnProperty` 遇到空字符串会误装配阿里云刷新器
- **位置**：AD-11 第 193 行。
- **问题**：项目的 yml 惯例是 `key: ${ENV:}`（见 `application.yml:207`）。这样属性**存在但值为空串**，而 `@ConditionalOnProperty(name=…)` 在未指定 `havingValue` 时，只要值不是 `"false"` 就会匹配。结果是没配 CDN 的环境也装配了 `AliyunCdnCacheRefresher`，每次刷新都失败告警，Noop 实现永远不会生效。
- **建议修复**：改成自定义 `Condition`（判断 `StringUtils.hasText`），或者在 `@Configuration` 里用一个 `@Bean` 方法根据 `MediaProperties` 返回对应实现。加一条测试：空串 → Noop。

### I7. `VideoPostValidator` 的校验清单没有写全（FR-10 / FR-12）
- **位置**：AD-5 第 142 行只列了 HEAD 的四件事。
- **问题**：下面这些都需要显式写明，否则 agent 会各自发挥：
  - **发帖时**的等级 ≥10 校验（FR-4：等级改回 1 后不能再发）；
  - `mediaType=VIDEO` 时 `imageUrls` 恰好 1 个，`video` 必须有值；`mediaType=IMAGE` 时带了 `video` → `video-mixed-media`；
  - `type` 只能是 DAILY / KNOWLEDGE；
  - `petId` / `eventDate` / `placeCheckinToken` 必须为空。现有 `publish` 对任何类型都接受打卡 token（`ContentService.java:487-491`），需要显式拒绝；
  - `visibility` 强制为 PUBLIC（`req.visibilityOrPublic()` 允许 PRIVATE）；
  - 上报的时长在 3–90 秒、宽高为正数、短边 ≤720；
  - `imageCropOffsets` 必须为 null，不能是 `[]`。传 `[]` 配 1 张图会被 `ImageCropOffsetValidator.java:31-40` 判为长度不符，返回 422；
  - 名单外账号是否仍跑机审（L1 文本硬拦截）？封面是私有的，内容安全拉不到图，会走降级。建议：名单外账号**只跑文本审核、跳过图片审核**，然后强制入队；
  - 每种失败对应的错误码（命名规范第 228-236 行只给了 5 个）。
- **建议修复**：在 AD-5 加一张「校验项 → 错误码 → 执行时机（事务外 / 内）」的表。

### I8. AD-1 的 `image_sizes` 用什么尺寸有歧义，而且兜底测量拉不到私有封面
- **位置**：AD-1 第 110 行「`image_sizes = [显示宽高]`」。
- **问题**：用户可以换一张比例不同的相册图做封面。这时 `image_sizes` 应该填视频的显示宽高（卡片按视频比例显示，封面居中裁切），还是封面图的宽高？另外，客户端不传尺寸时，`ImageSizeBackfillService` 会去匿名拉取封面 URL，而封面是私有的，测不出来，卡片会长期没有尺寸。
- **证据**：`ImageSizeBackfillService.java:129-150`（`URLConnection` 匿名读取）；`ImageSizeResolver.normalize`（没传 → 全部置 null 交给兜底）。
- **建议修复**：明确规定 `image_sizes[0] = {video_width, video_height}`（视频的显示宽高），由服务端用 `video{width,height}` 直接填写，不依赖客户端上报，也不进兜底测量。在 AD-1 写明「视频帖不调用 `scheduleSizeBackfill`」，与 addendum D「`ImageSizeBackfillService` 不处理视频帖」对齐。

### I9. App 详情路由在进入时不知道帖子类型，AD-10「按 mediaType 分流」没有落点
- **位置**：AD-10 第 184 行。
- **问题**：`/content/:id` 只带 id。从通知、推送、深链、分享进入时，不先请求接口就不知道是不是视频帖；`?focus=comments` 进入视频页后应该打开评论抽屉；App 内的分享页 `SharedPostPage` 也要能跳到视频页。
- **证据**：`app_router.dart:1055-1067`；`deep_link_routes.dart:123-144`（统一跳 `/content/$postId`）。
- **建议修复**：AD-10 规定由 `ContentDetailPage` 先拉详情，再按 `mediaType` 在页面内渲染 `VideoPostPage`，或者 replace 到视频路由；列表点击时如果已知是视频，可以直接带 extra 进入。补充 `focus=comments` 和分享页的行为。

### I10. `ClientCapabilities` 请求级 bean 的边界，以及缓存键会连带影响曝光记录
- **位置**：AD-7 第 163、165 行。
- **问题**：
  - 请求级 bean 在请求线程之外（事件监听、@Async、定时任务）访问会抛异常。`ContentDetailService` 和 `FeedService` 以后可能被非请求的代码路径复用。
  - `FeedRankCacheKey.namespace` 同时被序列和曝光记录（`FeedSeenStore`）使用。如果直接把能力维度加进 namespace，同一个用户在新旧两个版本之间的曝光记录会被拆成两份。
- **证据**：`FeedRankCacheKey.java:14-44`；`FeedSequenceStore.java:42-43`（`KEY_PREFIX + namespace + ":" + seed`）。
- **建议修复**：只在 Controller 层解析能力，然后作为**显式参数**（例如 `boolean includeVideo`）传给服务和仓库，`ClientCapabilities` 只负责解析。能力维度只加到 `FeedSequenceStore.key`（例如 `feed:seq:{ns}:v{0|1}:{seed}`），不改 namespace。

### I11. FR-22「名单全量列表」以及 FR-2 的细节没有落点
- **位置**：结构图第 283、331-332 行（只写了「用户抽屉：等级 + 名单」）。
- **问题**：
  - FR-22 要求有名单列表页，并显示每个账号的会员等级，架构里没有这个页面、端点和模板。
  - FR-2 要求取值范围 1–99，审计记录前后值，也没有写。
- **建议修复**：补一个后台页面：「视频免审名单」列表（端点、权限 `video.trusted.manage`、模板、显示等级列）。等级接口的服务端校验 `1 ≤ v ≤ 99`，并在库里加 `CHECK (vip_level BETWEEN 1 AND 99)`；审计摘要写 `old→new`。

### I12. FR-21 埋点没有设计：事件定义冲突、补报和来源都没有方案
- **位置**：AD-10 第 189 行「埋点按 PRD FR-21 和 addendum F」。
- **问题**：
  1. addendum F 是压缩时代的事件定义，与 FR-21 冲突（见 C4）；
  2. 「App 被杀掉时下次启动补报」需要本地持久化（存储位置、上限、去重、补报事件的时间戳），架构跨组件关注点第 5 条提到了，但没有任何设计；
  3. 「进入来源」需要从各个列表一路传到播放页，路由参数怎么带没有说明；
  4. 观看秒数只计实际播放时间、上限为时长 ×3，这个计时器的口径没有写。
- **建议修复**：AD-10 增加一张事件表（以 PRD FR-21 为准，取代 addendum F），写清补报存储（例如 secure storage 外的 shared_preferences，只存最后一条未报的 `video_watch_time`）、`source` 枚举和传递方式、计时口径。

### I13. stag 生命周期规则作用在 `stag/` 前缀上，会把 stag 的图片也一起删掉
- **位置**：AD-11 第 200 行。
- **问题**：stag 和生产共用同一个桶，`stag/` 前缀下有全部 stag 图片（包括测试种子数据）。这条规则不在决策日志里，PRD §10 第 3 条（存储策略）也还没定。
- **建议修复**：把范围缩到 `stag/video/`，并在决策日志补一条；如果暂不确定，就先删掉这一句，留到 §10-3 定案时再写。

### I14. 架构内部与 V-38 矛盾的两处
- **位置**：
  - 第 73 行：「CDN 缓存刷新（包括带 `x-oss-process` 参数的缓存变体）」。V-38 之后封面不走 CDN，CDN 只放行 `.mp4`，这个变体不存在。
  - 第 215 行：「AD-6 依赖 AD-11 的 CDN 域名」。V-38 规定没有 CDN 时用 Noop，AD-6 不依赖 CDN。
- **建议修复**：删除或改写这两处；实现顺序第 4 步改成「对账（Noop 刷新器即可验收），CDN 实现器可并行」。

### I15. 测试策略几乎空白
- **位置**：「结构 → 测试」第 251-253 行只写了 MP4 样片。
- **建议补充**：
  - **能力过滤矩阵测试**：枚举 I1 表格中的每个出口，分别在「有 / 无」能力头下断言是否出现视频帖、分页是否空页（L1）；
  - **对账器**：OSS 和 CDN 打桩，覆盖并发改 desired、失败重试、5 分钟告警（L0 + L1）；
  - **desired 推导**：`desiredOf` 的状态组合表测试；
  - **强制审核超时**：总开关关闭时 3 天超时（L1）；
  - **upload-url 兼容**：forbid-overwrite 头和旧版 `OssUploader` 一起工作（L2）；
  - **PostObject**：真实 OSS 下的超限、错误类型、错误前缀、覆盖都被拒绝（L2）；
  - 每条都标注 L0 / L1 / L2，与项目约定一致。

### I16. AD-3 PostObject 的细节还不够让 agent 一次做对（结论：在 dio 上可行）
- **位置**：AD-3。
- **可行性核实**：dio 5 的 `FormData` 先写表单字段、后写文件，满足 OSS「`file` 必须是最后一个字段」的要求；`MultipartFile.fromFile` 是流式读取，不会把视频整个读进内存；FormData 的长度可以预先算出，Content-Length 能正确设置；`onSendProgress` 和 `CancelToken` 都可用。所以方案本身可行。
- **缺口与建议**：
  1. 用 `["eq","$key", 服务端生成的完整 key]`，不要用 `starts-with` 前缀。否则客户端可以自己命名，「随机 16 字节、不可枚举」这条规则就形同虚设；
  2. 写明签名版本（V1 或 V4）以及 SDK 方法（`generatePostPolicy` / `calculatePostSignature`）；
  3. 加 `success_action_status=200`；
  4. 写明目标桶；
  5. `media.video.upload-ttl-seconds` 给出默认值（建议 1800 秒，与 addendum D 一致）。注意 policy 的过期只在请求开始时检查；
  6. 文件分段的 `contentType` 设为 `video/mp4`，同时表单里也要有 `Content-Type` 字段，policy 校验的是后者；
  7. 视频上传用独立的 Dio，并配置 connect / sendTimeout（现有的 `OssUploader` 没有超时，见 `oss_uploader.dart:20`）。

---

## Nice-to-have

| # | 位置 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| N1 | 结构图第 310-313 行 | 写的是「5 个兼容构造器」，实际有 6 个；`ContentDetailResponse` 有 2 个 `of()` 重载；`SharedPostResponse` **没有** `of()` 工厂，是在 `ContentShareService` 里直接构造的，与一致性规则 4「只在 of() 里组装」不符 | `ContentPostCreateRequest.java:72-104`；`ContentDetailResponse.java:92、102`；`SharedPostResponse.java`（无 static 工厂） | 改成「6 个」；给 `SharedPostResponse` 新增 `of()` 并改造调用方，或者在规则 4 写明例外 |
| N2 | AD-10 | 没写 FR-15「App 切到后台时暂停，回到前台不自动续播」 | PRD FR-15 第 247 行 | 补上 |
| N3 | AD-9 第 180 行 | 只说「成长相关入口不显示」，漏了 FR-5 的「打卡后发帖」入口 | PRD FR-5 第 125 行 | 补上 |
| N4 | AD-9 | iOS 的 `image_picker` 选视频时，PHPicker 可能会转码或改写文件（与 FR-7「原样上传」、faststart 冲突），需要验证 | — | 写明 picker 的 API 和参数（如 representation mode），列为冒烟测试项 |
| N5 | 外部集成第 384 行 | 申请了 `cdn:DescribeRefreshTasks` 权限，但设计里没用到；刷新配额和刷新本身是异步的（10 分钟目标），也没说明 | — | 要么用它确认刷新完成，要么删掉这项权限 |
| N6 | AD-2 | 没要求 `media_type` / `media_acl_*` 加 CHECK 约束、加一致性约束（VIDEO ⇒ `video_object_key` 非空），也没写列长度 | CLAUDE.md 关于 CHECK 和 CHAR(1) 的踩坑记录 | 补上迁移约束 |
| N7 | AD-11 第 201 行 vs PRD FR-19 | 架构写「需更新到最新版 + 商店链接」，PRD 写「在 App 中观看，跳转逻辑与现有一致 + 播放角标」 | V-37 | 统一措辞，并说明是否仍然尝试拉起 App |
| N8 | AD-11 / `VideoUrlResolver` | 没配 CDN 时用的「OSS 源站地址」从哪个配置拼？建议明确复用 `media.oss.cdn-base-url`（即 `AliyunOssClient.publicUrl`） | `AliyunOssClient.java:85-87` | 写明 |
| N9 | AD-3 限流 | `RedisRateLimiter` 抛的是通用的 rateLimited，不是 `video-upload-quota-exceeded`；「每天」按 UTC 还是 WIB 没定 | `RedisRateLimiter.java:30-36` | 新增专用异常类型；按 WIB 自然日 |
| N10 | AD-8 | 发帖接口的返回体 `ContentPostResponse`（`from()`）没有列出，发布成功页可能需要 `mediaType` | `ContentPostResponse.java:29` | 列入 DTO 清单 |
| N11 | 后台 | 评论审核里的帖子缩略图取 `imageUrls[0]`，封面是私有的时候会显示坏图 | `AdminCommentModerationService.java:156` | 纳入 I3 的后台签名方案 |
| N12 | PRD §8.3 | 「处理后视频约 25MB」是压缩时代的说法（V-33 已不在手机上处理） | PRD 第 394 行 | 改成「按导出规格约 2Mbps，90 秒约 25MB」 |

---

## 覆盖核对（FR → 架构落点）

| FR | 落点 | 结论 |
|---|---|---|
| FR-1 | AD-2 `vip_level DEFAULT 1` | ✅ |
| FR-2 | 后台抽屉 + 权限码 | ⚠️ 缺 1–99 范围和审计前后值（I11） |
| FR-3 | `/me` 加 vipLevel | ✅ |
| FR-4 | AD-3（凭证）、错误码 403 | ⚠️ 发帖时的等级校验没写进 AD-5（I7） |
| FR-22 | 后台抽屉 + 表 + 权限 | ❌ 缺名单列表页（I11） |
| FR-5 | AD-9 | ⚠️ 漏「打卡后发帖」入口（N3） |
| FR-6 | AD-9 + 规则 9 | ⚠️ 选择器 API 和 iOS 转码风险（N4） |
| FR-7 | AD-9 + addendum A + 样片测试 | ✅ |
| FR-8 | AD-9 + AD-4 | ✅（封面审核随视频，依赖 C3 / I7） |
| FR-9 | AD-3 + 过程规范 | ⚠️ 凭证有效期没有默认值（I16） |
| FR-10 | AD-5 | ❌ 类型、宠物、打卡、可见性、@、超时都有缺口（C2、C3、I7） |
| FR-11 | AD-3 / AD-4 | ✅ |
| FR-12 | AD-5 | ❌ 归属校验不存在（C1），校验项不全（I7） |
| FR-23 | AD-4 | ⚠️ 请求头回传（I4）；与 PRD 白名单不一致 |
| FR-14 | AD-10 卡片 + AD-7 | ⚠️ 通知缩略图不存在（I1）；尺寸口径（I8） |
| FR-15 | AD-10 | ⚠️ 后台暂停（N2） |
| FR-16 | AD-10 复用组件 | ⚠️ 路由分流、`focus=comments`（I9） |
| FR-17 | AD-6 + 后台模板 | ❌ 迁移点不符（C5）；后台预览私有视频（I3） |
| FR-18 | AD-7 | ❌ 漏分享帖 API 等出口（I1） |
| FR-19 | AD-11 H5 | ⚠️ 措辞（N7） |
| FR-20 | AD-11 | ⚠️ 条件装配有坑（I6） |
| FR-21 | AD-10 引用 | ❌ 定义冲突、补报和来源没有设计（I12） |
| §8.1 规格检查 ≤1 秒 | addendum A | ✅ |
| §8.1 起播 ≤2 秒 | faststart + CDN | ⚠️ 没有测量和验收方法（建议纳入 `video_play_start.ttffMs` 的 L2 验收） |
| §8.1 安装包增量 | 只加两个依赖 | ⚠️ 没有测量步骤（建议冒烟测试时记录 APK / IPA 的大小差） |
| §8.1 隐私 | 位置检查 + 日志不记签名 URL | ✅ |
| §8.1 可靠性 | 手动重试 + 退避 | ⚠️ 「后台回来不卡死的发帖页」没写 |
| §8.2 | AD-5 + 规则 6 | ✅（依赖 C3） |
| §8.3 成本 | AD-11 频控、封顶 | ✅ |

## 已核实正确的说法（不需要改）

- 官方号判断：`isOfficialAccount` 判断的是 `role=ADMIN`（`AccountQueryService.java:158-166`）。✅
- 版本比较函数只认三段纯数字（`AppVersionConfigService.java:96-106`）。✅
- App 内上传凭证只有一个调用点，只申请 `image/jpeg`（`media_upload_use_case.dart:105-107`）；后台模板不调用 upload-url。✅
- `ManualReviewGate.enqueue` 不读开关，可以无条件入队（C2 说的是超时那一环）。✅
- `ContentUnavailableEvent` 在作者删除、下架、审核丢弃时发出，没有「恢复」事件（`ContentService.java:149、414`）。✅
- 发帖最低门槛「文字或图片」（`ContentService.java:469-473`）在封面即首图之后天然满足。✅
- 推荐序列缓存在 Redis，翻页从快照读取（`FeedSequenceStore`），有候选池 / 科普池 / 降级回落三条路径。✅
- `TimelineCapabilities` 是 `supports=` 参数的先例。✅
- `ClientInfoInterceptor` 只挂在 API 的 Dio 上，OSS 直传用独立的 Dio。✅
- 宠物封面 SQL `image_urls->>0` 只查 `GROWTH_MOMENT`（`ContentPostRepository.java:147-160`），视频帖不会混进去。✅
