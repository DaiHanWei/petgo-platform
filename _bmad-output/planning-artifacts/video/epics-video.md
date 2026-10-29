---
title: "专题线 video · Epic 与 Story 拆分"
status: complete
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - _bmad-output/planning-artifacts/video/PRD-video.md
  - _bmad-output/planning-artifacts/video/architecture-video-delta.md
  - _bmad-output/planning-artifacts/video/addendum-video.md
  - _bmad-output/planning-artifacts/video/决策日志-video.md
  - _bmad-output/planning-artifacts/video/官方视频导出规格.md
  - _bmad-output/project-context.md
---

# TailTopia 短视频（专题线 video）- Epic 拆分

## 概览

本文档把 PRD-video、架构增量 architecture-video-delta 拆成可以直接实现的 Epic 和 Story。拆分规则：

- **编号**：FR 编号沿用 PRD 原编号。FR-13 已经移除，所以不出现。
- **权威顺序**：决策日志 > PRD > 架构 > 本文件。
- **UI 稿**：本专题没有 UI 稿（决策 V-29）。界面细节参照现有页面的风格实现，之后补 UI 稿再做还原度验收。

## 需求清单

### 功能需求

- **FR-1** 会员等级默认值：所有用户（存量和新注册）的会员等级默认为 1。
- **FR-2** 后台设置会员等级：持有「会员等级管理」权限的后台账号，可以在用户详情里把等级改为 1–99；每次修改都写审计日志（记录修改前后的值）；这个权限码是新增的，默认不分配给任何角色。
- **FR-3** App 获取会员等级：App 获取当前用户信息时，一并拿到会员等级；等级被修改后，最晚在下次冷启动或刷新个人信息时生效。
- **FR-4** 服务端校验发视频权限：等级 < 10 的账号申请视频上传或发布视频帖，一律返回 403；等级降回 1 之后不能再发新的视频帖，已经发布的不受影响。
- **FR-22** 后台维护视频免审名单：
  - 持有「视频免审名单管理」权限的后台账号，可以把用户加入或移出名单，也可以查看名单全量列表（列表显示会员等级）。
  - 每次改动都写审计日志。
  - 账号被移出名单后，之后发的视频帖要走人工审核。
- **FR-5** 「视频」入口：
  - 等级 ≥ 10 的账号在发帖页才能看到「视频」选项。
  - 视频和图片不能混发。
  - 从「打卡后发帖」或「成长日历」进入发帖页时，不显示视频入口。
- **FR-6** 选择视频：
  - 通过系统照片选择器从相册选择 1 个视频，不新增任何系统权限。
  - 时长不在 3–90 秒范围内，就提示并且不上传。
  - 视频帖不带宠物、打卡、事件日期。
- **FR-7** 视频规格检查：
  - App 读取 MP4 文件头，检查以下项：时长、体积 ≤ 100MB、短边 ≤ 720、H.264 / AAC / MP4、SDR、faststart、不含拍摄地点。
  - 1 秒内给出结果；不合格的逐条列出原因；合格的原样上传。
  - 不依赖新的视频处理库。
- **FR-8** 封面：
  - 默认取视频第 1 秒的画面，也可以换成相册里的一张图。
  - 格式 JPEG，短边 720。
  - 封面的审核方式跟随视频。
- **FR-9** 上传：
  - 规格检查通过后自动开始上传，显示进度百分比，可以取消。
  - 上传失败可以重试，重试时文案、类型、封面都保留。
  - 视频和封面都传完之前，「发布」按钮不可点。
  - 关闭发帖页前弹出确认，不留草稿。
- **FR-10** 发布视频帖：
  - 内容类型只能选日常或科普；文案和 @ 的规则与图文帖相同；视频帖一律公开。
  - 名单内的账号：免审，直接公开（正文也不审）。
  - 名单外、等级 ≥ 10 的账号：一律进入人工审核，不受总开关影响，3 天超时后丢弃。
  - 视频帖不触发 AI 自动评论，也不触发暖评。
- **FR-11** 上传申请的类型与权限：通用上传接口只接受图片类型；视频上传凭证只发给有发视频权限的账号。
- **FR-12** 发布视频帖时的服务端校验：
  - 发布者有发视频权限。
  - 只有 1 个视频和 1 张封面，不能混发。
  - 视频和封面都是本人上传的。
  - 视频的实际大小 ≤ 100MB。
  - 时长 3–90 秒，宽高为正数，短边 ≤ 720。
  - CDN 下发不能导致归属校验被绕过。
- **FR-23** 通用上传加固：
  - 类型白名单只放图片，`video/mp4` 等一律拒绝。
  - 上传凭证禁止覆盖已有文件。
  - 旧版 App 发图不受影响。
  - 视频凭证在 OSS 层面限定大小、类型和路径，并单独按天限流。
- **FR-14** 列表中的视频帖：
  - 推荐流（含分类 Tab）、关注流、他人主页、我的发布、顶置位里，视频帖都显示封面、播放角标和时长，**不自动播放**。
  - 列表里加载的是封面缩略图；封面比例规则与图文首图一致。
  - 推荐逻辑与图文帖相同，不额外加权；运营可以手动顶置视频帖。
- **FR-15** 全屏播放页：
  - 黑底，按原比例显示，App 保持竖屏。
  - 有声自动播放；有静音按钮，静音状态在本次会话内记住。
  - 单击暂停或继续；进度条可以拖动；播放结束后循环。
  - 加载时显示封面和转圈；15 秒内没出画面就判定失败，可以重试。
  - 用移动网络播放时不额外提示。
  - 离开页面就停止；App 切到后台时暂停，回到前台不自动续播。
  - 不做磁盘缓存。
- **FR-16** 播放页上的互动：
  - 作者（可以点进主页、可以关注）、正文、点赞和点赞人列表、评论抽屉（打开时视频继续播放）、分享、举报 / 删除，规则都与图文帖一致。
  - 从播放页返回时，列表停在原来的位置。
