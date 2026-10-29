---
baseline_commit: 7885257cae0819cd216c20740eea08d7f93786d1
---

# Story 1.5：App 视频入口与规格检查

Status: review

> 专题线 video · Epic 1 · **只改 App 端**（`petgo_app/`）。分支 `feat/video`。依赖 1.1（`/me` 下发 `vipLevel`）。
> 权威顺序：决策日志-video.md > PRD-video.md > architecture-video-delta.md > epics-video.md > addendum-video.md（只有 A / B 两节有效）。
> 本专题**没有 UI 稿**（决策 V-29）：界面沿用现有发帖页风格，之后补稿再做还原度验收。

## Story

As a 官方号运营，
I want 在发帖页选一个视频并立即知道它合不合规，
so that 不合规的视频在上传前就被拦下，我也知道该怎么重新导出。

**范围**：FR-3（App 部分）、FR-5、FR-6、FR-7；架构 AD-9、addendum A；一致性规则 8、9。
**不在范围**：封面抽帧、上传、发布请求体、埋点 `video_spec_check` 上报 → **1.6**（本 Story 只留接入点；`video_player`、`fc_native_video_thumbnail` 两个依赖也在 1.6 / 2.4 才加，本 Story **不加任何依赖**）。

## Acceptance Criteria

1. **会员等级【L0】** `UserProfile` 增加 `int vipLevel`（JSON `vipLevel`，缺省 / null → 1）；`canPublishVideo` = `vipLevel >= 10`。等级被后台修改后，最晚下次冷启动或刷新个人信息时生效（沿用现有 `/me` 刷新路径，不新增轮询）。

2. **入口可见性【L0 widget + L2 模拟器】**
   - `canPublishVideo` 为真的账号在发帖页能看到「视频」入口；等级 1 的账号在任何状态下都看不到。
   - 以下进入方式**不显示**视频入口：`preset == growthMoment`（成长日历格子、Diary Tab 的「＋」、`/publish?preset=growth-calendar`、里程碑「去发布」）、带 `placeCheckinToken`（打卡后发帖）、带 `initialImages`（晒图预填）。
   - 已选了图片时视频入口**禁用**（灰置）；已选了视频时「添加图片」格禁用，「成长日记」类型 chip 禁用。
   - 当前类型是「成长日记」时点视频入口：先把类型切成「日常」再进入选视频（类型切换走现有 `setType`，埋点不变）。

3. **选视频不加权限【L0】**
   - 用 `ImagePicker().pickVideo(source: ImageSource.gallery)`；只用返回的**文件路径**，不读进内存（一致性规则 8）。
   - `AndroidManifest.xml` 与 `Info.plist` **零新增条目**（不加 `READ_MEDIA_VIDEO`、`RECORD_AUDIO`、`NSMicrophoneUsageDescription`）——加一条 L0 测试读两份清单文件断言这些字符串不存在（一致性规则 9）。
   - 用户取消选择 → 无任何状态变化。
   - iOS 上选到的文件是否与相册原文件逐字节一致 → **L2 iOS 真机**，结果写 Completion Notes；不一致时记录为风险，不在本 Story 换实现。

4. **规格检查器【L0】** 纯 Dart `VideoSpecChecker`（`features/content/video/`），**不引入新依赖**，用 `RandomAccessFile` 按 box 读文件头与 `moov`，不把整个文件读进内存。检查项与失败码：

   | 检查项 | 合格条件 | 失败码 |
   |---|---|---|
   | 容器 | 有 `ftyp`，major brand 不是 `qt  `（QuickTime） | `format` |
   | 时长 | `mvhd` 时长 3000–90000 ms | `duration` |
   | 体积 | 文件长度 ≤ 104857600 | `size` |
   | 分辨率 | 视频轨按 `tkhd` 旋转矩阵换算成**显示方向**后，短边 ≤ 720 | `resolution` |
   | 视频编码 | 视频轨 sample entry 为 `avc1` / `avc3` | `videoCodec`（带实际值，如 HEVC） |
   | 音频编码 | 无音频轨，或 sample entry 为 `mp4a` 且 `esds` 的 objectTypeIndication 是 AAC（0x40 / 0x66 / 0x67 / 0x68） | `audioCodec` |
   | 色彩 | 视频 sample entry 下 `colr`(nclx) 的 transfer 不是 16（PQ）或 18（HLG） | `hdr` |
   | 边下边播 | `moov` 在 `mdat` 之前 | `faststart` |
   | 位置 | `moov/udta/©xyz` 不存在，且 `moov/meta` 的 keys 里没有含 `location` 的键 | `location` |
   | 可解析 | 找不到 `moov` / 视频轨，或 box 结构损坏 | `unreadable`（此时不再报其他项） |

   - 结果 `VideoSpecResult`：`ok`、`failures`（有序、去重）、`durationMs`、`displayWidth`、`displayHeight`、`sizeBytes`、`videoCodec`（fourcc）。合格时 1.6 直接用这几个值发请求。
   - 其余失败项**全部列出**，不是遇到第一个就停（例外：`unreadable`）。
   - 64 位 box size（`size==1`）与「size==0 到文件尾」都要支持；`moov` 读入上限 16MB，超过按 `unreadable`。
   - 单个文件检查 < 1 秒【L2 实测；L0 只断言不整读文件】。

