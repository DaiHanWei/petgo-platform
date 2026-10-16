# 发帖支持短视频 · 调研 03：端上压缩库技术验证

> 分支 `research/video` · 2026-10-16 · 前置：[调研 02 规格](video-post-02-scope-and-spec-2026-10-16.md)
> 验证工程与样片在会话临时目录中，未入库；所有结论可用 §6 的方法复现。

## 0. 结论

> **2026-10-16 拍板**：采用 `pro_video_editor`，选型不做真机验证（专题线 video 决策 V-28）。§5 的真机项改到 FR-7 的 L2 验收中确认。
> **同日更新（决策 V-33）**：第一期只有官方号发视频，改为运营在电脑上按规格导出、App 只做规格检查，**不接入压缩库**。本文结论留给开放会员时使用。

- **推荐 `pro_video_editor`**，备选 `v_video_compressor`。两者都满足规格（H.264、≤720、≈2Mbps、30fps、faststart、方向正确）。
- `video_compress`（最流行的老库）**淘汰**：码率不可控（iOS 达 10.6Mbps，90s 视频 121MB），安卓输出不是 faststart，iOS 不降帧，2025-02 起不再更新，且不支持 SwiftPM / Built-in Kotlin（Flutter 后续版本会编译不过）。
- `native_video_compress` **淘汰**：在项目当前的 AGP 9.0.1 下编译失败。
- **还缺真机验证**：真实 HDR 素材的转换效果、真机耗时（见 §5）。

## 1. 候选与筛选

| 库 | 版本 | 安卓实现 | iOS 实现 | 结果 |
|---|---|---|---|---|
| `pro_video_editor` | 2.35.0（2026-10-15） | Media3 1.10.1 Transformer + InAppMp4Muxer，显式 HDR→SDR 色调映射 | AVAssetWriter（码率精确）；HEVC 10bit/HDR 先转 H.264 8bit SDR | ✅ 推荐 |
| `v_video_compressor` | 2.2.3（2026-09-26） | Media3 1.8.0 Transformer + InAppMp4Muxer；**未见 HDR 色调映射设置** | AVAssetExportSession + 文件长度预算近似码率 | ✅ 备选 |
| `video_compress` | 3.1.4（2025-02-13） | otaliastudios Transcoder（MediaMuxer） | AVAssetExportSession 预设 | ❌ |
| `native_video_compress` | 0.0.4 | Media3 1.5.0 | AVAssetWriter | ❌ 编译失败 |
| `light_compressor` / `video_compress_plus` / `flutter_video_compress` | 2019–2023 | — | — | ❌ 停更，未测 |
| `ffmpeg_kit_flutter_new` | — | ffmpeg | ffmpeg | ❌ 不考虑（体积大几十 MB，GPL 版） |

## 2. 测试样片

| 样片 | 内容 | 验证点 |
|---|---|---|
| A | 1080×1920 H.264 30fps 8Mbps **90s**，非 faststart | 典型 AI 短剧导出、最坏时长 |
| B | 1080×1920 **HEVC 10bit HDR(PQ)** 60fps 20s | iPhone 实拍风格 |
| C | 720×1280 H.264 2Mbps 20s faststart | 已合规（是否会被跳过） |
| D | 1920×1080 横屏 15s | 横屏 |
| E | D + rotation=90 元数据 | 手机竖拍的旋转标记 |
| F | 1080×1920 **HEVC 8bit** 60fps 20s | HEVC→H.264、60→30fps |

目标参数：短边 720、视频 2Mbps、≤30fps、AAC、faststart。

## 3. 结果

### 安卓（模拟器 API 36 arm64，软件编码器）

| 库 | A 90s | 体积 | 码率 | faststart | 降帧 | HEVC→H.264 | 方向 | HDR（B） |
|---|---|---|---|---|---|---|---|---|
| pro | 44.7s | 24.6MB | 2.0 | ✅ | ✅ | ✅ | ✅ | 模拟器不支持 Main10 解码 |
| vvc | 40.6s | 25.4MB | 2.07 | ✅ | ✅ | ✅ | ✅ | 同上 |
| vc | 47.7s | 45.3MB | 3.87 | ❌ | — | — | ✅ | 同上 |

- Media3 系（pro / vvc）的竖屏输出存为 1280×720 加 rotation 元数据，播放器按元数据旋转显示，已截帧确认方向正确；vc 输出物理旋转为 720×1280。
- B 样片三个库全部失败，日志为 `NoSupport hvc1.2.4`：模拟器的 HEVC 解码器不支持 Main10，属于**模拟器限制**，需要真机验证。
- 模拟器只有软件编码器，所以输出 profile 是 Constrained Baseline，耗时也不代表真机。

### iOS（模拟器 iPhone 17 / iOS 26.5）