- **FR-17** 后台展示视频帖，文件的公开状态跟随帖子：
  - 后台的内容列表、内容抽屉、审核详情能识别视频帖，并能播放预览。
  - 下架、删除写审计日志，沿用举报预处置。
  - 文件什么时候公开、什么时候不公开，跟随帖子的可见状态：上传后和审核中不公开；下架、作者删除、预处置、审核拒绝或超时、删除用户（违规）、注销后不公开；恢复后重新公开。
  - 帖子不可见后，原链接最晚 10 分钟内失效；失败会自动重试，并告警。
- **FR-18** 对旧版客户端隐藏视频帖：
  - 请求里没有视频能力声明的客户端，看不到任何视频帖。
  - 覆盖范围：推荐流（含降级、科普池）、分类 Tab、关注流、主页、我的发布、顶置、详情、点赞人、评论（含回复）、App 内打开分享链接。
  - 隐藏后分页不能出现异常；详情等页面统一按「已删除」提示。
- **FR-19** 视频帖的分享页：H5 页面显示封面、播放角标和「需更新到最新版 App 观看」，网页里不播放；跳转逻辑与现有一致（先尝试拉起 App，拉不起来就去商店）。
- **FR-20** 视频走 CDN：
  - CDN 可以配置：配了就下发 CDN 地址并刷新缓存，没配就下发 OSS 源站地址；切换只需改配置。
  - stag 可以没有 CDN；**生产环境必须配好 CDN**。
  - 封面和图片走 OSS 源站。
  - 支持拖动进度条。
- **FR-21** 视频观看与发布埋点：
  - 事件包括：卡片曝光、开始播放（带起播耗时）、25 / 50 / 75%、播完、卡顿、离开（带观看秒数）、播放失败、规格检查结果、上传失败、发布。
  - 每个事件带 source、postId、App 版本。
  - 观看秒数只计实际播放的时间，上限为时长 × 3；App 被杀掉后下次启动补报。
  - 走统一的埋点白名单。

### 非功能需求

- **NFR-1** 选中视频后，规格检查在 1 秒内给出结果。
- **NFR-2** 正常 4G 网络下，从点开到出画面 ≤ 2 秒（起播耗时 `ttffMs`，L2 实测）。
- **NFR-3** 安装包增量：安卓 ≤ 3MB，iOS ≤ 5MB（只新增 `video_player` 和 `fc_native_video_thumbnail` 两个依赖）。
- **NFR-4** 隐私：日志和埋点不记录签名 URL 和个人信息；视频不含位置元数据。
- **NFR-5** 可靠性：规格检查或上传失败都不会导致崩溃，并且可以重试；App 切后台再回来，发帖页不会卡死。
- **NFR-6** 帖子不可见后，文件直链 ≤ 10 分钟失效；没有 CDN 时改 ACL 立即失效。
- **NFR-7** 上行 5Mbps 的 4G 网络下，100MB 视频能在上传凭证有效期内传完（凭证有效期默认 1800 秒）。
- **NFR-8** 成本：按导出规格，90 秒视频约 25MB，每 1000 次完整播放约产生 25GB 流量；CDN 配置流量封顶和告警。
- **NFR-9** 安全：发视频权限由服务端校验；视频和封面做归属校验；上传凭证禁止覆盖；视频 CDN 只放行 mp4，并加 `nosniff`。

### 架构附加需求

- **AR-1**（AD-1）封面即首图：`image_urls=[封面]`，`image_sizes` 由服务端用视频的显示宽高填写，`image_crop_offsets=null`，视频帖不进入尺寸兜底测量。
- **AR-2**（AD-2）时间戳迁移：
  - `content_posts` 新增视频列、`media_acl_*` 列和 `media_acl_desired_at`，加 CHECK 约束、一致性约束和部分索引；
  - `users.vip_level` 用 INTEGER，CHECK 1–99；
  - 新建表 `video_trusted_publishers`；
  - `review_reason` 新增取值 `VIDEO_FORCED`（如果有 CHECK 约束，要重建全集）。
- **AR-3**（AD-3）视频 PostObject：
  - 新增接口 `/media/video-upload-policy`；策略锁死完整 key，限定 ≤100MB、`video/mp4`、private、禁止覆盖，`success_action_status=200`；凭证有效期 1800 秒。
  - 限流：每个账号每天 50 次，按 WIB 自然日计。
  - App 端用独立的 Dio，`FormData` 流式上传。
- **AR-4**（AD-4）通用 upload-url：
  - 白名单只放图片；`x-oss-forbid-overwrite` 要签进签名，**同时放进返回的 headers**；
  - 新增参数 `purpose=VIDEO_COVER`：签成 private，并要求等级 ≥ 10。
- **AR-5**（AD-5）视频帖走 `publish()` 加 `skipModeration`，**不调用 `publishTrusted`**。
  - 按 `VideoPostValidator` 校验表逐项校验（错误码、在事务内还是事务外都已列明）；归属校验需要新写；HEAD 放在事务外。
  - 名单外的帖子：只跑文本审核，打上 `VIDEO_FORCED` 后强制入队；`scanTimeouts` 对 `VIDEO_FORCED` 不受总开关影响。
- **AR-6**（AD-6）`desiredOf(post)` 在实体的各个状态方法里推导；注销的批量 SQL 要显式写入 desired。
  - `MediaAclReconciler`：每分钟跑一次，每批 ≤100 条；回写用条件单列 UPDATE；对象 404 时收敛不再重试；超过 5 分钟写 ERROR 日志告警。
  - 发布、下架、恢复后，在事务外调用 `reconcileOne` 立即对账一次。
- **AR-7**（AD-7）能力声明：
  - App 发送 `X-App-Capabilities: video`。
  - 后端只在 Controller 层解析，解析出 `includeVideo` 后作为显式参数往下传。
  - 过滤出口按 AD-7 表逐个实现。序列键 `feed:seq:{ns}:v{0|1}:{seed}` 带上能力维度，但 namespace 不变。
  - 顶置按 id 取，作为例外处理：取出后再判断是否过滤。
- **AR-8**（AD-8）DTO：
  - 以下 DTO 增加 `mediaType` 和 `video`：`FeedItemResponse`、`ContentDetailResponse`、`SharedPostResponse`（新增 `of()`）、`ContentPostResponse`。
  - `VideoUrlResolver` 负责拼地址：公开时用 CDN 或 OSS 源站地址；作者看到不公开的帖子时，视频和封面都用签名 URL；后台用 `forAdmin`。
  - `SignedUrlService` 新增 `sign(bucket, key, ttl)` 重载。
  - 请求体相关的 6 个构造器都要补上新字段。
