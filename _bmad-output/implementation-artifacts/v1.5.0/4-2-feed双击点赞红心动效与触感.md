# Story 4.2: Feed 双击点赞红心动效与触感（App）

Status: review

> 版本 v1.5.0 · 主题 likes（会话 ⑤）· 分支 `feat/1.5.0-likes`
> 口径优先级：`decision-log.md` > PRD B `Feed双击点赞/1-5-0Feed双击点赞prd.md`（FR-211）> `architecture-v1.5.0-likes-delta.md`（F-3 弹跳、F-6 ~ F-8）> UI 稿 `ui-1-5-0-feed-double-tap-like-integrated-v1.html`（**D1 时间轴与数值为准**、D3 定位、D4 大心、B5 减少动态效果）与 `拆页-App/D1-时间轴与数值.html`。
> 前置：Story 3.1（`bounceTick`）、4.1（`playBurst()` 钩子）。
> 范围层：只 App；另在 `_bmad-output/planning-artifacts/architecture.md` AD-8 Rule 6 处补一句（AC6）。
> 🔴 云端只跑 L0；视觉、帧率、震动（L2）待本地真机。不写 golden。

## Story

As a 双击点赞的用户,
I want 图片上弹出一颗漂亮的红心、手机轻震一下、下方爱心跟着跳,
so that 我确定这个赞点上了。

## Acceptance Criteria

**AC1 — 颜色常量** `[L0]`（F-6）
`lib/core/theme/colors.dart` 在 `likeHeart`（`:183`）旁新增：
```dart
static const Color likeHeartLight = Color(0xFFFF8FA0); // 双击大心渐变亮端（V1.5.0 FR-211）
static const Color likeHeartDeep = Color(0xFFD42A55);  // 双击大心渐变暗端
```
业务代码不得写死 hex。小圆点用现有 `AppColors.coral` / `gold` / `dashedViolet`。

**AC2 — 动效组件 `FeedLikeBurst`** `[L0]`（FR-211「动效」、F-8；数值照 UI 稿 D1）
新建 `lib/shared/widgets/feed_like_burst.dart`：一个 `AnimationController(duration: 670ms)` + `TweenSequence`，`IgnorePointer` 包裹，对外 `play()`。
- **大心** 75dp，中心在可见图片区 `(宽 × 0.6, 高 × 0.5)`：
  - 0–180ms：scale 0.3 → 1.12、opacity 0 → 1，曲线 `Cubic(0.23, 1, 0.32, 1)`；
  - 180–330ms：1.12 → 0.96 → 1.0，`Cubic(0.77, 0, 0.175, 1)`；
  - 330–430ms：停留；
  - 430–600ms：上移 12dp、scale → 0.92、淡出，`Cubic(0.23, 1, 0.32, 1)`。
- **小心** 34dp，中心在大心中心右 36、上 32，旋转 14°；晚 70ms 开始（`Interval(70/670, …)`），节奏同大心，退场上移 22dp；全程到 670ms。
- **5 颗圆点** 直径 6dp：80ms 起 450ms 内从大心中心散开到以下位移并淡出（UI 稿 `DOT_SPEC`，dx, dy 单位 dp）：`(-53, -12) coral`、`(-32, -53) gold`、`(54, 10) dashedViolet`、`(41, 43) coral`、`(-43, 37) gold`。画法参照 `lib/features/gift/presentation/gift_send_animation.dart` 的粒子 `CustomPainter`。
- **心形**：`CustomPainter` 画 UI 稿 `HEART_D`（viewBox 0 0 24 24，按 75 / 34dp 缩放）：
  `M12 20.6C6.6 16.9 3 13.6 3 9.4 3 6.5 5.2 4.4 7.9 4.4c1.7 0 3.2.8 4.1 2.2.9-1.4 2.4-2.2 4.1-2.2 2.7 0 4.9 2.1 4.9 5 0 4.2-3.6 7.5-9 11.2z`
  填充 `LinearGradient(likeHeartLight → likeHeart → likeHeartDeep)`；同色 1.4（viewBox 单位）描边、`StrokeJoin.round` 磨圆尖角；左上白色椭圆高光 65% 不透明 + 小白点 55%（位置照 `拆页-App/D4-大心动效.html`）；同色柔光阴影（offset 0 / 6、blur 14、32%）。
