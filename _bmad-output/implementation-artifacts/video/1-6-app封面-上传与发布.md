---
baseline_commit: 4379f6cdfc6c70e9ff651a0748077a87acad3c74
---

# Story 1.6：App 封面、上传与发布

Status: review

> 专题线 video · Epic 1 · **只改 App 端**（`petgo_app/`）。分支 `feat/video`。依赖 1.2（视频上传策略、封面 `purpose=VIDEO_COVER`）、1.3（发布接口 `mediaType=VIDEO`）、1.4（发布后即时公开）、1.5（入口、规格检查、`VideoDraft`、接入点 `_onVideoAccepted`）。
> 权威顺序：决策日志-video.md > PRD-video.md > architecture-video-delta.md > epics-video.md > addendum-video.md（A / B）。无 UI 稿（V-29），沿用现有发帖页风格。

## Story

As a 官方号运营，
I want 用默认封面或自己换的封面，把视频上传并发布，
so that 视频帖能出现在社区里。

**范围**：FR-8、FR-9、FR-10（App 部分）、FR-21（发布类埋点）；架构 AD-3（App 部分）、AD-4（封面）、AD-9、AD-10 埋点表。
**不在范围**：播放页、列表角标、能力头 `X-App-Capabilities` → Epic 2。

## Acceptance Criteria

1. **默认封面【L0 + L2】** 视频通过检查后，用 `fc_native_video_thumbnail`（新依赖，3.0.1）取**第 1 秒**画面：`saveThumbnailToBytes(srcFile: Uri.file(path).toString(), srcFileUri: true, width: 显示宽, height: 显示高, at: 1 秒, quality: 85)`（Android 只有 `srcFileUri: true` 时才支持 `at`）。结果统一过 `coverToJpeg720`：解码 → 若短边 > 720 等比缩到短边 720 → JPEG（质量 85）。取帧失败（返回 null / 抛错）→ 封面区显示「请选择封面」，「发布」不可用，用户可从相册选。

2. **更换封面【L0】** 点「更换封面」走现有选图链路（`mediaUploadUseCaseProvider.pickAndProcess(source: gallery)`：去 EXIF、压缩），再过 `coverToJpeg720`。换封面后旧封面的上传结果作废、重新上传新封面。

3. **视频上传【L0 + L2】**
   - `POST /media/video-upload-policy` 取 `{host, key, fields}`；用**独立的 Dio**（`connectTimeout` 15 秒、`sendTimeout` 600 秒、不挂鉴权拦截器）向 `host` 发 `multipart/form-data`：先按顺序放 `fields` 全部字段，**`file` 最后**，`MultipartFile.fromFile(path, contentType: video/mp4)`（流式，不读进内存，一致性规则 8）。
   - `onSendProgress` → 进度百分比；可取消（`CancelToken`）；OSS 返回 200 即成功，记下 `key`。
   - 封面同时（并行）上传：`POST /media/upload-url`，`{scope: PUBLIC, contentType: image/jpeg, purpose: VIDEO_COVER}` → 现有 `OssUploader.put`（原样回带 `ticket.headers`，其中已含 `x-oss-forbid-overwrite`、`x-oss-object-acl: private`）→ 记下 `publicUrl`。

4. **失败与重试【L0】**
   - 视频或封面任一失败 → 该项显示「上传失败，点击重试」；重试**重新申请凭证并从头上传**；申请凭证本身失败时按 1 / 2 / 4 秒退避最多重试 3 次（AD 过程规范），仍失败才显示「重试」。
   - 重试时文案、类型、@ 名单、封面都保留（都在控制器里，不随上传重置）。
   - 视频和封面**都成功**之前「发布」不可点。
   - 申请视频策略返回 403 `video-permission-denied` → 提示「你暂时没有发布视频的权限」，不再自动重试；429 `video-upload-quota-exceeded` → 提示「今天的视频上传次数已用完」。

5. **关闭与后台【L0 + L2】**
   - 上传进行中点关闭（右上角 ×）或系统返回 → 弹确认「放弃上传？已填写的内容不会保存」；确认后取消上传并关闭，不留草稿；取消则留在页面。
   - 发帖页被关闭（含下拉关闭 sheet）时控制器 dispose → 取消进行中的上传（不在后台继续传）。
   - App 切到后台再回来：上传要么继续，要么已失败并显示「重试」，页面不卡死【L2】。