- **AR-9**（AD-9 / AD-10）App 端：
  - 选视频用 `ImagePicker.pickVideo`，冒烟时验证 iOS 拿到的是原样文件。
  - 详情页先拉详情，再按 `mediaType` 渲染 `VideoPostPage`。
  - 埋点按 AD-10 的埋点表实现，`source` 枚举通过 `extra` 或 `src` 传递，补报的数据存在 `shared_preferences`。
- **AR-10**（AD-11）CDN 开关：通过 `@Bean` 按 `hasText` 判断选择实现（**不要用 `@ConditionalOnProperty`**）；需要「空字符串 → 走 Noop」的测试；stag 存储生命周期只作用于 `stag/video/`。
- **AR-11**（AD-12）后台：
  - 用户抽屉里可以改等级、加入或移出名单；
  - 新增 `/admin/video-trusted` 列表页；
  - 新增两个权限码：`user.vip_level.edit`、`video.trusted.manage`；
  - 内容和评论审核的缩略图用签名 URL。
- **AR-12** 一致性规则 1–9，以及架构「实现规范」一节里的命名、错误码、配置键。
- **AR-13** 测试策略：按架构「测试策略」表执行，每一项都标了 L0 / L1 / L2。
- **AR-14** 运营交付物：`官方视频导出规格.md`；首次导出要做实测验收（PRD §10 第 1 条）。
- **AR-15** 外部依赖：
  - 对账功能开发时，RAM 需要追加 `oss:PutObjectAcl` 和 `oss:GetObjectAcl`。
  - CDN 依赖企业认证，**会阻塞生产上线，不阻塞开发和 stag 验收**。

### UX 设计需求

本专题没有 UX 文档和 UI 稿（决策 V-29）。界面相关的要求都已写进 FR-5 ~ FR-9、FR-14 ~ FR-16、FR-19 的可测条件；视觉上沿用现有发帖页、详情页和卡片的风格。

### FR 覆盖映射

| FR | Epic | 说明 |
|---|---|---|
| FR-1 | Epic 1 | 会员等级默认值 |
| FR-2 | Epic 1 | 后台设置等级 |
| FR-3 | Epic 1 | App 获取等级 |
| FR-4 | Epic 1 | 服务端发视频权限 |
| FR-22 | Epic 1 | 视频免审名单 |
| FR-5 | Epic 1 | 「视频」入口 |
| FR-6 | Epic 1 | 选择视频 |
| FR-7 | Epic 1 | 视频规格检查 |
| FR-8 | Epic 1 | 封面 |
| FR-9 | Epic 1 | 上传 |
| FR-10 | Epic 1 | 发布与审核路由 |
| FR-11 | Epic 1 | 上传类型与权限 |
| FR-12 | Epic 1 | 发帖服务端校验 |
| FR-23 | Epic 1 | 通用上传加固 |
| FR-14 | Epic 2 | 列表中的视频帖 |
| FR-15 | Epic 2 | 全屏播放页 |
| FR-16 | Epic 2 | 播放页互动 |
| FR-18 | Epic 2 | 对旧版隐藏视频帖 |
| FR-19 | Epic 2 | 分享页 |
| FR-17 | Epic 3 | 后台展示与文件可见性闭环 |
| FR-20 | Epic 3 | CDN 开关 |
| FR-21 | Epic 1 + Epic 2 | 发布类埋点在 Epic 1，观看类埋点在 Epic 2 |

## Epic 列表

### Epic 1：官方号能在 App 里发布视频帖
运营用官方号选视频，经过规格检查、更换封面、上传后发布。名单内的账号发布后立即公开；名单外的账号进入人工审核。后台可以设置会员等级、维护免审名单。上传链路完成安全加固。
**覆盖的 FR：** FR-1、FR-2、FR-3、FR-4、FR-22、FR-5、FR-6、FR-7、FR-8、FR-9、FR-10、FR-11、FR-12、FR-23、FR-21（发布类埋点）
**对应架构：** AD-1 ~ AD-5、AD-6（发布路径）、AD-9、AD-12（会员等级与名单）

### Epic 2：用户能刷到并观看视频
新版 App 的列表里能看到视频卡片，点进去全屏播放，可以点赞、评论、分享。旧版 App 完全看不到视频帖。H5 分享页提示升级。
**覆盖的 FR：** FR-14、FR-15、FR-16、FR-18、FR-19、FR-21（观看类埋点）
**对应架构：** AD-7、AD-8、AD-10
**依赖：** Epic 1

### Epic 3：视频帖的管理与文件安全闭环
后台可以预览视频。下架、删除、预处置、审核拒绝或超时、删除用户、注销后，视频文件自动改为不公开；恢复后重新公开。名单外的帖子超时后丢弃。CDN 可通过配置开关切换。
**覆盖的 FR：** FR-17、FR-20
**对应架构：** AD-6（全部迁移入口 + 对账器）、AD-11、AD-12（预览与签名 URL）
**依赖：** Epic 1（与 Epic 2 互不依赖，可以并行）

## Epic 1：官方号能在 App 里发布视频帖

运营用官方号在 App 里选视频，经过规格检查、更换封面、上传后发布。免审名单内的账号发布后立即公开；名单外的账号进入人工审核。后台可以设置会员等级、维护免审名单。上传链路同时完成安全加固。

### Story 1.1：后台设置会员等级与视频免审名单

As a 有权限的运营，
I want 在后台把官方号设为等级 10 并加入视频免审名单，
So that 官方号获得发视频的权限，发出的视频直接公开。

**范围**：只改后端和后台。涉及 FR-1、FR-2、FR-3（接口部分）、FR-22；架构 AD-2（vip_level 与名单表）、AD-12。

**Acceptance Criteria:**

**Given** 迁移已执行
**When** 查询存量用户和新注册用户
**Then** `users.vip_level` 都为 1，列类型为 INTEGER，带 CHECK 1–99；`video_trusted_publishers` 表已存在（user_id 主键，外键级联删除）【L1】