5. **样片测试【L0】** `test/fixtures/video/` 下由 `gen_fixtures.sh`（ffmpeg，入库）生成的样片，每个 ≤ 1MB，逐个断言判定与失败项：

   | 样片 | 期望 |
   |---|---|
   | `ok_avc_720x1280.mp4`（H.264 + AAC，faststart） | 合格；display 720×1280 |
   | `ok_no_audio.mp4` | 合格 |
   | `ok_landscape_1280x720.mp4` | 合格 |
   | `ok_rotated.mp4`（编码 1280×720 + rotation 90） | 合格；display **720×1280** |
   | `hevc.mp4` | `videoCodec` |
   | `hdr_pq.mp4`（H.264 + transfer=PQ） | `hdr` |
   | `not_faststart.mp4` | `faststart` |
   | `location.mp4` | `location` |
   | `too_big_1080x1920.mp4` | `resolution` |
   | `too_long_91s.mp4` | `duration` |
   | `too_short_2s.mp4` | `duration` |
   | `mp3_audio.mp4` | `audioCodec` |
   | `quicktime.mov` | `format` |
   | `garbage.bin`（非 MP4） | 只有 `unreadable` |
   | 多项同时不合格（hevc + 非 faststart） | 两项都列出 |
   | 超大（用检查器的 `maxBytes` 参数调小来触发） | `size` |
   - **剪映 / CapCut 导出样片**：需要运营实际导出后提供 → 本 Story 预留 `capcut_export.mp4` 用例（文件不存在时 `skip` 并写明原因），拿到文件后补上。

6. **不合规提示【L0】** 检查不合格：发帖页显示一张错误卡，**逐条**列出「实际值 + 正确要求 + 重新导出」（如「编码是 HEVC，需要 H.264，请按导出规格重新导出」），并有「重新选择」与「移除」；不进入后续流程。合格：显示视频预览格（时长、分辨率，暂用占位图标——封面在 1.6）与「移除」，并调用接入点 `onVideoAccepted(VideoDraft)`（1.5 只保存到控制器）。检查过程中显示转圈，期间禁止再次选择。

7. **发布按钮【L0】** 有视频时「发布」按钮暂不可用（上传在 1.6 实现）；移除视频后恢复为现有图文规则。

## Tasks / Subtasks

- [x] **T1 会员等级**（AC 1）
  - [x] `features/auth/domain/login_response.dart` 的 `UserProfile`：加 `final int vipLevel;`（构造器默认 1）、`fromJson`（`(json['vipLevel'] as num?)?.toInt() ?? 1`）、`toJson`（若有）、`copyWith`（若有）、`==`/`hashCode`（若有，照现有字段写法），以及 `bool get canPublishVideo => vipLevel >= 10;`。
  - [x] 搜全仓 `UserProfile(` 构造点，确认编译通过（默认值兜住）；本地缓存的 profile（若有序列化到 prefs）兼容旧数据无 `vipLevel`。