| 库 | A 90s 体积 | 码率 | faststart | 降帧（B/F） | 音频 | 20s 片耗时 |
|---|---|---|---|---|---|---|
| pro | 24.1MB | 2.01–2.09 | ✅ | ✅ 30 | 统一转 AAC ~125k | 30–40s（AVAssetWriter 逐帧，模拟器慢） |
| vvc | 24.2MB | 1.86–1.99 | ✅ | ✅ 30 | 原样透传 | 5–13s |
| vc | **121.5MB** | **10.6** | ✅ | ❌ 仍 60 | 原样透传 | 4–7s |

- iOS 上三个库都输出物理竖屏 720×1280（无 rotation 元数据）。
- HDR 样片（B）在 iOS 上三个库都转成了 bt709 SDR；但测试图案是合成的，**看不出色调映射是否正确**，需要 iPhone 实拍 HDR 素材验证。

### 封面抽帧（第 1 秒）

| 库 | 尺寸 | 耗时（安卓模拟器） |
|---|---|---|
| pro | 720×1280 | 1.8s |
| vvc | 720×1280 | 0.3s |
| vc | 288×512（不够清晰） | 0.25s |

### 安装包增量（release，相对空工程）

| 库 | 安卓 arm64 APK | iOS Runner.app |
|---|---|---|
| pro | +1.1MB | +2.8MB |
| vvc | +0.8MB | +0.5MB |

### 维护情况（pub.dev，2026-10-16）

| 库 | 版本数 / 最近发布 | 30 天下载 | 评分 | 许可 | SwiftPM / Built-in Kotlin |
|---|---|---|---|---|---|
| pro | 187 / 2026-10-15 | 25k | 160/160 | BSD-3 | ✅ / ✅ |
| vvc | 14 / 2026-09-26 | 12.7k | 160/160 | MIT | ✅ / ✅（要求 iOS ≥15） |
| vc | 14 / 2025-02-13 | 206k | 140/160 | MIT | ❌ / ❌ |

## 4. 推荐理由与接入注意

**选 `pro_video_editor`：**
1. 两端都有**显式的 HDR→SDR 处理**（安卓 OpenGL 色调映射；iOS 先转 8bit SDR）。vvc 在安卓上没有设置 HDR 模式，iPhone HDR 视频在安卓上压缩的结果没有保障。
2. iOS 码率最准，音频也统一转为 AAC。
3. 维护最活跃，许可宽松。
4. 代价：安装包增量略大（iOS +2.8MB）；iOS 模拟器上明显较慢，**需要真机确认 90s 视频的耗时可以接受**。若真机太慢，就换 vvc。

**接入时要处理的问题（两个库都适用）：**
- **降帧后码率减半**：60fps 片降到 30fps 后，安卓实测码率约 1Mbps，因为编码器是按源帧率配置的。降帧时需要提高目标码率，或改为指定输出帧率后再实测。
- **不会自动跳过已合规的视频**：C 样片也被重编码了。跳过逻辑要自己写：先读元数据，满足 ≤720、H.264、≤30fps、码率达标时不重编码；**但仍要检查 faststart**，AI 短剧导出工具（如 ffmpeg 默认）常常不是 faststart，此时需要做一次只调整 moov 位置、不重编码的 remux。
- **pro 的质量预设对竖屏缩放有误**：`withQualityPreset(p720)` 会按 1280×720 取最小缩放比，竖屏 1080×1920 会被缩成 405×720。要自己按短边算 `ExportTransform(scaleX, scaleY)`（验证工程就是这样做的）。
- **vvc 的 `outputPath` 会被当成目录**，输出文件在该目录下，文件名自动生成。
- 两个库都提供进度和取消接口（pro：`progressStreamById` + `cancel(taskId)`；vvc：`onProgress` + `cancelCompression()`），可以支撑发帖页的进度条和取消按钮。

## 5. 待真机验证

| 项 | 方法 | 判定 |
|---|---|---|
| HDR 转换 | iPhone 拍一段 HDR（杜比视界）视频，分别在 iPhone 和安卓真机上用 pro 压缩，与原片并排对比 | 不发灰、不过曝 |
| 耗时 | 90s 1080p 样片 A，iPhone（≥ A15）与中端安卓各跑 3 次 | 建议 ≤ 视频时长的 1/2（≤45s） |
| 发热 / 后台 | 压缩过程中切到后台再回来 | 不崩溃；任务失败时有可重试的提示 |

## 6. 复现方法

1. 用 ffmpeg 生成 §2 的样片（testsrc2 + noise + sine 音频）。
2. 建空的 Flutter 工程，依赖候选库，启动后对 `Documents/in` 下的样片依次压缩，结果写入 `Documents/out`。
3. 拉回输出，用 `ffprobe` 取编码、分辨率、帧率、码率、色彩、旋转，并解析顶层 atom 顺序判断 faststart（`moov` 在 `mdat` 之前）。
4. 安装包增量：空工程与单库工程分别执行 `flutter build apk --release --split-per-abi --target-platform android-arm64` 和 `flutter build ios --release --no-codesign`，对比产物大小。