**Given** 后台账号持有 `user.vip_level.edit` 权限
**When** 在用户抽屉里把某用户的等级改为 10
**Then** 保存成功，审计日志记录操作人、用户、`vip_level 1→10`；输入 0 或 100 时返回校验错误【L1】
**And** 没有该权限的账号看不到这个入口；直接调用接口返回 403【L1】

**Given** 后台账号持有 `video.trusted.manage` 权限
**When** 在用户抽屉里把用户加入或移出免审名单
**Then** 名单表同步变化，每次操作都写审计日志【L1】
**And** `/admin/video-trusted` 列表页显示账号、昵称、会员等级、加入人、加入时间；没有该权限时返回 403【L1】

**Given** 两个新权限码已加到后台权限常量里
**When** 查看任意角色的默认权限
**Then** 这两个权限码默认都不分配给任何角色【L0】

**Given** 用户已登录
**When** 请求 `/api/v1/me`
**Then** 响应里包含 `vipLevel`【L1】

### Story 1.2：上传链路加固与视频上传策略

As a 平台，
I want 通用上传只接受图片并禁止覆盖，视频走独立的、受严格约束的上传策略，
So that 上传链路不会被当成免费网盘用，也不能在审核之后偷换内容。

**范围**：只改后端。涉及 FR-11、FR-23；架构 AD-3、AD-4，一致性规则 7。

**Acceptance Criteria:**

**Given** 调用 `POST /api/v1/media/upload-url`
**When** contentType 为 `image/jpeg|png|webp|heic`
**Then** 正常签发，响应的 `headers` 里同时包含 `x-oss-forbid-overwrite: true`，且该头已签进签名【L1】
**And** contentType 为 `video/mp4`、`text/html` 或为空时，返回 422 `upload-content-type-not-allowed`【L1】

**Given** 用 1.3.4 版 `OssUploader` 的方式（原样带回 `ticket.headers`）上传
**When** 第一次 PUT
**Then** 成功；用同一个凭证再 PUT 一次，被 OSS 拒绝，原文件保持不变【L2：真实 OSS】

**Given** 请求时带 `purpose=VIDEO_COVER`
**When** 调用者的等级 ≥ 10
**Then** 签发的凭证 ACL 为 private；等级 < 10 时返回 403 `video-permission-denied`【L1】

**Given** 等级 ≥ 10 的用户调用 `POST /api/v1/media/video-upload-policy`
**When** 签发策略
**Then** 返回 `{host, key, fields}`。key 的形式为 `{keyPrefix}video/{userId}/{16字节随机}.mp4`。策略要求：完整 key 用 eq 匹配，`content-length-range ≤ 104857600`，`Content-Type=video/mp4`，`x-oss-object-acl=private`，`x-oss-forbid-overwrite=true`，`success_action_status=200`；有效期默认 1800 秒【L1】
**And** 等级 < 10 时返回 403；同一账号在同一个 WIB 自然日内超过 50 次时，返回 429 `video-upload-quota-exceeded`【L1】

**Given** 拿到视频上传策略
**When** 上传超过 100MB 的文件、非 mp4 文件、改写过 key 的文件，或者向同一个 key 重复上传
**Then** 全部被 OSS 拒绝【L2：真实 OSS】

### Story 1.3：视频帖发布接口（校验与审核路由）

As a 官方号，
I want 用视频和封面发布一条视频帖，
So that 视频帖能进入社区；名单内直接公开，名单外进入人工审核。

**范围**：只改后端。涉及 FR-4、FR-10、FR-12；架构 AD-1、AD-2（content_posts 列）、AD-5，以及 AD-6 中的 `desiredOf` 初始化和实体推导部分。

**Acceptance Criteria:**

**Given** 迁移已执行
**When** 查看 `content_posts`
**Then** 已有 `media_type`（默认 IMAGE，带 CHECK）、`video_object_key / duration_ms / width / height / size_bytes`、`media_acl_desired / applied / desired_at`，加上一致性 CHECK 和部分索引；`review_reason` 的可选值加入 `VIDEO_FORCED`（如果有 CHECK，要按全集重建）；存量数据不受影响【L1】

**Given** `ContentPostCreateRequest` 增加了 `mediaType` 和 `video{objectKey,durationMs,width,height}`
**When** 编译
**Then** 6 个兼容构造器都已补齐，现有调用方无需改动【L0】

**Given** 名单内的等级 10 账号提交视频帖（`imageUrls=[本人上传的私有封面]`，video 指向本人上传的 mp4）
**When** 调用 `POST /api/v1/content-posts`
**Then** 走 `publish()` 主流程，跳过文本审核和图片审核，状态为 PUBLISHED；`image_sizes=[{video_width,video_height}]`（服务端写入）；`image_crop_offsets=null`；desired=PUBLIC，applied=PRIVATE；@ 名单和 @ 通知都正常；**没有调用 `publishTrusted`**【L1】
**And** 不触发尺寸兜底测量，不生成 AI 自动评论和暖评【L1】

**Given** 名单外的等级 10 账号提交视频帖
**When** 发布
**Then** 先执行文本审核（L1 词库命中仍然拦截），跳过图片审核；状态为 UNDER_REVIEW，`review_reason=VIDEO_FORCED`，已写入人工审核队列；desired=PRIVATE；**人工审核总开关关闭时也照样入队**【L1】

**Given** AD-5 校验表中的每一条失败情况（等级 < 10、混发、类型不是日常或科普、带了宠物 / 打卡 / 日期、cropOffsets 不是 null、时长或宽高越界、视频 key 不属于本人、封面不属于本人、HEAD 不存在 / 类型不对 / 超过 100MB）
**When** 逐条提交
**Then** 返回 AD-5 表中对应的错误码，且不会产生任何帖子【L1】
**And** HEAD 调用发生在事务之外（单测里验证事务边界）【L0】

**Given** `desiredOf(post)` 纯函数
**When** 对「状态 × deletedAt × visibility」的组合逐一断言
**Then** 只有 PUBLISHED、未删除、PUBLIC 三个条件同时满足时返回 PUBLIC，其余都返回 PRIVATE；`ContentPost` 的各个状态方法都会在内部重算 desired 并刷新 `desired_at`（只对 VIDEO 生效）【L0】