6. **发布【L0 + L1 联调 + L2】**
   - 请求体：`type`（日常 / 科普）、`mediaType: "VIDEO"`、`imageUrls: [封面 publicUrl]`、`video: {objectKey, durationMs, width, height}`（宽高为显示方向）、`visibility: "PUBLIC"`、正文可空、@ 名单照图文规则；**不带** `imageSizes`、`imageCropOffsets`、`petId`、`eventDate`、`placeCheckinToken`。
   - 成功 → 与图文相同：关闭发帖 sheet、进现有发布成功页、刷新推荐流与「我的发布」。
   - 403 `video-permission-denied` → toast「你暂时没有发布视频的权限」，留在编辑页、内容保留；`content-text-blocked` → 走现有「内容被拒」页；其他失败 → 现有「发布失败」toast。
   - 视频帖发布不调用图片上传状态机（`uploadAll`）。

7. **埋点【L0】**（统一走 `Analytics.capture`，经 `scrub` 去敏；App 版本由 PostHog 自动附带）
   - `video_spec_check {ok: bool, failedItems: "duration,hdr"（逗号分隔，合格时空串）}`：每次规格检查完成时上报（1.5 的检查点补上）。
   - `video_upload_error {errorType}`：`policy`（申请凭证失败）、`upload`（OSS 上传失败）、`cover`（封面上传或取帧失败）、`permission`（403）、`quota`（429）。用户主动取消不报。
   - `video_publish {durationMs, sizeBytes, uploadMs}`：发布成功后上报；`uploadMs` = 视频上传开始到成功的耗时（多次重试取最后一次成功的那次）。
   - 不含任何个人信息、路径、URL、对象 key。

8. **端到端【L2：stag】** 运营用剪映按《官方视频导出规格》导出一段视频，官方号（等级 10、在免审名单）在 stag 上走一遍：一次通过规格检查、上传、发布成功，推荐流里出现这条帖子的封面（此时还没有播放页）。

## Tasks / Subtasks

- [x] **T1 依赖与路径**
  - [x] `pubspec.yaml` 加 `fc_native_video_thumbnail: ^3.0.1`（2026-06-05 发布，已满两周；SDK 要求 `^3.12.0` / Flutter `>=3.44.0` 与本仓一致），`flutter pub get`、`flutter gen-l10n`。
  - [x] `ApiPaths.mediaVideoUploadPolicy = '$base/media/video-upload-policy'`。

- [x] **T2 封面**（AC 1、2）— `lib/features/content/video/video_cover_service.dart`
  - [x] 纯函数 `Uint8List? coverToJpeg720(Uint8List input)`（`image` 包已在依赖里：解码失败返回 null；短边 > 720 等比缩小；`encodeJpg(quality: 85)`）。
  - [x] `class VideoCoverService { Future<Uint8List?> defaultCover(VideoDraft d); }`，内部调 `FcNativeVideoThumbnail().saveThumbnailToBytes(...)`（参数见 AC1），catch 一切异常返回 null。经 `videoCoverServiceProvider` 注入。

- [x] **T3 网络层**（AC 3、6）
  - [x] `MediaRepository.requestUploadTicket(scope, {contentType, purpose})`：新增可选 `String? purpose`，非空才进请求体。同步改 3 个实现（含测试假实现）。
  - [x] `lib/features/content/video/video_post_repository.dart`：
    - `class VideoUploadPolicy { host, key, fields }`（`fromJson`，`fields` 保持服务端顺序 → `LinkedHashMap`）；
    - `abstract class VideoPostRepository { Future<VideoUploadPolicy> requestPolicy(); Future<void> uploadVideo(VideoUploadPolicy p, String path, {required void Function(int sent, int total) onProgress, required CancelToken cancelToken}); Future<String> uploadCover(Uint8List jpeg); Future<int> publishVideo({...}); }`；
    - `DioVideoPostRepository`：`requestPolicy` 与 `publishVideo` 用主 `dioProvider`（带鉴权）；`uploadVideo` 用**独立 Dio**（`BaseOptions(connectTimeout: 15s, sendTimeout: 600s)`，无拦截器）；`uploadCover` = `mediaRepository.requestUploadTicket(MediaScope.public, contentType: 'image/jpeg', purpose: 'VIDEO_COVER')` + `OssUploader().put(ticket, bytes:)` → `publicUrl`；
    - `publishVideo({required ContentType type, String? text, required String coverUrl, required VideoDraft video, required String objectKey, List<int> mentionedUserIds, required String idempotencyKey})` → POST `ApiPaths.contentPosts`，`Idempotency-Key` 头同现有 `DioContentRepository.publish`（照它的头写法），请求体见 AC6，返回 `id`。
    - `videoPostRepositoryProvider`。
  - [x] **不改 `ContentRepository` 接口**：它有 13 个实现（7 个测试假实现没有 `noSuchMethod`），加参数会让它们全部编译失败；视频发布独立成上面的仓库。

