# PRD-video 附录：技术实现参考

> 这里放技术实现的细节，供架构 delta 和 story 拆分时参考，不属于需求本身。详细依据见调研 01–03。

## A. 视频规格检查与封面（对应 FR-7、FR-8）

> 第一期**不在手机上压缩**（决策 V-33）。压缩库选型结论（`pro_video_editor`，V-28）和接入注意事项保留在调研 03，等开放会员时再用。

- **规格检查**：在 App 内写一个只读的 MP4 文件头解析器（纯 Dart，不引入依赖）。按 box 结构读取以下数据：
  - `ftyp` 前置，且 `moov` 在 `mdat` 之前，即为 faststart。
  - `moov/trak/mdia/minf/stbl/stsd`：视频轨的 sample entry 为 `avc1` 或 `avc3` 才算 H.264；`hvc1`、`hev1` 是 HEVC，拒绝。音频轨为 `mp4a`，或者没有音频轨。
  - `tkhd`：宽、高和旋转矩阵，换算成**显示方向**后再判断短边。
  - `mvhd`：时长。
  - 视频 sample entry 下的 `colr`（nclx）：transfer 为 16（PQ）或 18（HLG）的是 HDR，拒绝。
  - `udta` 下的 `©xyz`，或 `meta` 里的位置键：有位置信息就拒绝，提示用户重新导出。
  - 体积：读文件长度。
  - 只读文件头和 moov，不加载整个文件。moov 在文件末尾时，从文件尾部倒着找。
  - **用单元测试固定这套解析**：调研 03 的样片（A–F）加上剪映 / CapCut 实际导出的文件，作为测试用例。
- **封面**：默认帧用 `fc_native_video_thumbnail` 抽取。它调用系统接口（安卓 MediaMetadataRetriever / iOS AVAssetImageGenerator），支持 SwiftPM 和 Built-in Kotlin，2026-06 仍在更新。抽第 1 秒，输出短边 720 的 JPEG。替换封面时复用现有的选图链路（`ImageProcessor`：去 EXIF、压缩），再缩放到短边 720。
- 整个过程用文件路径操作，不要把视频读进内存（现有图片链路用的是 `Uint8List`，见调研 01 §3）。
- **宽高口径**：上报和入库一律用**旋转后的显示宽高**。

## B. 播放（对应 FR-15）
- 用 `video_player`，底层是系统播放器（ExoPlayer / AVPlayer），免费，体积增量很小。
- 第一期不做磁盘缓存。

## C ~ F（已被架构取代）

> 2026-10-16：原 C（数据模型）、D（服务端）、E（App 端）、F（埋点事件）四节写于压缩方案时期，与后续决策 V-33 / V-36 / V-38 冲突，**整节作废**。以 `architecture-video-delta.md` 为准：
> - 数据模型 → AD-1、AD-2
> - 上传、校验、审核路由 → AD-3 ~ AD-5
> - 文件可见性 → AD-6
> - 旧版过滤与 DTO → AD-7、AD-8
> - App 端 → AD-9、AD-10
> - CDN → AD-11
> - 后台 → AD-12
> - 埋点 → AD-10 埋点表
>
> 本文件只保留 A（规格检查与封面）和 B（播放）两节。