### Story 1.4：视频文件公开对账（发布路径）

As a 官方号，
I want 视频帖发布后，视频和封面马上就能被别人看到，
So that 发布成功后去推荐流里能看到正常的封面和视频。

**范围**：只改后端。涉及 FR-10、FR-17（发布部分）；架构 AD-6 的对账器、AD-8 的 `VideoUrlResolver` 基础、AD-11 的 Noop 刷新器与开关；一致性规则 1。

**Acceptance Criteria:**

**Given** `MediaAclReconciler` 每分钟运行一次
**When** 存在 `media_type=VIDEO` 且 desired ≠ applied 的帖子
**Then** 每批最多处理 100 条：依次设置视频对象 ACL、设置封面对象 ACL、调用 `CdnCacheRefresher.refresh`，最后用**条件单列 UPDATE**（`WHERE desired=:v`）回写 applied【L1】
**And** 如果回写时 desired 已经被并发修改（影响 0 行），这一条留到下一轮处理；单条失败不阻塞其他帖子；对象返回 404 时打 ERROR 日志并收敛，不再无限重试【L0 + L1，OSS 打桩】
**And** 超过 5 分钟仍不一致时，打 ERROR 日志 `video_acl_reconcile_stuck`；日志里不包含签名 URL【L0】

**Given** 名单内账号发布视频帖成功
**When** 发布接口的事务提交后，在 Controller 层调用 `reconcileOne(postId)`
**Then** 视频和封面马上变为 public-read，applied=PUBLIC；如果这一步失败，不影响发布结果，由定时任务兜底【L1】

**Given** `media.video.cdn-base-url` 为空字符串（yml 写法是 `${ENV:}`）
**When** 应用启动
**Then** 装配的是 `NoopCdnCacheRefresher`；配置有值时装配 `AliyunCdnCacheRefresher`。判断方式是在 `@Bean` 里按 `hasText` 选择，**不用 `@ConditionalOnProperty`**【L0】

**Given** 新建的 `VideoUrlResolver`（Epic 2、Epic 3 都会用到，所以在这里先建好）
**When** 调用 `publicUrl(post)`、`signedUrl(post, ttl)` 或 `forAdmin(post)`
**Then** 公开地址按「有 CDN 就用 CDN，否则用 OSS 源站」拼接对象 key；签名地址的有效期取 max(15 分钟, 时长 × 3)；`SignedUrlService` 新增 `sign(bucket, key, ttl)` 重载；日志里不打印签名 URL【L0】

**Given** RAM 用户已追加 `oss:PutObjectAcl` / `oss:GetObjectAcl` 权限
**When** 在 stag 上发布一条视频帖
**Then** 1 分钟内（通常是立刻）可以匿名访问视频和封面的源站地址【L2：stag】

### Story 1.5：App 视频入口与规格检查

As a 官方号运营，
I want 在发帖页选一个视频并立即知道它合不合规，
So that 不合规的视频在上传前就被拦下，我也知道该怎么重新导出。

**范围**：只改 App 端。涉及 FR-3（App 部分）、FR-5、FR-6、FR-7；架构 AD-9、addendum A；一致性规则 8、9。

**Acceptance Criteria:**

**Given** `UserProfile` 增加了 `vipLevel`
**When** 等级 ≥ 10 的账号打开发帖页
**Then** 能看到「视频」选项；等级为 1 的账号在任何状态下都看不到【L0 widget 测试 + L2 模拟器】
**And** 已经选了图片时「视频」不可用；已经选了视频时「添加图片」不可用，「成长日记」类型也不可选；从成长日历、打卡后发帖、`preset=growth-calendar` 进入时，不显示视频入口【L0】

**Given** 点击「视频」
**When** 调用 `ImagePicker.pickVideo(source: gallery)`
**Then** 不申请任何新的系统权限（Manifest 和 Info.plist 都没有新增视频或麦克风相关条目）【L0：检查清单文件 diff】
**And** iOS 上选到的文件与相册里的原文件逐字节一致；如果不一致，记录风险，并改用能保留原文件的方式【L2：iOS 真机】

**Given** 纯 Dart 写的 `video_spec_checker`（不引入新依赖，按文件路径读取，不把整个文件读进内存）
**When** 用 `test/fixtures/video/` 里的样片测试（avc 合规 / hevc / HDR / 非 faststart / 带位置信息 / 旋转 / 无音轨 / 超时长 / 超大 / 剪映导出样片）
**Then** 每个样片的判定结果和失败项都正确；旋转视频按显示方向计算短边【L0】
**And** 单个文件的检查在 1 秒内完成【L2】

**Given** 视频不合规
**When** 检查结束
**Then** 逐条显示不合格的项和正确要求（例如「编码是 HEVC，需要 H.264，请按导出规格重新导出」），不会开始上传；合规时进入封面和上传流程（由 Story 1.6 实现，这里只需留好接入点）【L0】

### Story 1.6：App 封面、上传与发布

As a 官方号运营，
I want 用默认封面或自己换的封面，把视频上传并发布，
So that 视频帖能出现在社区里。

**范围**：只改 App 端。涉及 FR-8、FR-9、FR-10（App 部分）、FR-21（发布类埋点）；架构 AD-3（App 部分）、AD-4（封面）、AD-9、AD-10 埋点表。

**Acceptance Criteria:**

**Given** 视频已通过规格检查
**When** 用 `fc_native_video_thumbnail` 截取第 1 秒的画面
**Then** 默认封面为 JPEG、短边 720；点「更换」可以从相册选一张图，选中的图走现有处理流程（去 EXIF、压缩），并缩放到短边 720【L0 + L2】

**Given** 申请到视频上传策略
**When** 用独立的 Dio（connectTimeout 15 秒、sendTimeout 600 秒）通过 `FormData` 上传（表单字段在前，`file` 在最后，`MultipartFile.fromFile`）
**Then** 能显示进度百分比，可以取消；整个过程不把视频读入内存【L0 + L2】
**And** 封面通过 `upload-url?purpose=VIDEO_COVER` 上传，和视频上传并行进行【L1 联调】