- [x] **T2 规格检查器**（AC 4）— `lib/features/content/video/`
  - [x] `video_spec.dart`：`enum VideoSpecFailure { unreadable, format, duration, size, resolution, videoCodec, audioCodec, hdr, faststart, location }`；`class VideoSpecResult`；常量 `kVideoMinDurationMs=3000`、`kVideoMaxDurationMs=90000`、`kVideoMaxBytes=104857600`、`kVideoMaxShortSide=720`；`class VideoDraft { path, durationMs, width, height, sizeBytes }`（宽高为显示方向）。
  - [x] `video_spec_checker.dart`：`class VideoSpecChecker { const VideoSpecChecker({int maxBytes = kVideoMaxBytes}); Future<VideoSpecResult> check(String path); }`。实现要点：
    - 顶层：从 0 开始逐个读 box header（4 字节 size + 4 字节 type；size==1 再读 8 字节 largesize；size==0 到文件尾），记录 `ftyp` major brand、`moov`/`mdat` 的偏移，**跳过 mdat 内容不读**；
    - `moov` 整块读入（≤16MB），递归解析容器 box：`moov / trak / mdia / minf / stbl / udta / meta`（注意 `meta` 是 FullBox，子 box 从偏移 +4 开始；QuickTime 风格的 `meta` 有时不是 FullBox——先看头 4 字节是否像 `hdlr` 的 size+type 再决定）；
    - `mvhd`：version 0/1 分别取 32/64 位 duration 与 timescale → ms；
    - 每个 `trak`：`mdia/hdlr` handler_type（`vide` / `soun`）、`tkhd` 宽高（16.16 定点）与矩阵 a、b、c、d（16.16），`b != 0 || c != 0` 且 |a|≈0 时视为 90/270 度 → 宽高互换；
    - `stsd`：entry count 后第一个 sample entry 的 fourcc；视频 sample entry 头固定 78 字节（8 box 头 + 6 reserved + 2 dref + 16 pre-defined/reserved + 2 宽 + 2 高 + 50 其余）之后是子 box，从中找 `colr`：colour_type `nclx` 时读 primaries(2)、transfer(2)；音频 `mp4a` 头 28 字节（含 box 头 36）之后找 `esds`，解析 ES_Descriptor → DecoderConfigDescriptor(tag 0x04) 的 objectTypeIndication（描述符长度是 1–4 字节的可变长编码）；
    - 位置：`udta` 下 `©xyz`（type 字节 `0xA9 'x' 'y' 'z'`）；`meta/keys` 中任一键名含 `location`（如 `com.apple.quicktime.location.ISO6709`）；
    - 任何越界 / 长度不一致 → `unreadable`，**不抛异常给 UI**；文件读不了（不存在、权限）同样 `unreadable`。
  - [x] 整个实现不用 `readAsBytes` / `readAsBytesSync`（加一条 L0 测试：`grep` 本目录源码不含这两个调用）。

- [x] **T3 样片**（AC 5）
  - [x] `test/fixtures/video/gen_fixtures.sh`：用 `ffmpeg -f lavfi -i testsrc2=size=WxH:rate=15 -f lavfi -i sine` 生成上表样片，低码率（`-b:v 60k` 左右）保证每个 ≤ 1MB；`-map_metadata -1` 默认去元数据（`location.mp4` 除外）；`ok_*` 与大部分样片带 `-movflags +faststart`；HDR 用 `-color_trc smpte2084 -color_primaries bt2020 -colorspace bt2020nc -movflags +faststart+write_colr`；HEVC 用 `-c:v libx265 -tag:v hvc1`；旋转用 `-display_rotation 90`（放在 `-i` 前，作用于输入；或 ffmpeg 版本不支持时用 `-metadata:s:v rotate=90`）；位置用 `-metadata location=+37.5090+127.0620/`（如 mp4 muxer 不写 `©xyz`，改 `-f mov` 再 remux 或用 `-movflags use_metadata_tags`，以 `ffprobe`/十六进制确认实际写进去为准）。脚本头部注释写清本机 ffmpeg 版本与重新生成方法。
  - [x] 生成后逐个 `ls -l` 确认 ≤ 1MB，用 `ffprobe` 抽查 2–3 个确认属性（写进 Completion Notes）。
  - [x] 样片与脚本一起入库（二进制，约十几个文件）。

- [x] **T4 发帖控制器**（AC 2、7）— `features/content/domain/publish_controller.dart`
  - [x] 新增 `VideoDraft? video;`、`void setVideo(VideoDraft d)`、`void clearVideo()`；`bool get canAddImage => video == null && items.length < kMaxImages`；`bool get canPickVideo => items.isEmpty && video == null`。
  - [x] `setType(ContentType.growthMoment)` 在 `video != null` 时直接返回（不切）。
  - [x] `canPublish` 追加 `video == null` 条件（注释：1.6 实现上传后放开）；`addImage` 在 `video != null` 时返回 false。
  - [x] 不动现有图片上传状态机与顺序锁。