- **重播**：播放中再次 `play()` → `controller.forward(from: 0.27)`（「放大到 1.12」那一刻），屏上始终只有一组心（B-AC20）。
- **减少动态效果**：`MediaQuery.disableAnimationsOf(context)` 为 true → 原尺寸只淡入淡出约 400ms，无缩放、无上飘、无圆点（B-AC21）。
- **离开屏幕**：State `deactivate` / `dispose` 时 `controller.stop()` 并复位，不残留（B-AC22）。每张卡各自一个实例（B-AC23）。
- Flutter 原生动画，**不用 Rive**；绘制异常只影响这一层，不影响点赞。

**AC3 — 挂进 `FeedImage`** `[L0]`（F-7、B-AC18、AC19）
- `lib/shared/widgets/feed_image.dart` 新增可选参数（如 `final FeedLikeBurstController? likeBurst;`），在 `Stack`（`:290` 起）**最上层**（三个角位之后）放 `Positioned.fill(child: FeedLikeBurst(controller: likeBurst))`；`likeBurst == null` 时不加这一层。
- 定位按 `AspectRatio` 之后的可见图片区计算，横图（比例上限 1.78）/ 方图（含无尺寸存量图 1:1 占位后解码变高——心要随之重新定位）/ 竖图（下限 0.75）/ 被 `maxImageHeight` 截高的长图，心中心都在可见区横 60%、纵 50%。
- `FeedImage` 不感知点赞逻辑；4.1 的 `_FeedImageTapArea` 持有 controller，`playBurst()` = `controller.play()`。

**AC4 — 爱心按钮弹跳与震动** `[L0]`（FR-211「下方爱心按钮…」「触感」、F-3）
- `LikeButton` 监听 store 覆盖的 `bounceTick` 变化 → 心形图标 1.0 → 1.2 → 1.0（约 200ms，`ScaleTransition`，只缩放图标，不改热区与布局）。
- 点爱心**点赞**（未赞 → 已赞）时：store 同样 `bounceTick + 1` + `HapticFeedback.lightImpact()`；**取消**不弹不震；点爱心**不出**大红心。
- 双击点赞：震动在 4.1 已调（与红心同时）；按钮弹跳由 `bounceTick` 触发。
- 减少动态效果：按钮不弹跳，直接变红 +1；照常轻震。
- 已赞再双击、飞行中再双击、游客双击：不震、按钮不弹（Q-9）。
- 系统关了触感 → 不震（系统自动处理，无需代码）。

**AC5 — 结构断言（不写 golden）** `[L0]`（F-7）
`test/content/content_tag_test.dart` 的 AD-8 Rule 6 组（`:86` 附近）补用例，或新建 `test/shared/feed_like_burst_test.dart`：
1. 有 `likeBurst` 时，三个角位（底部居中圆点、右上顶置角标、左下装饰标签）的位置与无 `likeBurst` 时**完全相同**；
2. 动效层是 `IgnorePointer`，`play()` 期间点图片仍能触发单击 / 双击判定；
3. 670ms 后（`pump`）心与圆点不在树里 / opacity 0；
4. `disableAnimations: true` 下没有 `Transform.scale` / 圆点，只有透明度变化，400ms 结束；
5. 播放中再 `play()`：controller 值跳到 0.27，屏上只有一组心；
6. 卡片被移出树 → 无残留、无异常；
7. 心中心坐标 = 可见区 `(0.6w, 0.5h)`，四种比例各一例（B-AC19）；
8. 新增颜色常量存在且 `likeHeart == coral` 未变。