**Given** 上传失败
**When** 用户点「重试」
**Then** 重新申请凭证并从头上传（申请接口按 1 / 2 / 4 秒退避）；文案、类型、封面都保留；视频和封面都上传成功之前，「发布」按钮不可点【L0】
**And** 上传中关闭发帖页时弹出确认，确认后放弃，不留草稿【L0】
**And** App 切到后台再回来，上传要么继续，要么显示「重试」，不会卡住【L2】

**Given** 视频和封面都已上传成功
**When** 点击发布
**Then** 请求体带上 `mediaType=VIDEO`、`video{objectKey,durationMs,width,height}`，`imageUrls=[封面]`，文案可以为空；发布成功后进入现有的发布成功页，并刷新推荐流和「我的发布」【L1 联调 + L2】
**And** 收到 403 `video-permission-denied` 时，提示「你暂时没有发布视频的权限」，退出前保留已填写的内容【L0】

**Given** 埋点白名单已登记
**When** 完成一次规格检查、一次上传失败、一次发布
**Then** 分别上报 `video_spec_check{ok, failedItems}`、`video_upload_error{errorType}`、`video_publish{durationMs, sizeBytes, uploadMs}`，都带 App 版本，且不包含个人信息【L0】

**Given** 用剪映按《官方视频导出规格》导出一段视频
**When** 官方号在 stag 上完整走一遍发布流程
**Then** 一次通过规格检查并发布成功；推荐流里出现这条视频帖的封面（这个阶段还没有播放页）【L2：stag 真机 / 模拟器】

## Epic 2：用户能刷到并观看视频

新版 App 的列表里能看到视频卡片，点进去全屏播放，可以点赞、评论、分享。旧版 App 完全看不到视频帖。H5 分享页会提示升级。

### Story 2.1：帖子接口下发视频信息与签名地址

As a 新版 App，
I want 帖子接口告诉我哪条是视频帖、视频地址是什么，
So that 我能区分视频帖并播放。

**范围**：只改后端。涉及 FR-14、FR-15（数据部分）；架构 AD-8、AD-11（地址拼接）；一致性规则 4。

**Acceptance Criteria:**

**Given** 以下 DTO 增加 `mediaType` 和 `video{url,durationMs,width,height}`：`FeedItemResponse.of()`、`ContentDetailResponse` 的 2 个 `of()`、`ContentPostResponse.from()`，以及 `SharedPostResponse`（新增 `of()`，`ContentShareService` 改为调用它）
**When** 请求图文帖
**Then** `mediaType=IMAGE`，不下发 `video`（NON_NULL），其余字段与改动前完全一致【L0 + L1】

**Given** 一条视频帖已公开（applied=PUBLIC）
**When** 任何人请求
**Then** `video.url` 由 `VideoUrlResolver`（Story 1.4 已建）生成：配置了 CDN 时用 `media.video.cdn-base-url` 拼对象 key，没配置时用 OSS 源站地址（`media.oss.cdn-base-url`）拼对象 key；`firstImageUrl` 就是封面【L1】

**Given** 作者本人请求一条处于 PRIVATE 状态的视频帖（审核中、预处置中，或 ACL 还没生效）
**When** 请求列表或详情
**Then** `video.url`、`imageUrls[0]`、`firstImageUrl` 都换成签名 URL，有效期取 max(15 分钟, 时长 × 3)；其他人请求这条帖子时，按现有可见性规则处理【L1】

### Story 2.2：视频能力声明与旧版过滤

As a 旧版 App 用户，
I want 根本看不到我播放不了的视频帖，
So that 我不会点开一个放不出来的东西。

**范围**：后端加上 App 拦截器的一行改动。涉及 FR-18；架构 AD-7；一致性规则 2、3。

**Acceptance Criteria:**

**Given** 新版 App
**When** 发出任何 API 请求
**Then** `ClientInfoInterceptor` 在请求头里带上 `X-App-Capabilities: video`；OSS 直传请求不带这个头【L0】

**Given** 后端只在 Controller 层解析 `ClientCapabilities`，得到 `includeVideo`
**When** 请求经过 AD-7 出口表里的每一个出口（推荐候选池、科普池、作者本人置顶插入、降级回落、分类 Tab、关注流、他人主页、我的发布、顶置、详情、点赞人、评论、回复、App 内分享链接）
**Then** 不带视频能力时，列表类出口在查询的 WHERE 条件里排除视频帖；详情、点赞人、评论、回复、分享链接返回统一 404；顶置按 id 取出后判断，如果是视频帖就当作没有顶置【L1 能力过滤矩阵测试】
**And** 带视频能力时，以上出口都能正常返回视频帖【L1】

**Given** 推荐序列缓存
**When** 同一个用户分别用新版和旧版刷推荐流
**Then** 序列缓存键是 `feed:seq:{ns}:v{0|1}:{seed}`，两个版本互不串用；曝光记录（`FeedSeenStore` 的 namespace）不受影响，不会被拆成两份【L1】
**And** 旧版连续翻页时，不会出现空页，也不会提前显示「没有更多了」【L1】

**Given** 服务层和仓库层
**When** 代码评审
**Then** 没有任何地方注入请求级的 `ClientCapabilities` bean，也没有在内存里过滤视频帖或在读取序列时过滤（顶置除外）【L0 评审项】

### Story 2.3：列表里的视频卡片

As a 用户，
I want 在推荐流和主页里一眼认出视频帖，
So that 我知道点进去可以看视频。

**范围**：只改 App 端。涉及 FR-14、FR-21（曝光部分）；架构 AD-10。

**Acceptance Criteria:**

**Given** `FeedItem`、`ContentDetail`、`SharedPost` 都增加了 `mediaType` 和 `video`
**When** 解析接口返回的数据
**Then** 以 `mediaType` 判断是不是视频帖，不用 `video != null` 判断；遇到未知的 mediaType，按图文帖处理【L0】

**Given** 推荐流（含分类 Tab）、关注流、他人主页、我的发布、顶置卡片
**When** 渲染一条视频帖
**Then** 显示封面缩略图，加上播放角标和「分:秒」格式的时长（例如 `1:15`）；**不自动播放**；卡片比例按封面尺寸（即视频显示宽高）计算，超出现有比例范围时居中裁切【L0 widget + L2 模拟器】