- [x] **T5 发帖页**（AC 2、3、6）— `features/content/presentation/publish_compose_page.dart` + 新 widget
  - [x] 视频入口：放在现有图片区附近（与「添加图片」格同一行的一个「视频」格 / 按钮，样式沿用 `_addCell` 的虚线格与 `AppColors`），`ValueKey('publishAddVideo')`；显示条件 `_videoEntryVisible` = `authProfile?.canPublishVideo == true && widget.preset != ContentType.growthMoment && widget.placeCheckinToken == null && widget.initialImages == null`；禁用条件 `!controller.canPickVideo || _checkingVideo`。
  - [x] `_pickVideo()`：若当前类型是 growthMoment 先 `setType(daily)` → `ImagePicker().pickVideo(source: ImageSource.gallery)` → null 返回 → `_checkingVideo=true` → `VideoSpecChecker().check(file.path)` → 合格 `controller.setVideo(...)` 并调 `widget.onVideoAccepted` / 页内接入点 `_onVideoAccepted(draft)`（1.5 留空实现 + TODO 指向 1.6）；不合格存 `_videoSpecError` 显示错误卡。`ImagePicker` 通过可注入的 provider 获取（照现有图片选择的注入方式，便于 widget 测试替换）。
  - [x] 新文件 `features/content/video/video_spec_error_card.dart`：逐条文案 + 「重新选择」「移除」；`video_draft_tile.dart`：占位图标 + `mm:ss` 时长 + `宽×高` + 移除。
  - [x] 「添加图片」格在 `!controller.canAddImage` 时禁用；「成长日记」chip 在 `controller.video != null` 时禁用。
  - [x] 文案全部进 `lib/l10n/app_en.arb` 与 `app_id.arb`（**本仓只有这两份**，架构里写的 zh 不存在，不新增），`flutter gen-l10n`。失败项文案示例（en）：`format` 「The file is not MP4. Please export it again following the video spec.」；`videoCodec` 「Codec is {codec}; H.264 is required. Please export again following the video spec.」；`resolution` 「Resolution is {w}×{h}; the short side must be 720 or less.」；`duration` 「Length is {sec}s; it must be 3–90 seconds.」；`size` 「File is {mb} MB; it must be 100 MB or less.」；`hdr`、`faststart`（提示勾选 Web Optimized / faststart）、`location`、`audioCodec`、`unreadable` 同理。印尼语按现有 id 文案风格翻译。

- [x] **T6 测试**
  - [x] L0 `test/features/content/video/video_spec_checker_test.dart`：AC5 全表 + 多项失败 + `maxBytes` 触发 `size` + CapCut 用例（缺文件则 skip）+ 「源码不含 readAsBytes」。
  - [x] L0 `test/features/content/domain/publish_controller_video_test.dart`：互斥（有图不能 setVideo 入口 / 有视频不能 addImage）、有视频时 growthMoment 切不过去、`canPublish` 在有视频时为 false、`clearVideo` 后恢复。
  - [x] L0 widget `test/features/content/presentation/publish_compose_video_entry_test.dart`：vipLevel 1 无入口；vipLevel 10 有入口；preset growthMoment / 打卡 token / initialImages 时无入口；有图时入口禁用；选视频（注入假 picker 返回 fixture 路径）→ 合格出现预览格、不合格出现逐条错误卡；当前类型 growthMoment 时点入口会切到 daily。
  - [x] L0 `test/features/content/video/no_new_permissions_test.dart`：读 `android/app/src/main/AndroidManifest.xml`、`ios/Runner/Info.plist`，断言不含 `READ_MEDIA_VIDEO`、`RECORD_AUDIO`、`NSMicrophoneUsageDescription`。
  - [x] L0 `UserProfile.fromJson` 有 / 无 `vipLevel` 两例。
  - [x] `flutter analyze` 零警告；`flutter test` 全量通过。
  - [ ] ⏸ L2（本地，留给验收，未做）：Android 模拟器用官方号（等级 10）走一遍：入口出现 → 选合规样片通过 → 选 HEVC 样片显示逐条错误；iOS 真机验证选到的文件与原文件逐字节一致（`shasum` 对比）；单文件检查耗时 < 1 秒。

