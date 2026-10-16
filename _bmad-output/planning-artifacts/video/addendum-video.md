# PRD-video 附录：技术实现参考

> 这里放技术实现的细节，供架构 delta 和 story 拆分时参考，不属于需求本身。详细依据见调研 01–03。

## A. 端上视频处理（对应 FR-7）
- **已定：`pro_video_editor`**（决策 V-28，2026-10-16 拍板，未做真机验证）。`v_video_compressor` 只作为 L2 真机验收不达标时的备选（调研 03）。
- 接入时要处理的已知问题：
  - **降帧后码率减半**：60fps 视频降到 30fps 后，安卓上的实际码率只有约 1Mbps。降帧时需要调高目标码率。
  - **两个库都不会自动跳过已符合视频规格的视频**：跳过逻辑要自己写，先读元数据判断。已符合规格但没有 faststart 的视频，要做只调整 moov 位置的 remux（能力未验证，兜底方案见下方「remux」一条）。
  - **`pro_video_editor` 的预设对竖屏缩放不对**：按 1280×720 取最小比例，竖屏会被缩到 405×720。要按短边自己计算 `ExportTransform(scaleX, scaleY)`。
  - **`v_video_compressor` 的 `outputPath` 会被当成目录**。
  - **`v_video_compressor` 在 iOS 上音频原样透传**：换用时要确认音频是 AAC，否则强制重新编码；iOS 最低版本要求 15（项目当前最低就是 15，满足）。
- 处理全程用文件路径，不要把视频读进内存（现有图片链路是 `Uint8List`，见调研 01 §3）。
- **宽高口径**：安卓 Media3 输出的竖屏视频，存成 1280×720 并带 rotation 元数据；iOS 输出的是物理竖屏 720×1280。上报和入库一律用**旋转后的显示宽高**。验收方向时以播放画面为准，不要看 ffprobe 读出的原始宽高。
- **remux**：两个候选库都没有验证过「只调整 moov 位置、不重新编码」的能力。如果库不支持，就补一个轻量的 MP4 moov 前移实现（不要引入 ffmpeg），或者已符合规格的视频也重新编码一次（代价是耗时）。
- **iOS**：使用 AVAssetExportSession 的实现要设置 `shouldOptimizeForNetworkUse = true`；验收时实测输出是否为 faststart。
- **元数据**：处理后的文件（包括只 remux 的）都要去掉位置等元数据，并实测验证。
- **统一封装**：在 App 内封装一层视频处理接口，`pro_video_editor` 只在这一层里调用。这样万一 L2 验收后要换库或两个平台混用，改动只限于这一层。
- **先冒烟**：开始功能开发前，在 petgo_app 里集成一次并跑通，再锁定库版本。

## B. 播放（对应 FR-15）
- 用 `video_player`，底层是系统播放器（ExoPlayer / AVPlayer），免费，体积增量很小。
- 第一期不做磁盘缓存。

## C. 数据模型（对应 FR-1、FR-12、FR-22）
- `users.vip_level`（会员等级）：integer NOT NULL DEFAULT 1（**不要用 SMALLINT**，项目踩过 Hibernate int→int4 的坑）。
- 视频免审名单：新表（例如 `video_trusted_publishers(user_id PK/FK → users ON DELETE CASCADE, created_by_admin_id, created_at)`），或者 `users` 上加一个布尔列。表的方式更便于做全量列表；改动写入 `AdminAuditService.record`。新增权限码要写进后台权限常量。
- `content_posts` 新增：`media_type`（IMAGE / VIDEO，默认 IMAGE）、`video_url`、`video_cover_url`、`video_duration_ms`、`video_width`、`video_height`、`video_size_bytes`。**不预留合集字段**（决策 V-15）。**不要把视频塞进 `image_urls`。**
- 迁移用时间戳版本号；涉及 CHECK 约束的要全量重建。
- `firstImageUrl` 对视频帖下发封面。
- **入库存什么**：视频和封面在库里存**对象 key**（或者源站 URL），归属校验按对象 key 做；下发时再拼成 CDN 域名。这样不会和 `objectKeyOfPublicUrl` 只认一个前缀的限制冲突，以后换 CDN 也不用迁移数据。