**Given** 视频卡片露出一半以上
**When** 在同一次刷新中第一次满足这个条件
**Then** 上报 `video_card_impression{postId, source}`，同一次刷新内不重复上报【L0】

**Given** 点击视频卡片
**When** 跳转到 `/content/:id`
**Then** 通过 `extra` 带上 `mediaType=VIDEO` 和 `source`；从播放页返回后，列表停留在原来的滚动位置【L0 + L2】

### Story 2.4：全屏播放页

As a 用户，
I want 点开视频帖后流畅地全屏观看，
So that 我能看完一段宠物短剧或科普。

**范围**：只改 App 端。涉及 FR-15、FR-21（观看部分）；架构 AD-10；NFR-2。

**Acceptance Criteria:**

**Given** 打开 `/content/:id`
**When** 帖子是视频帖（从 `extra` 得知，或者拉取详情后得知）
**Then** 在页面内渲染 `VideoPostPage`。从通知、推送、深链进入时，先拉取详情再分流；`focus=comments` 时自动打开评论抽屉；App 内分享页遇到视频帖时，跳到 `/content/:id`【L0 + L2】

**Given** 进入 `VideoPostPage`
**When** 视频开始加载
**Then** 黑底；视频按原比例显示，横屏视频上下留黑边；App 保持竖屏；加载期间显示封面和转圈；有声自动播放【L2】
**And** 单击暂停或继续；有可拖动的进度条；播完后循环播放；15 秒内没出画面时显示「视频加载失败，点击重试」；用移动网络播放时不额外提示【L0 widget + L2】
**And** 有静音按钮，静音状态在本次会话内记住，App 重启后恢复为有声【L0】
**And** 离开页面时停止播放；App 切到后台时暂停，回到前台不自动续播；不做磁盘缓存【L2】

**Given** 埋点
**When** 播放视频
**Then** 上报以下事件，都带 source 和 App 版本：`video_play_start{ttffMs}`、`video_progress{pct}`（25 / 50 / 75 各上报一次）、`video_complete{loop}`、`video_buffering{durationMs}`、`video_play_error{errorType}`【L0】
**And** 离开播放页时上报 `video_watch_time{seconds, occurredAt}`。seconds 只计实际播放的时长（暂停、在后台、缓冲时不计），单次上限是视频时长 × 3；如果 App 被杀掉，`shared_preferences` 里只保留最后一条未上报的记录，下次启动时补报，补报后删除【L0】

**Given** 正常的 4G 网络，播放 stag 上的视频帖
**When** 点开
**Then** `ttffMs` ≤ 2 秒；拖动进度条正常（源站支持分段请求）【L2：真机】

### Story 2.5：播放页上的互动

As a 用户，
I want 在看视频的时候点赞、评论、分享、关注作者，
So that 我能像对图文帖一样参与互动。

**范围**：只改 App 端。涉及 FR-16；架构 AD-10。

**Acceptance Criteria:**

**Given** `VideoPostPage` 的叠加层
**When** 页面渲染
**Then** 显示作者区（可以点进主页、可以关注）、正文、点赞和点赞人列表、评论、分享、举报或删除。复用详情页的现有组件，规则和图文帖一致：作者本人看到「删除」，其他人看到「举报」【L0 widget + L2】

**Given** 点击评论图标
**When** 评论抽屉弹出
**Then** 视频继续播放；关闭抽屉后回到播放状态【L2】

**Given** 游客
**When** 播放视频，然后点击点赞、评论或关注
**Then** 视频可以正常播放；点赞、评论、关注这些互动按现有登录墙处理【L0 + L2】

### Story 2.6：视频帖的 H5 分享页

As a 拿到分享链接的人，
I want 在网页上看到视频帖的封面，并被引导去 App 里观看，
So that 我知道这是一个视频，以及怎么看到它。

**范围**：只改后端模板。涉及 FR-19；架构 AD-11（H5 部分）。

**Acceptance Criteria:**

**Given** 打开一条视频帖的分享页（`content_share.html`）
**When** 页面渲染
**Then** 显示封面（带播放角标）、正文，以及「需更新到最新版 App 观看」的提示；网页里不播放视频【L1 + L2 浏览器】
**And** 「在 App 中观看」按现有逻辑先尝试拉起 App，拉起失败时跳转到对应平台的商店（商店地址取自 `app_version_config`）【L2】

**Given** 打开一条图文帖的分享页
**When** 页面渲染
**Then** 与改动前完全一致【L1】

## Epic 3：视频帖的管理与文件安全闭环

运营在后台可以预览视频帖。视频帖在下架、删除、预处置、审核拒绝或超时、删除用户、注销后，文件自动改为不公开；恢复后重新公开。名单外账号发的视频帖超时会被丢弃。CDN 做成开关，接入时只改配置。

### Story 3.1：所有可见性变化都带动文件公开状态

As a 运营，
I want 视频帖一旦对公众不可见，视频文件也同时不能被直接访问，
So that 下架的内容不会通过原链接继续传播。

**范围**：只改后端。涉及 FR-17（文件部分）；架构 AD-6 的入口表；NFR-6。

**Acceptance Criteria:**

**Given** AD-6 入口表里的每个入口（作者删除、运营下架 ×2、删除用户（违规）、恢复、审核通过 / 丢弃、举报预处置 / 解除、注销）
**When** 逐个触发
**Then** 视频帖的 `media_acl_desired` 都等于 `desiredOf(post)`，并刷新 `desired_at`【L1 可见性迁移测试】
**And** 注销用的批量 JPQL UPDATE 也显式写入 `media_acl_desired='PRIVATE'` 和 `desired_at`【L1】
**And** 一条在「审核中被丢弃」的帖子被 `restore` 后，状态仍是 UNDER_REVIEW，desired 推导结果仍为 PRIVATE【L0】

**Given** 单纯停用账号（suspend 或 deactivate）
**When** 执行
**Then** 帖子和 desired 都不变，视频仍然可以看到，与图文帖的现有行为一致（决策 V-39）【L1】

**Given** 下架或恢复接口
**When** 事务提交后
**Then** 在 Controller 层调用一次 `reconcileOne(postId)`；失败时由定时任务兜底【L1】