**AC6 — 架构文档补一句** `[L0]`（PRD B §6「架构规则」）
`_bmad-output/planning-artifacts/architecture.md` 找到 AD-8 Rule 6（三个角位）那段，后面补：
> 图片区除三个角位外，另有一个**临时中央动效层**（Feed 双击点赞红心，V1.5.0 FR-211）：`IgnorePointer`、播完即移除、不占角位、不改图片区结构；其他功能不得借用此层放常驻内容。

（PRD / UI 稿里写的「更新 `feed_overlay_test.dart`」：该文件不存在，按 AC5 执行即可，Completion Notes 记一句。）

**AC7 — 真机验收** `[L2]`（B-AC18 ~ AC24）
中低端 Android 一台 + iOS 一台：全程 ≤ 1 秒、不挡后续操作；同屏多卡快速各双击一次互不干扰、滚动不掉帧（B-AC23，DevTools 帧图截图附 Completion Notes）；点赞成功轻震一次、已赞再双击不震（B-AC24）；浅色 / 深色图片上心都清楚；还原度对 `拆页-App/A2 ~ A4、B1 ~ B5、D1 ~ D4` 逐屏打分 ≥ 95%。

## Tasks / Subtasks

- [x] T0 开工核对：3.1 / 4.1 已在本分支；读 `拆页-App/D1`、`D3`、`D4`
- [x] T1 颜色常量（AC1）
- [x] T2 `FeedLikeBurst` + 心形 / 圆点 painter（AC2）
- [x] T3 `FeedImage` 挂层、`_FeedImageTapArea` 接 controller（AC3）
- [x] T4 `LikeButton` 弹跳 + 爱心点赞震动（AC4）
- [x] T5 结构断言（AC5）+ 架构文档一句（AC6）+ L0；Completion Notes 标「L2 待本地」

## Dev Notes

### 现状
| 位置 | 现状 |
|---|---|
| `lib/core/theme/colors.dart:41/45/46/183` | `dashedViolet 0xFFC2B0EC`、`coral 0xFFF0425A`、`gold 0xFFF6A609`、`likeHeart = coral` |
| `lib/shared/widgets/feed_image.dart:284-357` | `AspectRatio` → `Stack(fit: expand)`：底层图 / `PageView` → 右缘暗渐变（`IgnorePointer`）→ 角位一圆点 → 角位二 `topRight` → 角位三 `bottomLeft` |
| `test/content/content_tag_test.dart:86` | 「三个角位分处三处、互不遮挡（AD-8 Rule 6）」 |
| `lib/features/gift/presentation/gift_send_animation.dart` | 送礼落点粒子 `CustomPainter`（画法参照） |
| 减少动态效果写法 | 升级庆祝、送礼动效都读 `MediaQuery.disableAnimations`（UI 稿已核） |

### 命令
- L0：`cd petgo_app && flutter analyze && flutter test`。不写 golden（云端 Linux 字体渲染与本机不同）。

## Dev Agent Record

