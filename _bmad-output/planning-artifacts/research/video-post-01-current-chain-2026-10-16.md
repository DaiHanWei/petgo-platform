# 发帖支持短视频 · 调研 01：现有发帖链路梳理

> 分支 `research/video`（从 main `5ec8a2e4` 切出）· 2026-10-16 · 只读梳理，未改代码
> 路径前缀：前端 `petgo_app/lib/`，后端 `petgo-backend/src/main/java/com/tailtopia/`

## 0. 结论

- 整条链路**只认图片**：前后端都没有媒体类型、视频字段、视频依赖、视频审核。
- 前端全程把图片当 `Uint8List` 放在内存里处理；视频必须改为按文件路径、流式上传。
- 后端图片审核是**发帖事务内同步串行**的；阿里云视频审核是异步接口，必须新起「先审后发」的状态机。
- 顺带发现两个**和视频无关的现存问题**：发帖不校验 `imageUrls` 的归属（任意外链可入库分发）；上传签名不限 content-type 和大小。

## 1. 链路总览

```
[入口] 底栏+ / 打卡后发帖 / 首页空态 / 被拒重发 / 运营位 / 晒图 / 深链 /publish
   └─ PublishComposePage.open()（95% 高的 bottom sheet，非路由）
[选图] image_picker（相机单张 / 相册多选，最多 9 张，原生层 2048px q85）
   └─ ImageProcessor：主 isolate 解码 → 去 EXIF → JPEG q90 → ≤10MB
   └─ 宽高比超出 [0.75,1.78] → 露出区域页 → cropOffset
[上传] 选完即传，串行逐张
   └─ POST /api/v1/media/upload-url {scope:PUBLIC, contentType:image/jpeg}
   └─ 独立 Dio PUT 到 OSS（预签名，带 x-oss-object-acl: public-read）
   └─ 无进度百分比、无超时、无自动重试（失败项手动重试）
[发布] POST /api/v1/content-posts（Idempotency-Key）
   └─ imageUrls / imageSizes / imageCropOffsets / text / type / visibility / petId / mentions / placeCheckinToken / eventDate
   └─ 后端事务内：L1 词库 → 文本审核 → 逐张图片审核（2s/张）
       ├─ 拦截 → 422（content-text/image-blocked）→ /publish/rejected
       ├─ 降级/存疑 → 人工审核开关开时 UNDER_REVIEW，关时直接发布（现网默认关）
       └─ 通过 → PUBLISHED + ContentPublishedEvent
[展示] 后端下发 OSS 直链；客户端 AppImage 自拼 x-oss-process 缩略图（Image.network，无磁盘缓存）
   └─ 推荐/关注流 FeedImage(800) · 详情 _ImageCarousel(1080) · 灯箱(1080) · 主页网格(400) · 时间线/日历/分享页
[删除] 只软删；OSS 对象永不删除（F21）
```

## 2. 前端要点

| 环节 | 现状 | 证据 |
|---|---|---|
| 选取 | `image_picker` 1.2.3，Android 开 Photo Picker；`pickMultiImage` / `pickImage` | `features/media/domain/media_upload_use_case.dart:87-123`、`main.dart:29-33` |
| 数量 | 最多 9 张 | `features/content/domain/publish_controller.dart:41` |
| 权限 | SDK≥33 只申请 `photos`；iOS 无麦克风描述；Android 无 `READ_MEDIA_VIDEO` | `shared/utils/media_permission.dart:51`、`ios/Runner/Info.plist`、`AndroidManifest.xml:4-9` |
| 处理 | 纯 Dart `image` 包同步处理，10MB 上限 | `shared/utils/image_processor.dart:6,23-55` |
| 上传 | 独立 `Dio()` 无超时，整块字节一次 PUT，无 `onSendProgress` | `features/media/data/oss_uploader.dart:20-39` |
| 状态机 | `ImageUploadItem{bytes,status,url,size,cropOffset}`，串行 uploadAll | `publish_controller.dart:13-35,195-256` |
| 请求体 | 见 §1 | `features/content/data/content_repository.dart:58-95` |
| 展示 | `AppImage.ossResized` 对所有 `*.aliyuncs.com` 拼 `image/resize` | `shared/widgets/app_image.dart:25-34` |
| 模型 | `FeedItem` / `ContentDetail`：`firstImageUrl`、`imageUrls`、`imageSizes`、`imageCropOffsets` | `feed_item.dart:243-271`、`content_detail.dart:134-159` |
| 草稿/编辑 | 都不支持（NFR-10） | `publish_controller.dart:12,45-46` |
| 视频依赖 | 无（video_player / camera / 压缩 / 抽帧 均无） | `pubspec.yaml` |

## 3. 后端要点