**Given** 在 stag 上下架一条已公开的视频帖
**When** 等待对账完成
**Then** 没有 CDN 时，视频和封面的源站地址马上返回 403；恢复上架后可以重新访问【L2：stag】

### Story 3.2：名单外视频帖的审核超时

As a 平台，
I want 没人处理的名单外视频帖在 3 天后自动丢弃，
So that 它们不会一直挂在「审核中」。

**范围**：只改后端。涉及 FR-10（超时部分）；架构 AD-5。

**Acceptance Criteria:**

**Given** 人工审核总开关是**关闭**的
**When** 一条 `review_reason=VIDEO_FORCED` 的视频帖在审核队列里超过 3 天
**Then** `scanTimeouts` 照常把它丢弃，作者收到现有的超时通知，desired 变为 PRIVATE【L1】
**And** 其他类型的帖子在总开关关闭时不受影响，仍按现有逻辑处理【L1】

**Given** 运营在后台审核通过或拒绝一条 VIDEO_FORCED 视频帖
**When** 操作完成
**Then** 通过时 desired=PUBLIC 并立即对账，拒绝时 desired=PRIVATE【L1】

### Story 3.3：后台预览与识别视频帖

As a 运营，
I want 在后台的内容列表、内容抽屉和审核详情里直接看到并播放视频帖，
So that 我能判断内容是否需要下架或通过审核。

**范围**：后端 + 后台模板。涉及 FR-17（后台部分）；架构 AD-8（`forAdmin`）、AD-12。

**Acceptance Criteria:**

**Given** 后台的内容列表（`content-list.html`）
**When** 某一行是视频帖
**Then** 显示封面和「视频」标识，不会显示成一张坏图【L1 + L2 浏览器】

**Given** 内容抽屉（`drawer-content.html`）和审核详情（`review-detail.html`）
**When** 打开一条视频帖（包括私有、审核中、已下架的）
**Then** 用 `<video controls>` 播放，地址来自 `VideoUrlResolver.forAdmin(post)`（Story 1.4 已建）生成的签名 URL；封面也使用签名 URL【L1 + L2】
**And** 评论审核里的帖子缩略图同样使用签名 URL【L1】

**Given** 顶置管理（`AdminContentPinService`）
**When** 选中一条视频帖进行顶置
**Then** 显示封面和视频标识，顶置功能正常【L1】

### Story 3.4：视频 CDN 接入

As a 平台，
I want 配置好 CDN 之后，视频改从 CDN 下发，下架时刷新 CDN 缓存，
So that 生产环境的视频播放更快、流量更便宜，下架后的文件也能及时失效。

**范围**：后端 + 运维配置。涉及 FR-20；架构 AD-11；NFR-6、NFR-8、NFR-9。**生产上线的前提；依赖阿里云企业认证（PRD §10 第 2 条）。开发和 stag 验收不需要等企业认证。**

**Acceptance Criteria:**

**Given** `AliyunCdnCacheRefresher`
**When** 对账器要求刷新某个视频 URL
**Then** 通过 `aliyun-java-sdk-core` 的通用请求调用 `RefreshObjectCaches`（ObjectType=File）；失败时抛出异常，由对账器重试【L0 打桩】

**Given** 在某个环境配置了 `media.video.cdn-base-url`
**When** 应用启动并下发视频帖
**Then** 装配 Aliyun 实现，`video.url` 使用 CDN 域名；封面和图片仍然走 OSS 源站；去掉配置后自动退回到源站地址 + Noop，库里的数据不需要迁移【L0 + L1】

**Given** CDN 已经按配置清单开通（回源到 OSS，不开启私有 Bucket 回源，只放行 `(stag/)?video/*.mp4`，支持分段请求，带 `nosniff`，配置了频控和封顶）
**When** 在接入 CDN 的环境里验收
**Then** 视频返回 200，并带 `Accept-Ranges` 和 `nosniff`；Range 请求返回 206；非视频路径返回 403；下架后 10 分钟内返回 403【L2：依赖企业认证】
**And** RAM 已追加 `cdn:RefreshObjectCaches` 权限【L2】

**Given** stag 存储
**When** 配置生命周期规则
**Then** 规则只作用于 `stag/video/` 前缀，不影响 stag 的图片（具体规则待 PRD §10 第 3 条定案后再配）【L2：运维】


## 最终校验（2026-10-16）

- **FR 覆盖**：22 条 FR 全部落到 Story。
  - FR-1/2/22 → 1.1；FR-3 → 1.1、1.5；FR-4 → 1.2、1.3；FR-5/6/7 → 1.5；FR-8/9 → 1.6
  - FR-10 → 1.3、1.4、1.6、3.2；FR-11/23 → 1.2；FR-12 → 1.3
  - FR-14 → 2.1、2.3；FR-15 → 2.1、2.4；FR-16 → 2.5；FR-18 → 2.2；FR-19 → 2.6
  - FR-17 → 1.4、3.1、3.3；FR-20 → 1.4（Noop）、3.4；FR-21 → 1.6、2.3、2.4
- **NFR 覆盖**：
  - NFR-1 → 1.5；NFR-2 → 2.4；NFR-3 → 1.6 冒烟（记录包体大小差）；NFR-4 → 1.4、1.5、1.6、2.1
  - NFR-5 → 1.6；NFR-6 → 3.1、3.4；NFR-7 → 1.2、1.6；NFR-8 → 3.4；NFR-9 → 1.2、1.3、3.4
- **建表时机**：只在第一次需要时建。`vip_level` 和名单表在 1.1 建；`content_posts` 的视频列在 1.3 加。
- **依赖关系**：
  - Story 只依赖它前面的 Story。`VideoUrlResolver` 提前到 1.4 建好，所以 Epic 2 和 Epic 3 都只依赖 Epic 1，彼此可以并行开发。
  - 3.4 的 L2 验收依赖阿里云企业认证，会阻塞生产上线，但不阻塞开发和 stag 验收。
- **文件重叠**：Epic 2（读取侧、App）和 Epic 3（状态迁移、后台、CDN）改动的主要文件不重叠。`ContentService` 和 `ContentPost` 由 1.3 和 3.1 分批修改，3.1 只增加入口覆盖，不重写 1.3 的推导逻辑。
- **脚手架**：现有项目，不需要初始化。