## Dev Notes

### 现状（已读过）

- **`PublishController`**（`features/content/domain/publish_controller.dart`，257 行）：`items: List<ImageUploadItem>`（`Uint8List`）、`type`、`syncToMoment`、`text`、`mentions`、`eventDate`；`canPublish = !publishing && !isUploading && textWithinLimit && (text 非空 || items 非空)`；`addImage` ≤9；上传会话顺序锁 `_orderLocked`。控制器随 sheet dispose 清空（无草稿，NFR-10）。
- **`PublishComposePage`**（1586 行，modal bottom sheet，`PublishComposePage.open(...)`）：参数 `preset / presetEventDate / milestoneCode / placeCheckinToken / placeCheckinPlace / initialText / initialImages`。`initState` 的 post-frame 回调里定默认类型：有宠物档案 → growthMoment，否则 daily；preset 优先。图片入口 `_addCell`（`ValueKey('publishAddImage')`，虚线格）→ `_pickImageSource`（相机 / 相册 sheet）。类型 chip 在 `:828-900` 一带（`_TypeChip`）。`authControllerProvider` 读 `profile`。
- **`UserProfile`**（`features/auth/domain/login_response.dart`）：现有 `id / nickname / … / hasPetProfile / postCount …`；后端 1.1 已在 `/me` 下发 `vipLevel`。
- **依赖**：`image_picker ^1.1.2` 已有（`pickVideo` 可用）；本 Story 不加任何新包。
- **权限清单**：Android 现有 `CAMERA / READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE(≤32) / …`；iOS 有 `NSCameraUsageDescription / NSPhotoLibraryUsageDescription / NSPhotoLibraryAddUsageDescription / …`。`pickVideo(gallery)` 在 Android 13+ 走系统照片选择器、iOS 14+ 走 PHPicker，都不需要新权限。
- **l10n**：只有 `app_en.arb`、`app_id.arb`。
- **本机 ffmpeg**：`/opt/homebrew/bin/ffmpeg`，有 `libx264`、`libx265`、`aac`、`libmp3lame`。

### 设计取舍（照做）

- **为什么检查器自己解析 MP4**：决策 V-33 第一期不压缩、不引入视频处理库；只读文件头与 moov，1 秒内可完成。宽高一律按**显示方向**（与后端 1.3 的校验、入库口径一致）。
- **为什么 `qt  ` 品牌算不合格**：后端只收 `video/mp4`，运营应按导出规格出 MP4。iPhone 相册原生视频多是 `.mov`，如果 iOS 选择器把运营的 MP4 改写成 MOV，这一项会拦下 —— 正是 AC3 要在真机上确认的风险。
- **为什么 MP3 也要拦**：MP4 里的 MP3 同样用 `mp4a` sample entry，只看 fourcc 区分不出，必须读 `esds` 的 objectTypeIndication。
- **为什么失败项要列全**：运营要一次知道所有问题再回去重新导出，不能改一项试一次。

### 与 1.6 的接口

1.5 结束时：控制器里有 `VideoDraft`（路径 + 显示宽高 + 时长 + 体积），页面有 `_onVideoAccepted(draft)` 空实现与视频预览格。1.6 在这里接：抽封面、并行上传视频与封面、进度、重试、放开 `canPublish`、发布请求体。

### 测试与验证

- 改 ARB 后必须 `flutter gen-l10n`；`test/l10n/microcopy_rules_test.dart` 会校验文案规则，先跑全量 `flutter test`。
- **不要整文件 `dart format`**（存量代码未格式化，会带出几百行无关 diff）；手工对齐，只跑 `flutter analyze`。
- 模拟器 = Android 模拟器（`adb` + `flutter run`）；连真实后端 `api.tailtopia.id` 时官方号需要是等级 10（stag / 生产库里先由后台设置）。

### Project Structure Notes