## D. 服务端（对应 FR-10、FR-11、FR-12、FR-17、FR-18、FR-20）
- 上传签名：content-type 白名单（jpeg / png / webp / heic / mp4），视频扩展名映射为 `.mp4`。预签名 PUT 无法限制大小，所以发帖时要 HEAD 真实对象，校验 ≤ 50MB。HEAD 放在事务外先做，失败直接拒绝，不要长时间占着数据库事务。
- 上传凭证有效期：视频单独加长（例如 1800 秒）；客户端在凭证过期时自动重新申请。
- 归属校验：只校验视频和封面，复用 `MediaObjectKeys.requireAllOwned`。图文帖的图片**不加校验**（决策 V-20）。
- 免审按名单判断：发布者在**视频免审名单**里（新建，**不要复用** 1.3.4 的 `isOfficialAccount`：那个判断的是 `role=ADMIN`，见 `AccountQueryService.java:158-168`），视频与封面跳过审核；正文仍然走文本审核。不在视频免审名单里、但会员等级 ≥ 10 的账号：视频帖直接落 `UNDER_REVIEW` 并进入人工审核队列（复用 `ManualReviewGate` 的入队逻辑，**不受人工审核总开关影响**，强制入队）。
- 文件公开状态跟随帖子（PRD FR-17）：视频和封面**上传时就设为 private**（现有上传签名会给 PUBLIC 域带上 `public-read`，视频要单独处理）；帖子对公众可见时改为 public-read，并刷新 CDN；帖子变为不可见的所有路径（与 PRD FR-17 一致：运营下架、作者删除、举报预处置、人工审核拒绝或超时、封号、注销；代码中分别对应下架、删除、举报 hold、审核 discard、封号批量下架、注销）都改回 private 并刷新 CDN。建议统一挂在帖子可见性变化的事件上，不要在每个入口各写一遍。
  - CDN 回源**不要**开启私有桶鉴权：否则对象改成 private 后 CDN 仍能回源，下架就失效了。
  - 审核中的帖子只有作者本人能看：作者端用短期签名 URL 播放。
  - ACL 修改或缓存刷新失败要重试并告警，不能静默。
- 旧版过滤：缺少 `X-App-Version`，或版本号低于配置的「视频最低版本」，就过滤掉 `media_type = VIDEO`。**过滤条件要写在查询的 WHERE 里**，不能取出数据后在内存里再过滤，否则分页会出现空页或提前到底。
  - 推荐流的顺序缓存在 Redis 里，翻页时按缓存读取（`FeedService.java:230-292`），所以**过滤必须在生成推荐顺序时就做**：新旧客户端分开缓存顺序，或者在缓存键里带上「是否包含视频」。不能在翻页读取时再过滤。
  - 降级回落路径、科普候选池、分类 Tab 都要覆盖到。
  - 版本号解析要兼容 stag 包的 `-stag` 后缀（现有 `AppVersionConfigService` 的比较函数遇到后缀会报错，见 `AppVersionConfigService.java:96-106`）：先去掉预发布后缀再比较；解析失败时按旧版客户端处理。详情接口返回统一 404。「视频最低版本」放在 `admin_settings` 或 `app_version_config` 里。
- 视频分发：新增独立配置项（例如 `media.oss.video-cdn-base-url`），**图片的 `cdn-base-url` 不改**。CDN 回源到 OSS，开启 Range 回源。
- 封面缩略图：CDN 要**透传 `x-oss-process` 参数**，并把它纳入缓存键，这样列表才能取到封面缩略图。App 端 `ossResized` 的配套改动见 E。
- CDN 配置要点：回源 Host 设为 OSS 桶域名；MP4 和封面设置长缓存；配置 HTTPS 证书；帖子变为不可见时刷新缓存（见 PRD FR-17；CDN 开通见 PRD §10 第 2 条）。
- 会把视频当成图片处理的地方，都要按媒体类型分支（调研 01 §4）：访客 / 名片的 `exifStrippedDeliveryUrl`、推荐封面 SQL `image_urls->>0`、Gemini 自动评论（视频帖跳过）、暖评缩略图、顶置首图、H5 分享页、后台 4 个模板。
- `ImageSizeBackfillService` 不处理视频帖。

## E. App 端（对应 FR-5 ~ FR-10、FR-14）
- 权限：
  - `Info.plist` 相册文案要提到视频；不做现场拍摄，所以**不加** `NSMicrophoneUsageDescription`（与调研 02 不同，以 PRD FR-6 及 §11 假设 A1 为准）。
  - **安卓不加 `READ_MEDIA_VIDEO`**：`image_picker` 在安卓 13+ 走系统照片选择器，选视频不需要权限；SDK < 33 的旧机型沿用现有的 `storage` 权限。`media_permission.dart` 不新增 `videos`。
- `AppImage.ossResized` 要跳过视频地址（`image/...` 处理参数加在视频上会报错），但要对视频 CDN 域名下的**封面**拼缩略图参数（见 D「封面缩略图」）。
- `OssUploader` 改为流式上传，并加上进度、超时和取消。

## F. 埋点事件（对应 FR-21）
| 事件 | 属性 |
|---|---|
| `video_play_start` | postId、source、ttffMs（点开到出画面的耗时） |
| `video_progress` | postId、25 / 50 / 75 |
| `video_complete` | postId、loop |
| `video_watch_time` | postId、seconds（离开页面时上报） |
| `video_play_error` | postId、errorType |
| `video_buffering` | postId、durationMs |
| `video_process_start` | durationMs、bytesBefore |
| `video_process_result` | ok、errorType、processMs |
| `video_upload_error` | errorType、bytesSent |
| `video_publish` | durationMs、bytesBefore、bytesAfter、processMs |
| `video_card_impression` | postId、source（卡片露出一半以上，同一次刷新内只计一次） |

> 需要加入 ButtonId / 事件白名单，并走统一的 scrub。所有事件都带 App 版本，用于 SM-1 按版本切分。