### Completion Notes
- **L1/L2 待本地验收**：AC7 全部（中低端 Android + iOS：时长、多卡互不干扰、滚动帧率 / DevTools 帧图、轻震、浅 / 深色图上清晰度、对 `拆页-App/A2 ~ A4、B1 ~ B5、D1 ~ D4` 逐屏还原度 ≥ 95%）云端未做。按规定**不写 golden**，视觉只做结构断言。
- **L0**：`flutter test` 全量 4987 通过；`flutter analyze` 0 error / 0 warning，相对基线无新增非弃用类 info（SDK 3.50 与 lock 说明同 3.1）。
- T0 核对：3.1（f18ddd3b）/ 4.1（8bf1bc71）已在本分支；读了 `拆页-App/D4-大心动效.html`（渐变 stops 0 / .55 / 1、对角方向；高光椭圆 (7.4, 8.3) rx 1.9 ry 1.15 旋 -38°、65%；小白点 (9.6, 6.5) r .55、55%），数值照 story 内联的 D1。
- 实现：
  - `AppColors.likeHeartLight` / `likeHeartDeep`（AC1），业务代码无写死 hex。
  - `lib/shared/widgets/feed_like_burst.dart`：`FeedLikeBurstController`（`play` / `stop`）+ `FeedLikeBurst`（670ms，`TweenSequence` 按 ms 权重：0–180 / 180–330 / 330–430 / 430–600；小心 `Interval(70/670, 1)`；圆点 `Interval(80/670, 530/670)` 位置 easeOut、透明度线性）；心形 `CustomPainter` 用 HEART_D（相对坐标换算为绝对）、渐变铺在心形外框（同 SVG objectBoundingBox）、1.4 圆角描边、高光、同色柔光（0/6、blur 14、32%）。重播 `forward(from: 0.27)`；减少动态效果 400ms 只淡入淡出、无缩放 / 上飘 / 小心 / 圆点；`deactivate` 停住并清可见态（不在 deactivate 里 `reset()`——那会在 build 阶段触发重建，复审 / 测试发现后改正）。
  - `FeedImage` 新增可选 `likeBurst`，在三个角位之后 `Positioned.fill` 挂层（null 时不加）；`_FeedImageTapArea` 持有 controller，`_playBurst()` = `play()`；同位置 State 复用给另一条帖子时 `stop()`。
  - `LikeButton`：`ref.listen` 本帖 `bounceTick` → `ScaleTransition` 1.0→1.2→1.0（200ms，只缩放图标，热区不变）；点爱心**点赞**时 `HapticFeedback.lightImpact()` + `toggle(bounce: true)`；取消不弹不震；减少动态效果不弹、照常震。
- AC5 结构断言写在新建的 `test/shared/feed_like_burst_test.dart`（8 条全覆盖 + 播放中点击穿透 + `stop`），另含按钮弹跳 3 例。PRD / UI 稿里写的「更新 `feed_overlay_test.dart`」：**该文件不存在**，按 AC5 执行。
- **AC6 待确认**：story 要求在 `_bmad-output/planning-artifacts/architecture.md` 的「AD-8 Rule 6」处补一句，但该文件里**没有** AD-8 Rule 6 段落（AD-8 定义在 `v1.1.6/architecture-v1.1.6-delta.md`，且那里也没有「三个角位」的字面条文，「Rule 6 三个角位」只出现在代码注释与 v1.1.6 epics）。按最保守做法：在基线 `architecture.md` 新增小节「Feed 图片区叠加层（AD-8 Rule 6，V1.1.6 起；V1.5.0 增补）」，先复述三角位规则再写增补句；`feed_image.dart` 头注释同步补一句。是否挪到别处由主会话定。
- **待确认**：弹跳按帖子计（`bounceTick` 是 store 里按 postId 的计数）——同一帖同时挂在顶置卡与普通卡、或详情底栏盖在 Feed 卡之上时，所有该帖的爱心按钮都会弹一次（被盖住的按钮会在返回后已播完，无残留）。若要只弹被操作的那张卡，需要把弹跳信号改成按「位置」而不是按帖子，属 F-5 设计变更。
- **待确认**（性能，L2 复测）：播放期间每帧重建 Stack / Opacity / Transform，心形柔光用 `MaskFilter.blur`；曲线已改为静态共享。若真机掉帧可再改为 `FadeTransition` / 预渲染阴影。

### File List
- `petgo_app/lib/core/theme/colors.dart`
- `petgo_app/lib/shared/widgets/feed_like_burst.dart`（新）
- `petgo_app/lib/shared/widgets/feed_image.dart`
- `petgo_app/lib/shared/widgets/masonry_card.dart`
- `petgo_app/lib/features/content/presentation/like_button.dart`
- `petgo_app/test/shared/feed_like_burst_test.dart`（新）
- `_bmad-output/planning-artifacts/architecture.md`（AD-8 Rule 6 增补小节）