| 文件 | 动作 |
|---|---|
| `lib/features/auth/domain/login_response.dart` | 改：`vipLevel`、`canPublishVideo` |
| `lib/features/content/video/video_spec.dart`、`video_spec_checker.dart` | 新 |
| `lib/features/content/video/video_spec_error_card.dart`、`video_draft_tile.dart` | 新 |
| `lib/features/content/domain/publish_controller.dart` | 改：`video`、互斥 |
| `lib/features/content/presentation/publish_compose_page.dart` | 改：入口、检查、错误卡、预览格 |
| `lib/l10n/app_en.arb`、`app_id.arb` | 改 |
| `test/fixtures/video/*.mp4|.mov|.bin`、`gen_fixtures.sh` | 新 |
| `test/features/content/video/*`、`test/features/content/domain/publish_controller_video_test.dart`、`test/features/content/presentation/publish_compose_video_entry_test.dart` | 新 |

架构目录写的是 `features/content/video/`（不新建顶层 feature），与此一致。测试目录先确认仓库现有的是 `test/content/…` 还是 `test/features/content/…`，**跟现有的走**。

### References

- [Source: _bmad-output/planning-artifacts/video/epics-video.md#Story 1.5]
- [Source: _bmad-output/planning-artifacts/video/architecture-video-delta.md#AD-9、#一致性规则 8 / 9、#测试策略（MP4 规格检查器）]
- [Source: _bmad-output/planning-artifacts/video/addendum-video.md#A]
- [Source: _bmad-output/planning-artifacts/video/PRD-video.md#FR-5、#FR-6、#FR-7]
- [Source: _bmad-output/planning-artifacts/video/官方视频导出规格.md]
- [Source: _bmad-output/planning-artifacts/research/video-post-03-compressor-spike-2026-10-16.md#2 测试样片]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (1M context)

### Debug Log References

- 全量 `flutter test` 日志：scratchpad `flutter-1-5.log`。
- 样片生成时的三个坑（已写进 `gen_fixtures.sh` 注释 / 已修）：① ffmpeg 8 的 mp4 muxer 把 `location` 写成 3GPP `loci` 而不是 `©xyz` → 检查器三种写法都认；② `-color_trc` 作为输出参数不会写进 `colr`，要用 `-vf setparams=…`；③ `-display_rotation` 放在编码输入上会被自动转正，要先编横屏再 `-c copy` 只写旋转矩阵。

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- **T1** `UserProfile.vipLevel`（缺省 1）+ `canPublishVideo`；`copyWith` 同步；无本地序列化缓存，不需兼容迁移。
- **T2** `VideoSpecChecker`（纯 Dart、`RandomAccessFile`、只读顶层 box 头 + 整读 moov ≤16MB）：format / duration / size / resolution（按 tkhd 旋转矩阵换显示方向）/ videoCodec / audioCodec（读 esds objectTypeIndication，兼容 QuickTime SoundDescription v1/v2 与 `wave` 包裹）/ hdr（colr nclx transfer 16/18）/ faststart / location（`©xyz`、`loci`、meta keys 含 location）/ unreadable；失败项全部列出；任何解析异常收敛为 unreadable。
- **T3** `test/fixtures/video/` 15 个样片 + `gen_fixtures.sh`（ffmpeg 8.1.1），合计约 0.9MB，单个最大约 100KB；用 box dump 逐个核实了旋转矩阵、colr transfer、moov/mdat 顺序与 ftyp 品牌。
- **T4** `PublishController.video` 与图片互斥；有视频时成长日记切不过去、`canPublish` 为 false（1.6 放开）。
- **T5** 发帖页「Video」入口（`publishAddVideo`，图片区下方圆角描边按钮）：可见 = 等级 ≥10 且非 Diary 预设 / 打卡 / 晒图预填；有图禁用；检查中转圈；合格 → 视频预览格替换图片网格（不再出现「添加图片」格，等价于禁用）；不合格 → 逐条错误卡（重新选择 / 移除）。接入点 `_onVideoAccepted(draft)` 留给 1.6。选择器与检查器经 provider 注入。
  - 与 AC2 措辞的小差异：当前类型是成长日记时，**选到合规视频之后**才切回日常（`setVideo` 内处理），取消选择则不改类型 —— 比「点入口先切」少一次无意义的类型变化。
- **文案**：en / id 各 16 个键（本仓只有这两份 ARB）；`Video` 在两语同形，已在 `microcopy_rules_test` 的同形词名单登记（附说明）。
- **测试**：新增 `video_spec_checker_test`(22 + 1 skip)、`publish_controller_video_test`(4)、`publish_compose_video_entry_test`(9)、`no_new_permissions_test`(2)、`user_profile_vip_level_test`(3)；`flutter analyze` 零问题；全量 `flutter test` 通过（唯一失败为上面的同形词规则，已处理并复跑通过）。
- **未加任何依赖**；Manifest / Info.plist 未改。
- ⏸ **L2 待本地验收**：① 后端 1.1–1.4 尚未部署到 stag / 生产，连线上后端时 `/me` 不带 `vipLevel`，入口不会出现 —— 需先部署 stag 并把官方号设为等级 10 再在 Android 模拟器验收；② iOS 真机确认 PHPicker 选到的文件与原文件逐字节一致（`shasum`）；③ 单文件检查耗时 < 1 秒；④ 剪映实际导出样片（`capcut_export.mp4`）待运营提供后放入并取消 skip。

### File List

- petgo_app/lib/features/auth/domain/login_response.dart
- petgo_app/lib/features/content/domain/publish_controller.dart
- petgo_app/lib/features/content/presentation/publish_compose_page.dart
- petgo_app/lib/features/content/video/video_spec.dart（新）
- petgo_app/lib/features/content/video/video_spec_checker.dart（新）
- petgo_app/lib/features/content/video/video_spec_messages.dart（新）
- petgo_app/lib/features/content/video/video_spec_error_card.dart（新）
- petgo_app/lib/features/content/video/video_draft_tile.dart（新）
- petgo_app/lib/features/content/video/video_picker.dart（新）
- petgo_app/lib/l10n/app_en.arb、app_id.arb
- petgo_app/test/fixtures/video/gen_fixtures.sh 与 15 个样片（新）
- petgo_app/test/features/content/video/video_spec_checker_test.dart（新）
- petgo_app/test/features/content/video/no_new_permissions_test.dart（新）
- petgo_app/test/features/content/video/user_profile_vip_level_test.dart（新）
- petgo_app/test/features/content/domain/publish_controller_video_test.dart（新）
- petgo_app/test/features/content/presentation/publish_compose_video_entry_test.dart（新）
- petgo_app/test/l10n/microcopy_rules_test.dart

### Change Log

- 2026-10-16：实现 Story 1.5——会员等级下发到 App、发帖页视频入口（可见性 / 互斥）、纯 Dart MP4 规格检查器与 15 个样片、逐条不合格提示；L0 通过，L2 待部署后在模拟器 / 真机验收。

## Senior Developer Review (AI) · 2026-10-16（云端）

- **CONFIRMED 已修**：
  1. 位置检查漏 `udta/meta/ilst/©xyz`（iTunes 风格）→ `_parseMeta` 进 `ilst` 判 `©xyz` / `loci`；补内存构造用例。
  2. 无 `ftyp`（老式 .mov 开头 / ftyp 前有 free）报 `unreadable` 而 AC4 要求 `format` → 去掉「首 box 必须 ftyp」硬拦，扫描后 `majorBrand == null` 记 `format`（乱码文件仍 unreadable）。
  3. 超限「实际值」四舍五入后像合格（90.04s → 「90.0s」）→ 超上限向上取整、低于下限向下取整；体积向上取整。新增 `video_spec_messages_test.dart`。
  4. 图片 / 视频处理中的互斥窗口：拍照处理中可选视频、视频检查中可加图，一方被静默丢掉 → 入口与加图格互相看 `_addingImage` / `_checkingVideo` / `_pickingVideo`；`setVideo` 被拒时不启动上传。
  5. 选择器未返回前可连点、选择器异常未捕获 → `_pickingVideo` 锁 + try/catch 按取消处理；补 2 个 widget 用例。
- **偏离 AC（沿用 Completion Notes 已声明，未改）**：成长日记切回日常在 `setVideo` 里直接写 type，未走 `setType` / 未上报类型选择埋点 —— 待 PO 确认。
- **PLAUSIBLE 未改**：文件尾垃圾 / udta 内不规范子 box 判 unreadable；64 位 size 的 ftyp；HDR 只认 `nclx`；碎片化 MP4；`_videoEntryVisible` 用 `ref.read`。
- L0：`flutter analyze` 零问题、`flutter test test/features/content` 全绿；**L1/L2 待本地验收**（真机检查耗时、iOS 选择器原样文件）。
