# Story 2.2: Feed 卡与详情页按露出位置开窗

Status: review

> 版本 V1.3.4 · 主题 app · Epic 2「发图不丢画面」· FR-125.3 / 125.8 / 125.9 / 125.10 · AD-6 / AD-7 · NFR2 / NFR7
> 本 story **只动 App**（读 2.1 下发的 `imageCropOffsets` 并在渲染时换对齐方式）。后端无改动。

## Story

As a 刷首页的用户,
I want 超宽 / 超长图在卡片和详情页里露出发帖人选的那一段,
so that 我看到的是发帖人想展示的画面，而不总是正中间。

## Acceptance Criteria

**AC1 — 纯函数 `feedImageAlignment`** `[L0]`
在 `petgo_app/lib/features/content/domain/feed_image_layout.dart` 新增顶层函数：
```dart
Alignment feedImageAlignment(ImageSize? size, double? cropOffset)
```
规则（写死）：
1. `cropOffset == null` 或非有限数 → `Alignment.center`；
2. `size == null` 或 `!size.isUsable` → `Alignment.center`；
3. `size.ratio > kFeedRatioMax`（过宽）→ `Alignment(2 * o - 1, 0)`；
4. `size.ratio < kFeedRatioMin`（过竖）→ `Alignment(0, 2 * o - 1)`；
5. 区间内（`kFeedRatioMin <= ratio <= kFeedRatioMax`）→ `Alignment.center`（即使带了 offset 也忽略）；
其中 `o = cropOffset.clamp(0.0, 1.0)`。
单测覆盖：3:1 宽图 offset 0 / 0.5 / 1 → `Alignment(-1,0)` / `Alignment(0,0)` / `Alignment(1,0)`；1:2 竖图同三值 → `Alignment(0,-1)` / `Alignment(0,0)` / `Alignment(0,1)`；规则 1 / 2 / 5 各一例；offset `1.2` 被夹成 `Alignment(1,0)`。

**AC2 — 窗口比例不变（算术断言）** `[L0]`
`resolveFeedImageAspect` / `resolveDetailImageAspect` **一行不改**。单测断言：对 `ImageSize(3000, 1000)` 与 `ImageSize(1000, 2000)`，在护栏不介入时（`maxImageHeight: 10000`）`resolveFeedImageAspect(...) == clampFeedRatio(size.ratio)`，分别等于 `kFeedRatioMax`（1.78）与 `kFeedRatioMin`（0.75）（PRD AC2）。

**AC3 — Feed 卡按 offset 开窗** `[L0]` 结构断言 · `[L2]` 视觉
1. `FeedItem` 新增 `imageCropOffsets`（`List<double?>`，默认 `const []`）与安全取值 `double? cropOffsetAt(int i)`（越界 → null）；`fromJson` 读 `json['imageCropOffsets']`；`copyWith` 透传。
2. `FeedImage` 新增两个可选入参 `sizes`（`List<ImageSize?>`，默认 `const []`）与 `cropOffsets`（`List<double?>`，默认 `const []`）。第 `i` 张图的 `Image` 设 `alignment: feedImageAlignment(sizeAt(i), offsetAt(i))`，其中 `sizeAt(0)` = `widget.declaredSize ?? _decoded ?? sizes[0]`，`sizeAt(i>0)` = `sizes[i]`（越界 null）；`fit` 仍为 `BoxFit.cover`；容器高度仍只按首图（`resolveFeedImageAspect` 调用不变）。
3. `MasonryCard` 给 `FeedImage` 传 `sizes: item.imageSizes, cropOffsets: item.imageCropOffsets`。顶置的**已发布内容**（`PinnedSlot.item`，`feed_view.dart` L323 经 `MasonryCard` 渲染）因此同样按 offset 开窗。
4. 结构断言（widget 测试）：3:1 宽图 + offset 0 → `FeedImage` 内 `Image.alignment == Alignment(-1, 0)`、`Image.fit == BoxFit.cover`、`AspectRatio.aspectRatio == kFeedRatioMax`。
5. `[L2]` 模拟器人眼：3:1 宽图 offset 0 / 1 时卡片露出最左 / 最右一段；1:2 竖图 offset 0 / 1 露出最上 / 最下一段。