| 环节 | 现状 | 证据 |
|---|---|---|
| 签名 | 预签名 PUT，无 STS；key=`<prefix><public\|private>/<userId>/<随机>.<ext>`；ext 只认图片，其余 `.bin` | `shared/media/PresignedUploadService.java:43-92` |
| 限制 | **无 content-type 白名单、无大小限制**；上传签名 600s | 同上、`MediaProperties.java:132-134` |
| 对外 URL | `cdnBaseUrl/key` 直链，本地 env 指向 OSS 源站（生产是否走 CDN 待确认） | `PresignedUploadService.java:67` |
| 发帖 DTO | `imageUrls`≤9 等；另有 5 个兼容构造器 | `content/dto/ContentPostCreateRequest.java:20-109` |
| 归属校验 | 工具 `MediaObjectKeys.requireAllOwned` 已有，**发帖未用** | `MediaObjectKeys.java:52-63` |
| 实体 | `image_urls`/`image_sizes`/`image_crop_offsets` 均 JSONB；无媒体类型列 | `content/domain/ContentPost.java`、`V4`、`V20260818_1344`、`V20261012_1103` |
| 审核 | `ContentService.publish` 事务内同步 `moderation.evaluate`；图片逐张 `postImageCheckByVL_global` | `ContentService.java:451-569`、`ContentModerationService.java:142-248` |
| 视频审核 | SDK `green20220302:3.3.3` 自带 `VideoModeration*`，未使用 | `pom.xml:116-120` |
| 尺寸补测 | `ImageSizeBackfillService` 用 ImageIO 读头，视频读不出 | `content/service/ImageSizeBackfillService.java:84-160` |
| 删除 | 软删；`MediaDeletionService` 只打日志（F21） | `MediaDeletionService.java:23-36` |
| 运营发帖 | Lark 同步、后台种子上传都只认图片 | `LarkContentSyncService.java:280-291`、`AdminSeedImageService.java:36-39` |

## 4. 加短视频的改动面

### 前端
1. **权限**：Info.plist 加 `NSMicrophoneUsageDescription`，相机/相册文案提到视频；Manifest 加 `READ_MEDIA_VIDEO`（录音再加 `RECORD_AUDIO`）；`media_permission.dart` 增加 `videos`。
2. **选取**：`pickVideo` 或 `pickMultipleMedia`；校验时长和大小；建议**图与视频互斥**（1 个视频 或 ≤9 张图）。
3. **处理**：新建视频处理器（压缩、抽封面、读宽高与时长），全程用文件路径。
4. **上传**：按 content-type 签名；流式上传（`file.openRead()`）+ 进度 + 超时 + 取消；封面单独上传；大文件考虑分片。
5. **状态机与 UI**：`ImageUploadItem` 泛化为媒体项；发帖页格子、预览、进度百分比。
6. **展示**：模型加视频字段；卡片/网格/时间线/日历/分享页用封面并加播放角标；`ossResized` 跳过视频地址；新增播放器组件和流内播放策略（自动播放？静音？同屏只播一个？）。
7. **依赖**：播放器用 `video_player`（免费，系统 ExoPlayer/AVPlayer，包体积增量小）；压缩用原生实现类库，不用 ffmpeg_kit。

### 后端
1. **签名**：content-type 白名单 + 视频扩展名；大小限制（预签名 PUT 做不到 → PostObject policy `content-length-range`，或发帖时 HEAD 校验）；视频可单独加长上传有效期。
2. **迁移/实体**：新增 `media_type`（IMAGE/VIDEO，默认 IMAGE）+ `video_url` / `video_cover_url` / `video_duration_ms` / `video_size`（或统一一个 `media` JSONB）。**不要把视频塞进 `image_urls`。**
3. **发帖校验**：互斥、时长/大小上限、**视频与封面的归属校验**（顺手补上图片）。
4. **视频审核**：新增 `scanVideo`（提交 → 轮询或回调取结果），异步状态机，**审核通过后才公开**、才发事件与 @ 通知；封面走现有图片审核。`review_reason` 的 CHECK 约束需要全量重建。
5. **读取侧**：`FeedItemResponse` / `ContentDetailResponse` / `SharedPostResponse` / `GrowthMomentView` 加字段；`firstImageUrl` 对视频帖下发封面（旧客户端可降级成看封面图）。
6. **会把视频当图片的地方**：访客/名片 `exifStrippedDeliveryUrl`、推荐封面 SQL `image_urls->>0`、Gemini 自动评论、暖评缩略图、顶置首图、H5 分享页 `<img>`、后台 4 个模板 `<img>`。

## 5. 风险

| 风险 | 说明 |
|---|---|
| 审核空窗 | 现网人工审核开关默认关，降级即放行；视频照搬会让未审视频直接公开 → 必须先审后发 |
| 流量成本 | 若生产直连 OSS 源站，视频外网流量费高且无转码；需确认 CDN，评估 MPS/IMM 转码与截帧 |
| 存储只增 | F21 永不删除 + 视频体积大 → 需重新拍板 F21 或配 OSS 生命周期规则 |
| `x-oss-process` 误用 | `image/...` 参数加在视频 URL 上会报错，所有拼参处都要按媒体类型判断 |
| 外链分发 | URL 不校验归属，视频字段照抄会开放任意外链视频分发 |
| 旧版兼容 | ≤1.3.4 的 App 不认识视频帖；需靠 `firstImageUrl`=封面降级，或用 `X-App-Version` 对旧客户端过滤视频帖 |
| 事务时长 | 视频不能进现有的同步审核链路 |

## 6. 待拍板（下一份调研再展开方案与成本）

1. 视频规格：最长时长、最大体积、分辨率、是否允许图和视频混发。
2. 压缩放端上还是云端（端上免费但耗性能；云端 MPS 按分钟计费）。
3. 视频审核：阿里云内容安全视频审核（按时长计费）+ 先审后发的用户体验（发布后显示「审核中」多久）。
4. 推荐流播放形态：静态封面点击进详情播放，还是流内自动静音播放。
5. 旧版 App 处理：降级显示封面，还是按版本头过滤掉视频帖。
6. 存储与 CDN：生产 CDN 现状、F21 是否对视频放开、生命周期策略。
7. 运营侧（Lark 定时发帖、后台种子）第一期是否需要支持视频。