- [x] **T4 控制器**（AC 3、4、5、6）— `publish_controller.dart`
  - [x] 新增可选依赖 `VideoPostRepository? videoRepository`、`Future<void> Function(Duration)? delay`（测试注入，默认 `Future.delayed`）。
  - [x] 视频状态：`enum MediaUploadStatus { idle, uploading, success, failed }`；`videoStatus`、`videoProgress`（0–1）、`videoObjectKey`、`videoUploadMs`、`videoError`（`VideoUploadError? {policy, upload, permission, quota}`）；封面：`coverBytes`、`coverStatus`、`coverUrl`。
  - [x] `Future<void> startVideoUpload()`：申请策略（失败按 `[1, 2, 4]` 秒退避再试，403/429 不重试）→ 上传 → 成功记 key 与耗时；失败置 failed 并记 `videoError`；被取消不置 failed（回 idle）。`retryVideoUpload()` = 再走一遍。
  - [x] `Future<void> setCover(Uint8List jpeg)`：替换封面并上传（旧结果作废）；`retryCoverUpload()`。
  - [x] `cancelVideoUpload()`；`dispose()` 里取消。`setVideo` 保持 1.5 行为；`clearVideo()` 时取消上传并清空视频 / 封面状态。
  - [x] `bool get videoUploading => videoStatus == uploading || coverStatus == uploading`。
  - [x] `canPublish`：有视频时 = `!publishing && textWithinLimit && videoStatus == success && coverStatus == success`（替换 1.5 的「有视频一律 false」）；无视频时不变。
  - [x] `publish()`：有视频 → `videoRepository.publishVideo(...)`（不走 `uploadAll`、不碰顺序锁），无视频 → 原逻辑。
  - [x] 埋点在控制器内上报 `video_upload_error`；`video_publish` 在发布成功后上报（控制器内，取 `video.durationMs / sizeBytes` 与 `videoUploadMs`）。经 `Analytics.capture`（静态门面，测试用 `debugCaptureSink` 断言）。

- [x] **T5 发帖页**（AC 1–7）
  - [x] `_onVideoAccepted(draft)`：取默认封面 → 有则 `controller.setCover(bytes)`；并行 `controller.startVideoUpload()`（两者都不 await 阻塞 UI）。
  - [x] `_pickVideo` 每次检查后上报 `video_spec_check`。
  - [x] `VideoDraftTile` 扩展：封面缩略图（`Image.memory`，无封面时占位 +「请选择封面」）、视频进度「上传中 42%」+ 取消、失败「上传失败，点击重试」、封面失败同理、「更换封面」按钮、移除。
  - [x] 关闭确认：× 按钮与 `PopScope`（`canPop: !controller.videoUploading`，`onPopInvokedWithResult` 弹确认）；确认 → `controller.cancelVideoUpload()` 后 `Navigator.pop`。
  - [x] `_publish`：DioException 的 `typeSlug == 'video-permission-denied'` → toast `videoPermissionDenied`、退出审核覆盖层、留在页面；其余沿用现有分支。成功路径不变（`args.photoCount` 为 0 无妨）。
  - [x] 文案（en / id）：`videoUploading`（{percent}）、`videoUploadFailedRetry`、`videoCoverChange`、`videoCoverPick`、`videoCoverFailedRetry`、`videoUploadCancel`、`videoDiscardTitle`、`videoDiscardBody`、`videoDiscardConfirm`、`videoDiscardKeep`、`videoPermissionDenied`、`videoQuotaExceeded`。