**AC4 — 详情轮播按 offset 开窗、与 Feed 同比例同位置** `[L0]` 结构断言 · `[L2]` 视觉
1. `ContentDetail` 新增 `imageCropOffsets`（`List<double?>`，默认 `const []`）+ `double? cropOffsetAt(int i)`；`fromJson` 读同名键。
2. `content_detail_page.dart` 的 `_ImageCarousel` 新增入参 `cropOffsets`（`List<double?>`），调用点传 `detail.imageCropOffsets`；第 `i` 页 `AppImage.widget(...)` 增传 `alignment: feedImageAlignment(sizeAt(i), offsetAt(i))`。
3. `AppImage.widget` 新增可选参数 `Alignment alignment = Alignment.center`，原样转给 `Image.asset` / `Image.file` / `Image.network`（默认值 = 改前行为，其余调用点零改动）。
4. 同一组 `(size, offset)` 在 Feed 与详情得到的 `Alignment` 相同（两处调同一个函数，单测各取一次比较）；比例仍由 `resolveDetailImageAspect` → `resolveFeedImageAspect` 决定（V1.3.0「详情与 Feed 一致」不变）。
5. `[L2]` 模拟器：同一帖从 Feed 点进详情，露出段一致、无跳变。

**AC5 — 无 offset 时与改动前一致（向后兼容硬验收）** `[L0]` 结构断言 · `[L2]` golden
1. `[L0]` 无 `imageCropOffsets` 的帖子（存量 / 区间内）：Feed 卡与详情轮播里每张 `Image` 的 `alignment == Alignment.center`、`fit == BoxFit.cover`，`AspectRatio.aspectRatio` 与不传 `cropOffsets` 时**完全相等**（同一 `FeedItem` 带 / 不带 `imageCropOffsets: [null]` 各 pump 一次比较）。
2. **不写 golden 截图测试**（云端 Linux 与本机 macOS 字体渲染不同，基准图必红；仓库也没有任何 golden 测试）。PRD AC4「改动前后截图比对」标 `[L2]`，留本地人工：同一存量帖改动前后各截一张模拟器图逐像素比对。

**AC6 — 灯箱不改（回归）** `[L0]`
`lib/shared/media/image_lightbox.dart` **零 diff**。详情页点图仍 `ImageLightbox.open(urls: widget.urls, ...)` 展示完整原图（`BoxFit.contain`）；多图滑动位置与轮播同步（既有 `test/shared/lightbox_*` 全绿即视为通过）。

**AC7 — 取档（流量）** `[L0]`
1. Feed 卡 `FeedImage.thumbWidth` 默认 `800` 不变；详情轮播 `thumbWidth: 1080` 不变；灯箱 `_thumbWidth = 1080` 垫底再原图不变（AD-7 表）。
2. `test/shared/app_image_test.dart` 新增一例，名称以 `AD-7` 开头：对生产域名 `https://tailtopia.oss-ap-southeast-5.aliyuncs.com/public/1/abc.jpg`，`ossResized(url, width: 800)` 含 `x-oss-process=image/resize,w_800`、`ossResized(url, width: 1080)` 含 `w_1080`（钉住「Feed 列表绝不拉原图」所依赖的域名判定）。

## Tasks / Subtasks

- [x] **T1 纯函数 + 解析**（AC1 / AC2）
  - [x] `feed_image_layout.dart` 顶部加 `import 'package:flutter/painting.dart' show Alignment;`；新增 `feedImageAlignment`（AC1 规则，函数 doc 写「BoxFit.cover 下 0 / 0.5 / 1 恰对应贴起始边 / 居中 / 贴结束边；只换对齐，不碰比例」）
  - [x] 同文件新增 `List<double?> cropOffsetListFromJson(Object? raw)`：非 List → `const []`；元素 `num` 且有限 → `toDouble()`，否则 null（容忍 `0` / `1` 以整数形式下发）
  - [x] `test/content/feed_image_layout_test.dart` 追加一个 `group('V1.3.4 2.2 露出位置', ...)`：AC1 全部断言 + AC2 两条算术断言 + `cropOffsetListFromJson` 三例（缺字段 / 含 null / 整数 0 与 1）
- [x] **T2 模型**（AC3.1 / AC4.1）
  - [x] `feed_item.dart`：构造器 L101 附近加 `this.imageCropOffsets = const []`；字段 + doc；`cropOffsetAt`；`copyWith`（L190–208）透传；`fromJson`（L227 附近）`imageCropOffsets: cropOffsetListFromJson(json['imageCropOffsets'])`
  - [x] `content_detail.dart`：同上（构造器 L24 附近、字段在 `imageSizes` L59 之后、`cropOffsetAt` 照 `sizeAt` L64–65、`fromJson` L103 之后）
- [x] **T3 Feed 卡**（AC3）
  - [x] `lib/shared/widgets/feed_image.dart`：构造器加 `this.sizes = const []`、`this.cropOffsets = const []` 两入参与 doc；`_imageAt(i)`（L208–224）的 `Image(...)` 增 `alignment:`（AC3.2 取法）；`build` 内 `resolveFeedImageAspect` 调用（L230–234）**不改**
  - [x] `lib/shared/widgets/masonry_card.dart` L236–253 `FeedImage(...)` 增两实参
  - [x] `lib/features/content/presentation/promo_pinned_card.dart` L55–62 **不改**（推广卡是运营素材，无 offset 数据 → 默认居中；见「关键设计点」）
- [x] **T4 详情轮播**（AC4）
  - [x] `lib/shared/widgets/app_image.dart` `widget(...)`（L43–89）加 `Alignment alignment = Alignment.center` 并转发三种 `Image.*`
  - [x] `content_detail_page.dart`：`_ImageCarousel`（L539–565）加 `required this.cropOffsets` 字段与 doc；调用点 L247–256 传 `cropOffsets: detail.imageCropOffsets`；`AppImage.widget(...)`（L624 起）增 `alignment:`；`resolveDetailImageAspect` 调用（L600–604）不改
  - [x] 顺手把同一 `AspectRatio` 上方注释（约 L610–611）里过期的「clamp 0.75~1.34」改为「0.75~1.78」（AD §13 第 5 条，仅注释）
- [x] **T5 测试**（见下表）
- [x] **T6 L0**：`cd petgo_app && flutter analyze && flutter test`；**不对整个文件跑 `dart format`**

## Dev Notes

> 🔴 **开工前先对照前面 story 实际提交的代码核对本文件引用的名字，有出入先改本文件并在 Completion Notes 记一句。** 尤其核对 2.1 实际落的 JSON 键名是否为 `imageCropOffsets`、元素是否为数字或 null。