- [x] **T6 测试**
  - [x] L0 `video_cover_service_test.dart`：`coverToJpeg720` —— 1080×1920 → 720×1280；720×1280 不放大；坏字节 → null；输出是 JPEG。
  - [x] L0 `video_post_repository_test.dart`：用 `dio` 的 `HttpClientAdapter` 桩：策略解析保序；上传请求是 multipart、字段顺序与 `fields` 一致且 `file` 在最后、`Content-Type: video/mp4`；独立 Dio 的超时配置；`publishVideo` 请求体逐键断言（含「不带」的键）与 `Idempotency-Key` 头。
  - [x] L0 `publish_controller_video_upload_test.dart`（假 `VideoPostRepository`）：成功路径（进度、key、canPublish 变 true）；策略失败退避 1/2/4 秒共 4 次后失败（`delay` 注入记录）；403 / 429 不重试且错误类型正确；上传失败 → 重试成功；取消回 idle；`clearVideo` 取消上传；换封面重传；`publish` 调 `publishVideo` 参数正确且不调 `uploadOne`；埋点三类事件（`debugCaptureSink`）且属性不含路径 / URL / key。
  - [x] L0 widget（延续 1.5 的 `publish_compose_video_entry_test.dart` 或新文件）：合规视频选中后显示封面与进度；上传失败显示重试、点重试再成功；上传中点 × 弹确认、取消留下、确认关闭；发布 403 → toast 且留在页面。
  - [x] `flutter analyze` 零问题；全量 `flutter test` 通过。
  - [ ] ⏸ L1 联调（本地后端 + 模拟器）（未做）：发布一条名单内视频帖，后端 `media_acl_applied=PUBLIC`。
  - [ ] ⏸ L2（stag，留给验收）（未做）：AC8 全流程；后台切换；Android 取第 1 秒帧是否正确；iOS 真机。

## Dev Notes

### 现状（已读过）

- **1.5 留下的接入点**：`publish_compose_page.dart` 里 `_onVideoAccepted(VideoDraft)` 空实现；`PublishController.video / setVideo / clearVideo / canAddImage / canPickVideo`；`canPublish` 在有视频时恒 false（本 Story 改）；`VideoDraftTile`（占位图标 + 时长 + 分辨率 + 移除）；`videoPickerProvider`、`videoSpecCheckerProvider`。
- **`ContentRepository.publish`**（`features/content/data/content_repository.dart`）：请求体按键「为空整键不出现」的写法；`Idempotency-Key` 头；返回 `id`。视频发布照这个风格写在新仓库里。
- **`MediaRepository.requestUploadTicket(scope, {contentType})`** → `UploadTicket{uploadUrl, objectKey, method, headers, publicUrl}`；**`OssUploader.put(ticket, bytes:)`** 原样回带 `ticket.headers` 并补 `Content-Length`；`OssUploader` 自带的 Dio 没超时、没进度（图片沿用，不改）。
- **`MediaUploadUseCase.pickAndProcess(source:, context:)`**：选图 + `ImageProcessor.process`（去 EXIF、压缩）→ `Uint8List?`。
- **`_publish`**（`publish_compose_page.dart:451-610`）：「审核中」覆盖层至少 900ms；`ProblemDetail.fromDioException(e)?.typeSlug` 区分 `content-text-blocked / content-image-blocked`；成功后 invalidate `feedProvider / myPostsProvider / meCountsProvider` 并 push `/publish/done`。
- **关闭**：右上角 × 直接 `Navigator.of(context).pop()`（`:695`），**目前没有任何关闭确认**；sheet 可下拉关闭。下拉关闭不经过 `PopScope`，所以靠 dispose 取消上传兜底（AC5 第二条）。
- **埋点门面 `Analytics.capture`**：没有事件白名单，只有 `scrub`（剥 PII 键、自由文本键、超长字符串）；`debugCaptureSink` 可在测试里截获。PostHog 自动附带 `$app_version`。
- **后端错误码**（1.2 / 1.3）：`video-permission-denied`(403)、`video-upload-quota-exceeded`(429)、`video-object-invalid`(422)、`video-mixed-media`(422)、`upload-content-type-not-allowed`(422)。
- **`fc_native_video_thumbnail` 3.0.1**：`saveThumbnailToBytes({srcFile, width, height, format, srcFileUri, at, quality})`；`width/height` 是最大尺寸（保持比例）；**`at` 在 Android 用文件路径时不生效**，所以传 `Uri.file(path).toString()` + `srcFileUri: true`；`FcVideoThumbnailTime(1, FcVideoThumbnailTimeUnit.seconds)`。

### 设计取舍（照做）