### 必读：会被本 story 改到的现有代码

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo_app/lib/features/content/domain/feed_image_layout.dart` | 纯 Dart 无 Flutter import；`ImageSize` L20–52；`kFeedRatioMin` L55 / `kFeedRatioMax` L67；`clampFeedRatio` L134–139；`resolveFeedImageAspect` L149–164（三段口径） | 加 `Alignment` 的 show import + 两个新函数 | 三段口径函数体一字不动；常量不动 |
| `.../content/domain/detail_image_layout.dart` | `resolveDetailImageAspect` L74–83（注释写「不要在这里加任何 if」） | **不改** | — |
| `petgo_app/lib/shared/widgets/feed_image.dart` | 构造器 L60–70（`declaredSize` 只是首图尺寸）；`_imageAt` L208–224 `BoxFit.cover`；`build` L227–328 按首图定 `AspectRatio` | 两个新入参 + 每张图的 `alignment` | 高度只按首图；角位结构（AD-8 Rule 6，`test/content/feed_carousel_test.dart` 的 AC4 组守）；滚动锚定通知 |
| `petgo_app/lib/shared/widgets/masonry_card.dart` | L236–253 构造 `FeedImage` | 加两实参 | 其余参数 |
| `.../content/domain/feed_item.dart` | `imageSizes` 字段 L168、`firstImageSize` L185、`copyWith` L190–208、`fromJson` L210–232 | 加 `imageCropOffsets` | `copyWith` 只开放三项计数的语义（新字段只透传） |
| `.../content/domain/content_detail.dart` | `imageSizes` L59、`sizeAt` L64–65、`fromJson` L85–108 | 加 `imageCropOffsets` + `cropOffsetAt` | — |
| `.../content/presentation/content_detail_page.dart` | `_ImageCarousel` 调用 L247–256；类 L539–565；`build` L596 起，`resolveDetailImageAspect` L600–604，`AppImage.widget(... fit: BoxFit.cover, thumbWidth: 1080)` L624–631；点图 `_openLightbox` → `ImageLightbox.open` | 加 `cropOffsets` 入参与 `alignment` | 灯箱入口、Hero tag、页码角标、装饰标签位置 |
| `petgo_app/lib/shared/widgets/app_image.dart` | `ossResized` L25–35（仅 `host.endsWith('aliyuncs.com')` 生效）；`widget` L43–89 无 alignment 参数 | 加可选 `alignment` | 默认行为不变；`provider` 不改 |
| `.../content/presentation/promo_pinned_card.dart` | L55–62 `FeedImage(urls: [promo.imageUrl], declaredSize: null, ...)` | **不改** | — |
| `lib/shared/media/image_lightbox.dart` | 灯箱（`open` / `openMemory`，`_thumbWidth = 1080` L626） | **零改动**（AC6） | 全部 |

### 可直接复用

| 要做的事 | 用这个 | 位置 |
|---|---|---|
| 判超界 | `kFeedRatioMin` / `kFeedRatioMax`（闭区间，端点算区间内） | `feed_image_layout.dart` L55 / L67 |
| 尺寸按下标安全取 | `ContentDetail.sizeAt` 写法 | `content_detail.dart` L64–65 |
| Feed 卡 widget 测试脚手架 | `_item(...)` + `_pumpAspect(...)`（`MasonryCard` + `find.descendant(of: find.byType(FeedImage), matching: find.byType(AspectRatio))`） | `test/content/feed_image_render_test.dart` L13–61 |
| 详情页 widget 测试脚手架 | `_detail(...)` + `_FakeDetailRepo` + `_pump(...)` | `test/content/content_detail_test.dart` L16 / L51 / L134 |
| OSS 缩图单测 | `group('AppImage.ossResized ...')`（已用生产域名） | `test/shared/app_image_test.dart` L6–58 |

### 关键设计点

- **只换对齐、不碰比例**（AD-6）：窗口比例仍 = `clampFeedRatio(原图比例)` 再受护栏约束；`BoxFit.cover` + `Alignment` 天然实现「窗口在被裁方向上的起点」，0 / 0.5 / 1 = 贴起始边 / 居中 / 贴结束边，不需要算像素矩形。
- **多图容器锁首图比例**：非首图的容器比例 ≠ 其自身 clamp 比例，但容器比例恒在 `[0.75, 1.78]`（或护栏抬高后更宽）——超宽图在其中仍是左右被裁、超竖图仍是上下被裁，所以 `feedImageAlignment` 按**该图自身**比例判方向是对的。极端小视口护栏把容器抬到比原图还宽时，宽图变成上下被裁，x 分量不起作用 = 居中，可接受。
- **`declaredSize` 为 null（存量 / 尺寸待兜底）时**：首图取 `_decoded` 量到的尺寸判方向；量到之前一律居中。新发布的帖客户端必上报尺寸（2.4 不改这一点），实际不会出现「有 offset 无尺寸」。
- **推广卡（`PromoPinnedCard`）不开窗**：epics 写「置顶卡（`promo_pinned_card`）同样按露出位置开窗」，但推广卡是运营素材（`PromoCard` 只有 `imageUrl / title / linkUrl` 三字段，`pinned_slot.dart` L58–83），没有、也不会有 offset；顶置的**已发布内容**走 `MasonryCard`，已在 AC3.3 覆盖。故推广卡保持居中、零改动（见「待确认」）。
- **为什么不写 golden**：cloud-rules 明令；结构断言（`Alignment` / `BoxFit` / 比例值）已能证明「无 offset 时渲染参数与改前完全相同」，像素级比对留 L2。

### 本 story 不做

- 不改灯箱、不改 `resolveFeedImageAspect` / `resolveDetailImageAspect`、不改容差常量与 `needsCrop()`；
- Feed 卡不新增全屏入口（FR-125.10）；
- 分享卡、成长档案时间线、个人主页网格等**其它**图片展示位不接 offset（PRD 只要求 Feed 卡与详情轮播）；
- 不改 `thumbWidth` 取档、不做按像素密度缩图（AD §13 第 1 条）。

### l10n 新 key

无。

### 必须更新 / 新增的测试

| 测试 | 层级 | 改什么 |
|---|---|---|
| `petgo_app/test/content/feed_image_layout_test.dart` | L0 | 新 group（T1） |
| `petgo_app/test/content/feed_image_render_test.dart` | L0 | 新 group `'V1.3.4 2.2 开窗'`：① 3:1 + `[0.0]` → `Image.alignment == Alignment(-1,0)`、`fit == BoxFit.cover`、比例 `kFeedRatioMax`；② 1:2 + `[1.0]` → `Alignment(0,1)`、比例 `kFeedRatioMin`；③ AC5.1：同一 3:1 帖 `imageCropOffsets` 为 `const []` 与 `const [null]` 各 pump 一次，`Image.alignment` 都是 `Alignment.center` 且两次 `AspectRatio` 相等；④ 区间内 4:3 帖带 `[0.0]` → 仍 `Alignment.center`。`Image` 用 `find.descendant(of: find.byType(FeedImage), matching: find.byType(Image))` 取 |
| `petgo_app/test/content/feed_carousel_test.dart` | L0 | 新增 1 例：两图帖 `sizes=[3:1, 1:2]`、`offsets=[0.0, 1.0]`，翻到第 2 页后第 2 张 `Image.alignment == Alignment(0,1)`，容器高度不变（沿用本文件 AC3 的高度断言写法） |
| `petgo_app/test/content/content_detail_test.dart` | L0 | 新增 2 例：① `_detail` 加 `imageSizes: [ImageSize(3000,1000)]`、`imageCropOffsets: [1.0]` → 轮播内 `Image.alignment == Alignment(1,0)`、`fit == BoxFit.cover`；② 不带 offsets → `Alignment.center`（AC5.1 详情侧） |
| `petgo_app/test/content/detail_image_layout_test.dart` | L0 | 新增 1 例（AC4.4）：同一 `(ImageSize(3000,1000), 0.25)`，Feed 侧与详情侧都调 `feedImageAlignment` 得 `Alignment(-0.5, 0)`；`resolveDetailImageAspect` 与 `resolveFeedImageAspect` 在同一护栏上限下结果相等（沿用本文件 L20 用例写法） |
| `petgo_app/test/shared/app_image_test.dart` | L0 | AC7.2 一例；另加一例 `AppImage.widget(url, alignment: Alignment(1,0))` 渲染出的 `Image.alignment == Alignment(1,0)`，不传时为 `Alignment.center` |
| 灯箱相关 `test/shared/image_lightbox_test.dart` / `lightbox_transition_test.dart` | L0 | **不改**，必须仍全绿（AC6） |

### 验证层级

- **L0（云端）**：AC1 / AC2 / AC3.1–3.4 / AC4.1–4.4 / AC5.1 / AC6 / AC7 —— `cd petgo_app && flutter analyze && flutter test`。
- **L2（本地模拟器，云端写好测试后不跑）**：AC3.5 / AC4.5 视觉、AC5.2 改动前后截图逐像素比对。需 stag 上有带 offset 的帖子（2.4 完成后用新包发；或直接在 stag 库 `UPDATE content_posts SET image_crop_offsets='[0.0]' WHERE id=<测试帖>`）。Completion Notes 记「L2 待本地验收」。

### Project Structure Notes

- 无新文件（除测试用例追加在既有测试文件里）。`feedImageAlignment` 与比例出口同住 `feed_image_layout.dart`——「Feed 图怎么摆」的口径只有一个家。

### 待确认

- epics 2.2 末句「置顶卡（`promo_pinned_card`）同样按露出位置开窗」与数据现状不符：推广卡无 offset 来源，本 story 只让「顶置的已发布内容」（`MasonryCard`）开窗，推广卡保持居中不改。若产品指的是推广卡也要支持露出位置，需另开需求（后台要能配 offset）。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-app-delta.md#AD-6, AD-7, §13]
- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-app.md#Story 2.2]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-app.md#3.2 FR-125 AC2–AC5]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-app.md#同日技术处置（2.2 不写 golden）]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-app.html#A6, A7, A8]
- [Source: _bmad-output/implementation-artifacts/v1.3.4/cloud-rules-app.md（视觉一致性不写 golden）]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (1M context)（claude-opus-5-5[1m]）

### Debug Log References

- `flutter analyze`：No issues found。
- `flutter test test/content test/shared`：612 通过 / 0 失败。
- `flutter test`（全量）：2725 通过 / 1 失败——唯一失败为已知基线红 `test/profile/milestone_badge_test.dart`「每个 .webp 文件名都是某个语义键或 locked」（等 Story 4.1 补 key，4.1 之前允许红）。
- 灯箱测试（`test/shared/image_lightbox_test.dart` / `lightbox_transition_test.dart`）全绿；`image_lightbox.dart`、`promo_pinned_card.dart`、`detail_image_layout.dart` 零 diff。
- 后端无改动，未跑后端 L0；无迁移。

### Completion Notes List

- 开工前核对 2.1 提交（0112176）：后端 JSON 键为 `imageCropOffsets`，元素为数字或 null，存量 / 无超界图整字段省略（NON_NULL）——与本 story 引用一致，story 文字无需改动。App 侧无镜像字段集测试 / mock 数据（`FeedResponseContractTest` 注释所指的 `FeedItem.fromJson` 已同步读取该键）。
- `feedImageAlignment` / `cropOffsetListFromJson` 落在 `feed_image_layout.dart`（加 `package:flutter/painting.dart show Alignment`）；三段口径函数与常量一字未动。
- `FeedImage` 新增 `sizes` / `cropOffsets`；`_sizeAt(0)` = `declaredSize ?? _decoded ?? sizes[0]`，其余按下标安全取；高度仍只按首图。`MasonryCard` 透传，顶置已发布内容同样开窗。
- 详情 `_ImageCarousel` 新增 `cropOffsets`，`AppImage.widget` 新增 `alignment`（默认 center，三种 `Image.*` 均转发）；顺手把注释「0.75~1.34」订正为「0.75~1.78」。
- **L1/L2 待本地验收**（本 story 无 L1 项）：
  - AC3.5：模拟器上 3:1 宽图 offset 0 / 1 卡片露出最左 / 最右；1:2 竖图 offset 0 / 1 露出最上 / 最下。
  - AC4.5：同一帖从 Feed 点进详情，露出段一致、无跳变。
  - AC5.2：同一存量帖改动前后各截一张模拟器图逐像素比对。
  - 数据准备：stag 上需有带 offset 的帖（2.4 完成后新包发，或 `UPDATE content_posts SET image_crop_offsets='[0.0]' WHERE id=<测试帖>`）。

- 复审（bmad-code-review 口径）无 HIGH/MED；已修：规则 5 补上界端点 1.78 用例。记录（已知边界，按 AC3.2 写死规则不改）：帖子有 offset 但无尺寸时（后端 `ImageSizeResolver` 长度不齐整列丢弃尺寸、offset 另走严格校验，理论可能），Feed 首图会退到 `_decoded` 判方向而详情只看 `sizes[0]`，从 Feed 进详情可能出现露出段跳变；正常新发布帖必带尺寸，不会出现。

#### 待确认

- 沿用 story 已列项：推广卡（`PromoPinnedCard`）无 offset 来源，保持居中零改动；只有顶置的已发布内容（`MasonryCard`）开窗。若产品要推广卡也支持露出位置，需另开需求（后台可配 offset）。

### File List

- petgo_app/lib/features/content/domain/feed_image_layout.dart
- petgo_app/lib/features/content/domain/feed_item.dart
- petgo_app/lib/features/content/domain/content_detail.dart
- petgo_app/lib/features/content/presentation/content_detail_page.dart
- petgo_app/lib/shared/widgets/feed_image.dart
- petgo_app/lib/shared/widgets/masonry_card.dart
- petgo_app/lib/shared/widgets/app_image.dart
- petgo_app/test/content/feed_image_layout_test.dart
- petgo_app/test/content/feed_image_render_test.dart
- petgo_app/test/content/feed_carousel_test.dart
- petgo_app/test/content/content_detail_test.dart
- petgo_app/test/content/detail_image_layout_test.dart
- petgo_app/test/shared/app_image_test.dart

## Change Log

- 2026-10-12：story 创建（ready-for-dev）。
- 2026-10-12：实现完成——Feed 卡与详情轮播按 `imageCropOffsets` 开窗（只换对齐、不碰比例），L0 绿（除已知基线红），Status → review。