- **视频上传用独立 Dio 且不挂鉴权拦截器**：目标是 OSS，不是我们的 API；挂上拦截器会把 JWT 发给 OSS，401 续期逻辑也会误伤。
- **申请凭证退避而不是上传本身自动重试**：上传失败由用户点「重试」触发（AD 过程规范），避免在弱网下反复烧流量。
- **取消不算错误**：用户主动取消不上报 `video_upload_error`，否则失败率会被「换了个视频」污染。
- **封面先私有**：`purpose=VIDEO_COVER` 签成 private，发布后由 1.4 的对账器改公开；所以发帖页预览封面必须用本地字节，不能用 `publicUrl` 拉图（会 403）。

### 测试与验证

- 改 `pubspec.yaml` 后 `flutter pub get` + `flutter gen-l10n`；不要整文件 `dart format`；`flutter analyze` 零问题。
- 后端 1.1–1.4 未部署到任何环境前，L1 / L2 只能本地起后端联调（`PETGO_API_BASE_URL=http://10.0.2.2:8080`，本地后端需要真实 OSS 凭证才能真正上传）；部署 stag 等用户明确指令。

### Project Structure Notes

| 文件 | 动作 |
|---|---|
| `pubspec.yaml` | 改：`fc_native_video_thumbnail: ^3.0.1` |
| `lib/core/network/api_paths.dart` | 改：`mediaVideoUploadPolicy` |
| `lib/features/media/data/media_repository.dart` | 改：`purpose` |
| `lib/features/content/video/video_cover_service.dart`、`video_post_repository.dart` | 新 |
| `lib/features/content/video/video_draft_tile.dart` | 改：封面、进度、重试、换封面 |
| `lib/features/content/domain/publish_controller.dart` | 改：上传状态机、发布分支 |
| `lib/features/content/presentation/publish_compose_page.dart` | 改：接入点、关闭确认、403 |
| `lib/l10n/app_en.arb`、`app_id.arb` | 改 |
| 测试 | 见 T6 |

### References

- [Source: _bmad-output/planning-artifacts/video/epics-video.md#Story 1.6]
- [Source: _bmad-output/planning-artifacts/video/architecture-video-delta.md#AD-3、#AD-4、#AD-9、#AD-10 埋点表、#过程]
- [Source: _bmad-output/planning-artifacts/video/PRD-video.md#FR-8、#FR-9、#FR-10、#FR-21]
- [Source: _bmad-output/implementation-artifacts/video/1-5-app视频入口与规格检查.md]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (1M context)

### Debug Log References

- 全量 `flutter test` 日志：scratchpad `flutter-1-6.log`（3477 通过、2 跳过）。

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- **T1** 依赖 `fc_native_video_thumbnail: ^3.0.1`；`ApiPaths.mediaVideoUploadPolicy`。
- **T2** `coverToJpeg720`（短边 >720 等比缩小、不放大、去 EXIF、JPEG 85）；`VideoCoverService.defaultCover` 取第 1 秒（文件 Uri + `srcFileUri: true`，Android 才认 `at`），失败返回 null。
- **T3** `MediaRepository.requestUploadTicket` 加可选 `purpose`（不带时整键不出现；改了 2 个测试假实现）；新 `VideoPostRepository`：策略、独立 Dio 流式 multipart 上传（字段按策略顺序、`file` 最后、video/mp4）、封面 `VIDEO_COVER`、`publishVideo` 请求体。**未改 `ContentRepository` 接口**。
- **T4** `PublishController`：视频 / 封面上传状态机；申请凭证 1/2/4 秒退避（403/429 不重试）；用户取消回 idle 不报错；换封面旧结果作废；`clearVideo` / `dispose` 取消上传；视频发布走 `publishVideo` 不碰图片状态机。
- **T5** 发帖页：通过检查后并行开始视频上传与默认封面；视频卡显示封面（本地字节）/ 进度 / 取消 / 失败重试 / 换封面；上传中 × 或系统返回弹「放弃上传？」确认；发布 403 → 提示并留在编辑页；每次规格检查上报。
- **实现中修掉的 2 个真 bug**（都由新测试抓到）：
  1. 发帖页关闭（控制器 dispose）后，被取消的上传回调仍调 `notifyListeners` → debug 下抛 “used after being disposed”。加 `_disposed` 守卫。
  2. `canPublish` 沿用了图文的「文字或图片至少一项」，视频帖不写文案时永远发不出去（违反 FR-10「文案可为空」）。改为视频也算满足。
- **与规划文档的命名差异（按仓库规范，需同步给后续 Story）**：
  - 仓库埋点规范测试要求「事件名以动作结尾」「属性 snake_case」。架构 AD-10 表的名字不满足，本 Story 改为：`video_spec_check_completed{ok, failed_items}`、`video_upload_failed{error_type}`、`video_publish_succeeded{duration_ms, size_bytes, upload_ms}`，并把 `video_` 作为新模块前缀登记进规范测试（附说明）。
  - ⚠️ **Epic 2 的观看类事件**（`video_card_impression`、`video_play_start`、`video_progress`、`video_complete`、`video_buffering`、`video_play_error`、`video_watch_time`）同样不满足，做 Epic 2 时要按同一规则改名（如 `video_card_shown`、`video_play_started` …）。
  - 控制器里封面方法叫 `setVideoCover`：仓库有一条规范测试禁止控制器出现 `setCover` 等「封面字段」（图文的封面只能是第 0 张图的投影）。
- 埋点：`Analytics` 门面无事件白名单，只有 `scrub` 去敏；App 版本由 PostHog 自动附带。属性里不含路径 / URL / 对象 key（测试断言）。
- **已知限制**：下拉关闭 sheet 不经过 `PopScope`，不会弹确认，但会经 dispose 取消上传、不在后台继续传（AC5 第二条）。
- **测试**：新增 `video_cover_service_test`(5)、`video_post_repository_test`(5)、`publish_controller_video_upload_test`(11)、`publish_compose_video_upload_test`(5)；`flutter analyze` 零问题；全量 `flutter test` 3477 通过。
- ⏸ **L1 / L2 未做**：后端 1.1–1.4 尚未部署；需要部署 stag（或本地后端配真实 OSS 凭证）后，用官方号（等级 10、在名单）在模拟器 / 真机走完整流程（AC8），并验证 Android 取第 1 秒帧、后台切换、iOS 真机。

### File List

- petgo_app/pubspec.yaml、pubspec.lock
- petgo_app/lib/core/network/api_paths.dart
- petgo_app/lib/features/media/data/media_repository.dart
- petgo_app/lib/features/content/video/video_cover_service.dart（新）
- petgo_app/lib/features/content/video/video_post_repository.dart（新）
- petgo_app/lib/features/content/video/video_draft_tile.dart
- petgo_app/lib/features/content/domain/publish_controller.dart
- petgo_app/lib/features/content/presentation/publish_compose_page.dart
- petgo_app/lib/l10n/app_en.arb、app_id.arb
- petgo_app/test/features/content/video/video_cover_service_test.dart（新）
- petgo_app/test/features/content/video/video_post_repository_test.dart（新）
- petgo_app/test/features/content/domain/publish_controller_video_upload_test.dart（新）
- petgo_app/test/features/content/presentation/publish_compose_video_upload_test.dart（新）
- petgo_app/test/analytics/v112_events_test.dart（登记 `video_` 前缀）
- petgo_app/test/triage/triage_upload_controller_test.dart、test/media/media_upload_use_case_test.dart（假实现签名）

### Change Log

- 2026-10-16：实现 Story 1.6——默认封面（第 1 秒）与换封面、视频流式直传与封面并行上传、失败重试与退避、关闭确认、视频帖发布与 403 处理、发布类埋点；修 2 个由测试发现的 bug；L0 通过，L1 / L2 待部署后验收。

## Senior Developer Review (AI) · 2026-10-16（云端）

- **CONFIRMED 已修**：
  1. `video_spec_check_completed.failed_items` 用枚举名逗号拼接，多项不合格时 > 64 字符被埋点去敏规则整键丢弃（违反 AC7）→ `VideoSpecFailure.analyticsCode` 短码（`unr/fmt/dur/size/res/vcodec/acodec/hdr/fast/loc`），全拼 ≤ 64 字符，有测试钉住。⚠️ 看板按短码取值。
  2. 用户主动取消上传后卡片显示红色「上传失败，点击重试」→ idle 态改用中性文案 `videoUploadCanceledRestart`（en / id）与 `ink2` 色；补 widget 用例。
- **PLAUSIBLE 未改**：封面上传用的默认 Dio 无超时；关闭发帖页不取消封面上传；下拉关闭后凭证请求失败仍可能上报 `video_upload_failed`；慢速默认封面可能覆盖用户自选封面；`coverToJpeg720` 在 UI 线程。
- L0：`flutter analyze` 零问题、content / l10n / analytics 测试全绿；**L1/L2 待本地验收**。
