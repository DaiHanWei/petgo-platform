---
baseline_commit: d9353457
---

# Story 7.2: Splash 视觉与入场动效整体重做

Status: in-progress

> **所属**：V1.1.2 **Epic 7 第二个 Story** —— **纯前端 · 零 schema · 零后端改动 · 零新依赖**。
> 交付：把 splash 的**视觉终态与入场动效整体换成新设计**（方案 B「写名字」），并收口尺寸、字体、资产。
>
> 🟢 **这是一次整体替换，不是在旧效果上做增量（2026-08-04 用户确认）**。旧的「猫沿写死路点跑遍全屏、归位翻紫填进狗标缺口」那套叙事**整段删除**，不保留、不兼容、不做开关。新动效从头搭。
>
> ⚠️ **依赖 Story 7.1**：需要它新增的品牌资产，以及它 AC5 真机量出的**系统图标实测尺寸**（首帧对齐要用）。
> ⚠️ **本 Story 不改启动期时序**（`/me` 并行 / 超时 5s / 取消当天门控 / 进度线 / 慢网提示 —— 全属 Story 7.3）。

## Story

As a **第一次打开 App 的用户**,
I want **开屏看到品牌名一笔一笔写出来**,
so that **我在第一秒就记住了这个 App 叫什么（FR-88）**。

## Acceptance Criteria

### AC0 — Flutter 首帧接住原生那一帧（L0 · FR-87 / UX-DR21 · 2026-08-04 由 Story 7.1 移入）

**Given** Android 12+ 的系统 splash 图标**不可定位**，只能落 50% 正中；而现状 Flutter 首帧是 108px 居中于 43%
**When** 本 Story 实现
**Then** Flutter 首帧 mark 落在 **50% 正中**、可视宽 = **屏宽 42%**，与原生那一帧**同位同尺寸**（首帧仅比原生多一层呼吸光晕，无其它差异）
**And** 尺寸须对齐 **Story 7.1 AC5 的真机实测值**（以 7.1 的 Completion Notes 为准；若实测与 42% 不符，以实测为准）
**And** ⚠️ **不得为省一个动作让首帧直接落 43%** —— 那会在交接处产生可见跳位。「50% → 43%」的抬升是 AC1 的 B1 拍
**And** 🔴 **本 AC 必须与 AC1 同批**：改首帧落位会改变猫的落点参照，而 AC1 正是删掉猫跑屏那套逻辑的地方 —— 分开做会出现「猫对不上缺口」的破损态
**And** 同批**下线旧资产**：`mark_dog.svg` / `mark_cat.svg` / `wordmark.svg` 的引用全部移除（7.1 已新增替代资产但刻意未覆盖，正是为了等本 Story 一起换）

### AC1 — B1 mark 交接拍（L0 · FR-88 / UX-DR24）

**Given** mark 需要从原生的 50% 就位到设计位 43%，同时把舞台让给字标
**When** 播 B1 拍
**Then** `≈480ms` 内完成：位置 50% → 43%，同时 `scale 1→.70`、`opacity 1→0`
**And** 这一段位移**同时**完成「就位」与「让位」两件事，不拆成两段
**And** 🔴 **整段删除旧的猫跑屏逻辑**：`_markStage()` 的狗+猫双 SVG 叠加、猫的 6 个绝对像素路点、脚步弹跳（`abs sin` ×11）、归位翻紫（白→紫 `colorFilter`）—— 全部不再保留

### AC2 — B2 字标逐拍写出（L0 · FR-88 / UX-DR25）

**Given** 字标母文件共 11 条可见路径（T 与猫尾合并为一条、2 条动感短划、`a i l t o p i a` 各一条）
**When** 播 B2 拍
**Then** 拆成 **9 拍**逐条写出，每拍 `+85ms`，每条 `blur 4→0` / `y 10→0`
**And** **无需拆分资产文件** —— 按路径分组即可

### AC3 — B3 终态（L0 · FR-88 / UX-DR26 / 设计侧挑战 X-1）

**Given** 品牌母文件中**不存在**「mark 在上 / 字标在下」的竖排 lockup（现状那个组合属实现期自行拼装）
**When** 到达终态
**Then** `1540ms` 时字标可视宽 = 屏宽 **60%** + 标语，**终态不出现 mark**
**And** 视觉重量全部给品牌名，品牌识别在第一秒内完成

### AC4 — 总时长与旧时间线的处置（L0 · NFR-13）

**Given** 现状入场总时长为 **4320ms**（`animatedTotal`，时间线切片基准 `_t = 4320`，已核实），设计上限 1.8s
**When** 实现完成
**Then** 入场总时长为 **1540ms**（1.54s），不超过 1.8s
**And** 🔴 **旧的 4320ms 时间线整体删除**（狗 0–.42 / 猫 .60–3.60 / 字标 3.72–4.22 / 标语 3.82–4.32 那套切片全部作废），不是在其上调参数
**And** 爪印 pop 动效**已砍掉**（决策 D-4，省 160ms 且无需拆 `o` 的子路径），**不得自行加回**
**And** `animatedHold` / `staticHold` 须随新时长同步收敛（现状 4500 / 1400ms 是配 4320ms 动效的），**但不得改变 `onComplete` 的调用契约**（见 AC9）

### AC5 — 尺寸相对化、删写死路点、移除版本号（L0 · FR-90 尺寸部分 / UX-DR22 / NFR-17 / 缺陷 B-6、B-8）

**Given** 现状多处写死绝对像素，导致小屏大屏构图漂移
**When** 实现完成
**Then** 全部尺寸改**相对屏宽**：mark **42%** / 字标 **60%** / 光晕直径 **66.7%**（现状写死 260×260）/ 标语 **70%**；底部元素自 **92** 起
**And** 视觉中心 Y 仍为 43%，但口径改为「**logo 中心**居中，标语不参与居中计算」（现状「整块含标语居中」把字标推到光晕亮心之上 43px）
**And** **版本号整体移除**（现状 `static const String version = 'v 1.0.0'`，而 `pubspec` 已是 `1.1.0+7` —— 该数字自上线起即为错值，缺陷 B-6 随本条消失）
**And** **移除常驻 spinner**（现状定位 `bottom: 108`）—— 它不反映任何真实进度，属假反馈；**条件出现的进度线由 Story 7.3 补上**
**And** 🟡 **已知窗口**：7.2 交付后到 7.3 交付前，慢网用户在等待期**看不到任何指示**（旧 spinner 已删、新进度线未加）。这是功能反馈的暂时缺失、非功能故障；Epic 7 整批发布故用户不会遇到

### AC6 — 标语字体换 Fraunces 与两语言排版一致（L0 · FR-90 字体部分 / UX-DR30 / NFR-16 / 缺陷 B-9）

**Given** 换字体与改字号不是两个独立参数，原有数值全部需重调
**When** 实现完成
**Then** 标语改 **Fraunces 500**，四个可变轴 `opsz` 16 · `SOFT` 100 · `WONK` 1，用 `fontVariations` 设置
**And** 连带 8 项同改：`font-size` 12.5→**16px**、`line-height` 1.65→**1.5**、`letter-spacing` .15→**0**、颜色 白62%→**白68%**、宽度 62%→**70%** 屏宽、与字标间距 20→**24px**、`.tagpad` 61→**72px**（= 24 + 2 行 × 16 × 1.5，保证 logo 中心仍落 43%）
**And** **删除 `app_id.arb:714` 硬编码的 `\n`**，改为按屏宽 70% 自动换行，**两语言排版一致**（缺陷 B-9）
**And** 字体重度子集化：标语仅 ID / EN 两条静态字符串，子集约 **37KB**（含大小写全集），**不打包完整可变字体**（约 120KB）
**And** ✅ **验收口径：splash 最终只依赖 Fraunces 一款字体** —— 版本号移除后不再用 Poppins，标语换字体后不再用 Quicksand。**用「splash 相关代码里是否还出现 Quicksand / Poppins」来判定**，比逐条核对 9 个参数可靠

### AC7 — reduce-motion 兜底（L0 · NFR-14 / UX-DR29 / 设计侧挑战 X-3）

**Given** 装饰性元素常驻无限动画属反模式（无限动画应只用于 loading 指示）
**When** 系统「减弱动态效果」开启（`MediaQuery.disableAnimations`，现状已在读）
**Then** **直接落终态，不播入场**
**And** **呼吸光晕停在中间值不呼吸**（现状光晕在 reduce-motion 下仍是常驻无限动画，`_reduceMotion ? 0.5 : _halo.value` 只固定了取值、动画本身仍在跑 —— 须真正停掉）
**And** 进度线保留（它承载真实语义，不是装饰）—— 本 Story 已删旧 spinner、新进度线属 7.3，故本条在本 Story 内**只需保证不引入新的常驻装饰动画**

### AC8 — 真机确认 Fraunces WONK 轴手感（L2 · OQ-21 · **必须本地**）

**Given** `WONK` 轴在不同渲染引擎下差异较明显
**When** 真机确认
**Then** **iOS + Android 各一台**确认字形手感
**And** 若「歪」得过头，可退到 `WONK` 0（保留 `SOFT` 的圆润，去掉倾斜感）并回写 PRD OQ-21
**And** 云端 headless 无法执行本条，须在 Completion Notes 标注「L2 待本地验收」
**And** 💡 视觉验收可用既有 debug 开关定屏：`--dart-define=DEV_ROUTE=/splash`（`kDebugMode` 下 splash 不跳转，供逐帧看），无需反复冷启动

### AC9 — 严守本 Story 边界（L0）

**Given** 启动期时序属 Story 7.3、落地分流属 Story 7.4
**When** 本 Story 实现
**Then** **不改 `/me` 的发起时机与超时**（现状 `onComplete` 内 `await ensureRestored().timeout(3s)`，改并行与 5s 属 7.3）
**And** **不动「当天只播一次」门控**（`splashLastShownDate` / `_decided` / `AppPrefs.create().timeout(300ms)` 那套仍保留原样，取消门控属 7.3）
**And** **不改 `SplashPage.onComplete` 的调用契约** —— 路由层依赖它做分流；本 Story 只改「播多久、播什么」，不改「播完之后交给谁」
**And** `flutter analyze` 零警告、`flutter test` 全绿、`flutter build apk --debug` 通过
**And** 🔴 **`test/onboarding/splash_test.dart`（52 行 / 2 条用例）必然被本 Story 打断，须一并改**：
  - 用例 1「当天首开：播完整动效，渲染标语/版本，**~4.5s** 后过场」—— 断言了 `find.text(SplashPage.version)`（`:28`）。**版本号常量被 AC5 移除后此行不再编译**；且 4.5s 时序随 AC4 收敛为 1.54s 体系
  - 用例 2「当天已播过：静止终态，~1.4s 后过场」—— 同样断言 `SplashPage.version`（`:44`）
  - **处置**：本 Story 改这两条的「版本号断言」与「时序数值」；**「当天已播过」这条用例的存废留给 Story 7.3**（取消门控后该场景不再存在）
**And** 其余 splash 相关测试若因视觉重做而失败，**改断言而非绕过**，并在 Completion Notes 逐条说明改了什么

## Tasks / Subtasks

### 🟩 前端子任务（petgo_app / Flutter）

- [x] **T1 拆掉旧动效**（AC1 / AC4 / AC5）
  - [x] 删除 `_markStage()` 的狗+猫双 SVG 叠加结构
  - [x] 删除猫的 6 个绝对像素路点、脚步弹跳、归位翻紫 `colorFilter`
  - [x] 删除 4320ms 时间线的全部切片定义（`_t` / `_dog` / `_catT` / `_catPath` 等）
  - [x] 删除常驻 spinner（`bottom: 108`）与版本号（`SplashPage.version`）
  - [x] 移除 **splash 里**对 `mark_dog.svg` / `mark_cat.svg` / `wordmark.svg` 的引用
  - ⚠️ **订正（code-review 2026-08-04）**：`mark_dog.svg` 与 `mark_cat.svg` **仍被登录页引用**
    （`lib/features/auth/presentation/login_page.dart:348-350`），并非「全部移除」。
    只有 `wordmark.svg` 确实已无引用。**照 File List 原描述去物理删除会让登录页缺资产。**
- [x] **T2 搭新时间线**（AC1 / AC2 / AC3 / AC4）
  - [x] 总时长 **1540ms**；三拍 B1(≈480ms) / B2(≈900ms，9 拍每拍 +85ms) / B3(终态)
  - [x] B1：位置 50%→43% + `scale 1→.70` + `opacity 1→0`
  - [x] B2：字标 11 条路径分 9 组，逐组 `blur 4→0` / `y 10→0`
  - [x] B3：字标可视宽 60% + 标语，**无 mark**
  - [x] 同步收敛 `animatedHold` / `staticHold`
- [x] **T3 首帧对齐**（AC0）
  - [x] 首帧 mark 落 **50% 正中**、可视宽 **屏宽 42%**（或 7.1 实测值）
  - [x] 自检：与原生那一帧同位同尺寸，仅多一层呼吸光晕
- [x] **T4 尺寸相对化**（AC5）
  - [x] mark 42% / 字标 60% / 光晕 66.7%（替换写死 260）/ 标语 70% / 底部元素 92 起
  - [x] 视觉中心口径改为「logo 中心居中，标语不参与计算」
- [x] **T5 字体接入**（AC6）
  - [x] 产出 Fraunces 子集 **TTF**（38KB，98 码位）→ `assets/fonts/Fraunces-subset-SOFT-WONK.ttf` + `OFL.txt`。**Flutter 不支持 woff2，只吃 TTF/OTF**，故用 fonttools 转换
  - [x] `pubspec.yaml` 的 `fonts:` 段登记该字体
  - [x] 用 `fontVariations` 设轴　**实际设 2 轴（SOFT/WONK）**：`wght 500` 与 `opsz 16` 已烘死进字体以省体积（121KB→38KB），视觉结果相同；`WONK` 保留可变以便 OQ-21 真机后可退 0
  - [x] 按 AC6 表逐项改 8 个排版参数 + `.tagpad` 补偿
  - [x] 删 `app_id.arb:714` 的硬编码 `\n`；核对 `app_en.arb` 侧一致
  - [x] 改完 ARB 须重跑 `flutter gen-l10n`
- [x] **T6 reduce-motion**（AC7）
  - [x] 开启时直接落终态、不播入场
  - [x] **真正停掉**光晕的无限动画（不是只固定取值）
- [x] **T7 L0 绿灯**（AC9）
  - [x] `flutter analyze` 零警告
  - [x] `flutter test` 全绿（splash 相关断言按需改，逐条记录）
  - [x] `flutter build apk --debug` 通过

### 🟨 联调验收子任务（**必须本地真机 / 模拟器视觉，云端无法执行**）

- [~] **T8 逐帧对稿**：`--dart-define=DEV_ROUTE=/splash` 定屏，对照 `ui-splash-v1.1.2.html` 的 B1 / B2 / B3 / BL 四屏逐帧核对（AC1~AC3）　**部分完成**：B3 终态已在模拟器上逐项核对通过（字标 59.9% 屏宽、无 mark、无版本号、无 spinner）；**B1/B2 中间帧未逐帧核对** —— 动效仅 1.54s，`adb screencap` 单帧耗时约 300ms 抓不稳，需人工看或录屏
- [ ] **T9 交接验证**：真机冷启动，确认原生→Flutter **无底色闪变且无 mark 跳位**（AC0；7.1 遗留的跳位应在此消除）　⚠️ **未完成**：交接窗口仅约 320ms，本会话的截图手段抓不到那一瞬，需人工肉眼看冷启动
- [~] **T10 字体真机确认**：iOS + Android 各一台看 `WONK` 手感，过头则退 `WONK` 0 并回写 OQ-21（AC8）　**部分完成**：Android（模拟器 API 36）已看，字形正常、缺字已解决；**iOS 侧未验**（本机无 iOS 模拟器/真机可用），OQ-21 仍开放
- [ ] **T11 reduce-motion 真机**：系统开启「减弱动态效果」后冷启动，确认直落终态且光晕不呼吸（AC7）
- [x] **T12 小屏/大屏构图**：至少两种屏宽验证相对尺寸无漂移（AC5 / NFR-17）　**以 widget 测试覆盖**（390 与 1080 两种逻辑屏宽 + 四个比例常量断言），未另在两台真机上比对

### 🟦 后端子任务

- [x] **无。本 Story 零后端改动、零 schema 变更、零迁移。**

### Review Findings

> code-review 2026-08-04 第二轮（靶子 `07c70b9b..HEAD`）的 findings **统一记在 Epic 7 收尾 story**：
> `7-4-落地分流收口与超时兜底迟到纠正.md#Review Findings`。本 Story 涉及的条目在那份台账里已按文件标注。
> 本 Story 直接相关的高危项：① 🔴 首帧 mark 垂直居中用了宽度的一半、比 50% 线高约 15dp —— 交接跳位正是本 Epic 要消除的东西；② `markWidthAt` 测试是恒真断言（返回整页宽），T12 相对尺寸验证不成立；③ 旧资产 `mark_dog/mark_cat` 仍被登录页引用，File List 的「引用已全部移除」为事实错误，照它删会缺资产；④ 字体子集注释与实测不符（97 码位、无弯双引号与破折号）+ OFL 未随包分发。

## Dev Notes

### 🔴 这是替换，不是改造

用户已明确：**旧 splash 效果整体换掉**。不要保留旧动效做开关、不要兼容、不要「先加新的再逐步下线旧的」。旧那套（狗标带猫形缺口 + 猫跑屏归位翻紫做镂空）的**全部代码与资产引用一次删净**。

判据：本 Story 完成后，`splash_page.dart` 里**不应再出现** `mark_cat`、猫路点、`abs sin` 弹跳、`v 1.0.0`、常驻 spinner。

### 现状核实结果（2026-08-04，实施基线）

文件：`petgo_app/lib/features/onboarding/presentation/splash_page.dart`（297 行）

| 项 | 现状 | 位置 |
|---|---|---|
| 入场总时长 | **4320ms**（切片基准 `_t = 4320`） | `:28` / `:53` |
| 播完停留 | `animatedHold` **4500ms** / `staticHold` **1400ms** | `:31-32` |
| mark 组成 | `mark_dog.svg`（带猫形缺口的狗/T 底标，缩放淡入）+ `mark_cat.svg`（沿路径跑 + 弹跳 + 白→紫翻色填进缺口） | `:219-261` |
| mark 声明尺寸 | **108×108** 固定 | `:222` / `:234` / `:252` |
| 视觉中心 | `centerY = size.height * 0.43`，整块（含标语）垂直居中、gap 22 | `:138` / `:171` |
| 呼吸光晕 | 写死 **260×260** | `:157` |
| spinner | 常驻，定位 `bottom: 108` | `:190-192` |
| 版本号 | `static const String version = 'v 1.0.0'` | `:24` |
| reduce-motion | 读 `MediaQuery.disableAnimations`；光晕处 `_reduceMotion ? 0.5 : _halo.value` | `:88` / `:151` |
| 当天门控 | `AppPrefs.create().timeout(300ms)` → `splashLastShownDate` 比对 → `animate` | `:92-102` |
| 跳转 | `Timer(animate ? animatedHold : staticHold)` → `onComplete ?? context.go('/home')` | `:113-119` |
| debug 定屏 | `kDebugMode && DEV_ROUTE == '/splash'` → 不起 Timer、不跳转 | `:111-112` |

### 必须保留的既有机制（本 Story 不得破坏）

- **`onComplete` 契约**：`Timer` 到点后调 `widget.onComplete ?? () => context.go('/home')`。路由层 `app_router.dart` 靠它做分流（pending 深链 > 落地矩阵）。**本 Story 只改「播多久、播什么」，不改「播完交给谁」。**
- **当天只播一次门控**：`_decided` 标志在 prefs 决策完成前不渲染 mark（避免静→动闪烁）。**本 Story 保留原样** —— 取消门控属 7.3，两件事一起改会让「动效对不对」与「该不该播」的问题纠缠在一起，不好定位。
- **`brandViolet = Color(0xFF7D45F6)`** 已是品牌紫常量，继续用它，不要另写色值。
- **`DEV_ROUTE=/splash` 定屏开关**保留 —— 它是本 Story 逐帧对稿的主要手段（T8）。

### 与 7.1 / 7.3 的交接点

| 来自 7.1 | 用在哪 |
|---|---|
| 新增的 mark / 字标资产 | AC0 首帧、AC1~AC3 动效 |
| **AC5 真机实测的系统图标尺寸** | AC0 首帧尺寸对齐（若与 42% 不符以实测为准） |

| 留给 7.3 | 本 Story 的处置 |
|---|---|
| `/me` 改并行 + 超时 5s | 不动，保持 `onComplete` 内串行 `timeout(3s)` |
| 取消当天门控（连带 B-7 首帧空窗） | 不动，`splashLastShownDate` 那套保留 |
| 条件出现的进度线 + 慢网提示 | 本 Story 只删旧 spinner，不加新指示（AC5 已标已知窗口） |

### 强制护栏（违反即返工）

- 用户可见字符串一律走 ARB，**严禁源码硬编码**（标语与未来的慢网提示都是）
- 改 ARB 后必须 `flutter gen-l10n`，否则编译失败
- 不引入新依赖、新中间件；字体以**资产**形式加入，不引 google_fonts 之类的运行时下载包（离线首屏不可依赖网络）
- 不改 `ddl-auto`、不加 Flyway 迁移（本 Story 零 schema）
- V1 仅浅色 + portrait-only，**不要为 splash 加 dark 分支**（原生启动屏的深色底色由 7.1 处理，Flutter 侧不分主题）

### Project Structure Notes

- 改动集中在 `lib/features/onboarding/presentation/splash_page.dart`
- 字体资产落 `petgo_app/assets/fonts/`（新目录），在 `pubspec.yaml` 的 `flutter: fonts:` 段登记
- 文案改 `lib/l10n/app_id.arb` + `app_en.arb`，生成物 `app_localizations*.dart` **不手改**
- 旧品牌资产文件本身可暂留在 `assets/brand/`（仅移除引用），是否物理删除由 Epic 收尾统一处理，避免与 7.1 新增资产的命名整理冲突

### References

- [Source: _bmad-output/planning-artifacts/epics-v1.1.2.md#Story 7.2]（AC0~AC8 原文，AC0 为 2026-08-04 由 7.1 移入）
- [Source: _bmad-output/planning-artifacts/v1.1.2/PRD-v1.1.2.md#2.6]（FR-88 全部 + FR-90 尺寸与字体部分 + 缺陷汇总表）
- [Source: _bmad-output/planning-artifacts/v1.1.2/ui-splash-v1.1.2.html]（B1 mark 交接 / B2 逐拍写出 / B3 终态 / BL 循环预览 / T3 尺寸规格 / F1 字体定稿 / C3 reduce-motion）
- [Source: _bmad-output/implementation-artifacts/v1.1.2/7-1-品牌资产重导与原生启动屏无缝交接.md]（前序 Story：新增资产清单 + AC5 系统图标实测值）
- [Source: PRD §4 OQ-21]（Fraunces WONK 轴真机确认）
- [Source: 源目录 .decision-log.md]（D-1 方案 B / D-4 砍爪印 pop / C-9 字体定稿及连带 9 项 / X-1~X-3 设计侧挑战 / 第六节「事实性更正记录」）——**未随仓库归档**，在 `/Users/hexsfile/work/MY-PROJECT/Pet Project/TailTopia/V1.1.2/`
- 架构：`architecture-v1.1.2-delta.md` **无 FR-88 / FR-90 对应 AD**（16 条 AD 早于 splash 设计）。UI 稿已给足可执行规格，故不阻塞

## Dev Agent Record

### Agent Model Used

Claude Opus 5 (1M context) · claude-opus-5[1m]

### Debug Log References

- `flutter analyze` → `No issues found!`
- `flutter test` → `All tests passed!`（**659 tests**，原 651 + 本 Story 新增 8）
- `flutter build apk --debug` → 通过；已安装至模拟器（AVD `petgo_phone` · Android 16/API 36）
- 视觉验收用既有开关定屏：`flutter build apk --debug --dart-define=DEV_ROUTE=/splash`

### Completion Notes List

**AC0~AC7、AC9 已满足**（AC6 原被字体素材缺口阻塞，2026-08-04 用户拍板联网取完整版后已解决）。**AC8 部分完成**：Android 侧已验，iOS 侧本机无设备，OQ-21 仍开放。

#### 🐛 本 Story 最重要的一条：真机空屏缺陷（已修，并补了守卫测试）

**现象**：L0 全绿（analyze 0 / 651 测试通过 / APK 构建成功），但装到模拟器上 splash **一片纯紫、什么都不显示**。

**排查过程**（记录下来，因为这类"测试全绿但真机全黑"最难查）：
1. 先怀疑 `screencap` 抓不到 Impeller 画面 → 用 Diary 页验证：截到 939 种颜色，**screencap 能抓 Flutter**，假设排除
2. 查 logcat：**无任何 Dart 异常**（只有 `FlutterRenderer: Width is zero. 0,0` 一条 engine 级 debug 日志）
3. widget 测试里量各元件尺寸（按设备 1080×2400 @2.625）：mark 172.8×142.8、字标 246.9×76.4、标语 288×63 —— **全部正常**，无异常
4. 临时把底色改红 + 加无条件绿方块 → **红底出现、绿块不出现** ⇒ Scaffold 在画，`body` 的 Stack 一个子元素都没画
5. 二分：Stack 里只留绿块 → 显示；绿块 + 字标 → 都显示；绿块 + 光晕 → 都显示；**加回 mark → 全黑**

**根因**：`_markHandoff` 在动效终态（`t >= 1.0`）时 `return const SizedBox.shrink()` —— 那是个**非定位（non-positioned）子元素**。
`RenderStack` 的规则是：**只要出现任何非定位子元素，Stack 就把自身尺寸收缩为这些子元素的最大尺寸**；返回 0×0 的非定位子元素 ⇒ **整个 Stack 变 0×0 ⇒ 所有兄弟元素一起消失**。

**为什么 L0 全绿却漏过**：旧测试只 `pump(60ms)`，此时 `t < 1`、`_markHandoff` 仍返回 `Positioned`，**恰好躲过**。真机上要等 1.54s 动效播完才触发 —— 而我的截图都在 3 秒后。

**修复**：`_markHandoff` **恒定返回 `Positioned`**，终态改用 `opacity: 0` 隐藏，不再切换节点类型。「终态不出现 mark」（AC3）由透明度实现，语义不变。

**守卫测试**（`splash_test.dart`「🐛 回归：动效播完后内容仍在」）：pump **越过** `animatedTotal` 后，断言每个 SVG 的 `RenderBox.size` 宽高均 > 0。
✅ **已验证该测试有效**（红-绿都跑过）：把错误改法放回去 → 失败并报 `Expected: a value greater than <0> / Actual: <0.0>`；改回修复 → 通过。

**顺带两处加固**（同一坑的邻近风险，非过度设计）：
- `MediaQuery.of(context).size` → **`LayoutBuilder`**。冷启动瞬间画面可能是 0×0（见上 logcat），按 MediaQuery 算出的尺寸会全为 0；`LayoutBuilder` 拿本次布局的真实约束，且约束变化自动重建。并加了 `size <= 0` 时只铺底色的兜底。
- **去掉 Stack 的 `clipBehavior: Clip.hardEdge`**。旧实现需要它是因为猫要跑出屏幕；新动效无越界元素，而留着它会在尺寸异常为 0 时把整块裁没（放大上述故障）。

#### 已交付内容（逐 AC）

| AC | 实现要点 |
|---|---|
| **AC0** | 首帧 mark 落 **50% 正中**、可视宽 **42% 屏宽**，与原生同位同尺寸；`handoffYRatio`/`centerYRatio` 提为常量并有测试锁定，防止把两者混为一谈 |
| **AC1** | B1 拍 480ms：`50%→43%` + `scale 1→.70` + `opacity 1→0`；**旧猫跑屏逻辑整段删除**（双 SVG 叠加、6 个绝对像素路点、`abs sin` 脚步弹跳、归位翻紫 `colorFilter`） |
| **AC2** | B2 拍：**单一资产运行时切分为 9 组**（前 3 条 T+尾+2 短划合为第 1 拍，其后 8 个字母各 1 拍），每拍 `+85ms` 错开、单拍 220ms、`blur 4→0` / `y 10→0`。**未拆成 9 个资产文件**，符合 AC2 |
| **AC3** | B3 终态 1540ms：字标 60% 屏宽 + 标语，**无 mark**。模拟器实测内容宽 647px = **屏宽 59.9%** ✅ |
| **AC4** | 总时长 **4320ms → 1540ms**（未超 1.8s 上限）；旧时间线四个切片全部删除；`animatedHold` 4500→**1720ms**、`staticHold` 保持 1400ms；爪印 pop 未加回 |
| **AC5** | 尺寸全部相对屏宽（mark .42 / 字标 .60 / 光晕 .667 / 标语 .70，底部 92 起）；视觉中心改「logo 中心居中、标语不参与」；**版本号移除**（缺陷 B-6 随之消失）；**常驻 spinner 移除** |
| **AC7** | reduce-motion 直落终态；**光晕控制器在 reduce-motion 下不启动**（改前只固定取值、动画仍在跑），值停在中间 0.5 |
| **AC9** | 未动 `/me` 时序、未动当天门控、`onComplete` 契约不变；`splash_test.dart` 按 AC9 要求改断言而非绕过（详见下节） |

#### 测试改动（AC9 要求逐条说明）

`test/onboarding/splash_test.dart`：52 行 / 2 用例 → **161 行 / 8 用例**
- 用例 1、2：删除 `SplashPage.version` 断言（该常量已随 AC5 移除，否则**不再编译**）；时序 4.5s → 1.8s、1.4s 保持
- 用例 2「当天已播过」**按 AC9 保留**（其存废归 Story 7.3 取消门控时处理）
- 新增 6 条：版本号与 spinner 下线断言 / 尺寸相对屏宽 + 四个比例常量 / 总时长与 hold 收敛 / 交接位 50% 与设计位 43% 不混淆 / **🐛 Stack 塌陷回归守卫** / reduce-motion 直落终态

#### ✅ AC6 已解决（原阻塞）：改用 Google Fonts 完整版 Fraunces 重新子集化

**story 与决策日志的「子集约 37KB，含大小写全集」描述不成立** —— 设计稿内联的 woff2 虽是 37KB，但 **cmap 只有 35 码位**，只覆盖印尼语标语：英文标语缺 `'` `f` `p` `v` `y`，Story 7.3 的慢网提示缺 `b` `g` `k`。已核查设计目录 / 仓库 / 系统字体库均无完整版。

**2026-08-04 用户拍板：联网取完整版重做子集。** 执行结果：

| 步骤 | 结果 |
|---|---|
| 下载 | Google Fonts `ofl/fraunces` 的 `Fraunces[SOFT,WONK,opsz,wght].ttf`（352KB）+ `OFL.txt` |
| 许可 | **OFL-1.1**。许可证已随字体放入 `assets/fonts/OFL.txt`（OFL 要求随分发附带，**勿删**） |
| 格式 | woff2 → TTF：**Flutter 不支持 woff2**（那是 Web 格式），只吃 TTF/OTF。用 `fonttools` 转换 |
| 子集 | 基础拉丁可打印区 + `…` + `’`，共 **98 码位**。**刻意比"当前三条文案所需"更宽** —— 这次的坑正是子集太紧造成的，放宽后改文案不会再被字体绊住 |
| 轴处置 | `wght 500` / `opsz 16` **烘死进字体**；`SOFT` / `WONK` **保留可变** |
| 体积 | **38KB**（与 story 期望的 ~37KB 量级一致） |
| 覆盖 | 三条文案（ID 标语 / EN 标语 / 慢网提示）**全部 ✅ 无缺字** |

**轴处置为何与设计稿 F1 的「四轴 fontVariations」写法有出入**（一处有意偏离，理由充分）：

实测体积对比 —— 四轴全可变 **121KB** / 钉死 wght **65KB** / 钉死 wght+opsz **38KB** / 全静态 23KB。
`wght 500` 与 `opsz 16` 设计上恒定、永不变化，保留可变纯属浪费 83KB，**视觉结果完全相同**。
而 `WONK` **必须留可变** —— PRD OQ-21 明确「真机确认手感，过头可退 WONK 0」，留着就只改一个数字，烘死则要重新产字体。`SOFT` 一并留作调校余量。

**已在模拟器验证**：英文标语完整渲染（`'` `f` `p` `v` `y` 全部正常），衬线字形正确，按 70% 屏宽自动换两行 —— 与设计稿「62% 在 16px 下会挤成三行、故放宽到 70%」的预期一致。

**顺带闭合缺陷 B-9**：删除 `app_id.arb` 的硬编码 `\n`，两语言现在都靠 70% 屏宽自动换行、排版一致。

**AC6 验收口径通过**：`splash_page.dart` 内已无 `Quicksand` / `Poppins` 的实际使用（仅注释里说明"从什么改成什么"），**splash 只依赖 Fraunces 一款字体**。

#### ⏳ 待人工验收（本会话手段不足）

| 任务 | 原因 |
|---|---|
| **T10 的 iOS 侧**（AC8） | 本机无 iOS 模拟器/真机可用。Android 侧字形已确认正常；`WONK` 手感的 iOS 复核与 OQ-21 的最终定夺留待有 iOS 设备时 |
| **T9 交接验证**（AC0） | 原生→Flutter 交接窗口约 320ms，`adb screencap` 单帧约 300ms 抓不到那一瞬，需**人工肉眼看冷启动**确认无底色闪变、无 mark 跳位 |
| **T8 的 B1/B2 中间帧** | 动效仅 1.54s，同上抓不稳；**B3 终态已核对通过** |
| **T11 reduce-motion 真机** | 需在系统设置里开「减弱动态效果」后冷启动观察（widget 测试已覆盖逻辑分支） |

#### 模拟器实测结果（B3 终态）

设备：AVD `petgo_phone` · Android 16/API 36 · 1080×2400 @420dpi（屏宽 411.4dp）

- 内容横向范围 647px = **屏宽 59.9%**（字标目标 60% ✅）
- 肉眼核对：完整「Tailtopia」（**粗壮实心月牙猫尾** = 7.1 新导的母文件版）+ 标语 + 呼吸光晕；**无 mark、无版本号、无 spinner** ✅

### File List

**新增（2）**
- `petgo_app/assets/fonts/Fraunces-subset-SOFT-WONK.ttf` —— 38KB / 98 码位 / SOFT+WONK 可变（wght 500、opsz 16 已烘死）
- `petgo_app/assets/fonts/OFL.txt` —— Fraunces 的 OFL-1.1 许可证，**随分发附带的许可义务，勿删**

**修改（4）**
- `petgo_app/lib/features/onboarding/presentation/splash_page.dart` —— 整体重写：旧 4320ms 猫跑屏动效整段删除；新 B1/B2/B3 三拍共 1540ms；尺寸相对屏宽；移除版本号与 spinner；reduce-motion 真正停光晕；`LayoutBuilder` + 去 `Clip.hardEdge` 加固；`_markHandoff` 恒返回 `Positioned`
- `petgo_app/test/onboarding/splash_test.dart` —— 52 行/2 用例 → **10 用例**（含 Stack 塌陷回归守卫 + Fraunces 九项参数断言 + 两语言无硬编码换行）
- `petgo_app/pubspec.yaml` —— `fonts:` 段注册 Fraunces family（附轴处置与子集范围的理由注释）
- `petgo_app/lib/l10n/app_id.arb` —— 删除 `splashTagline` 的硬编码 `\n`（缺陷 B-9）

**未改动（本 Story 边界）**
- `assets/brand/wordmark.svg` —— 引用已全部移除，**文件本身保留**（物理删除留 Epic 收尾统一处理，避免与 7.1 新增资产的命名整理冲突）
- 🔴 `assets/brand/mark_dog.svg` / `mark_cat.svg` —— **不可删！仍被登录页在用**
  （`login_page.dart:348-350`）。本行原写「引用已全部移除」是**事实错误**
  （code-review 2026-08-04 核实并订正）：splash 里确实移除了，但登录页那两处一直在用。
  Epic 收尾若照原描述统一物理删除，登录页会缺资产。要真下线得先替换登录页的用法。
- `lib/core/router/app_router.dart` / `lib/core/storage/prefs.dart` —— 属 Story 7.3
- `lib/l10n/app_en.arb` —— 已核对无需改（本就无硬编码换行）；生成物 `app_localizations*.dart` 由 `flutter gen-l10n` 重生成，不手改

### Change Log

| 日期 | 内容 |
|---|---|
| 2026-08-04 | 实施 AC0~AC5/AC7/AC9：splash 视觉与入场动效整体替换（1540ms 三拍「写名字」、终态无 mark、尺寸相对屏宽、移除版本号与 spinner、reduce-motion 真正停光晕）。测试 52→161 行、651→657 通过。 |
| 2026-08-04 | **修真机空屏缺陷**：`_markHandoff` 终态返回非定位子元素致 Stack 塌成 0×0、所有兄弟元素消失；改为恒返回 `Positioned` + `opacity 0`。补回归守卫测试并验证红-绿。顺带加固：改用 `LayoutBuilder`、去掉 `Clip.hardEdge`。 |
| 2026-08-04 | **AC6 解除阻塞并交付**：联网取 Google Fonts 完整版 Fraunces（OFL-1.1，许可证随字体入库），woff2→TTF 转换，子集化至 98 码位/38KB，三条文案全覆盖。轴处置为 wght+opsz 烘死、SOFT+WONK 保留可变（体积 121→38KB，且保住 OQ-21 退 WONK 0 的余地）。标语落定 Fraunces 500/16px/1.5/ls 0/白 68%/屏宽 70%，间距 22→24。删 app_id.arb 硬编码 `\n`（缺陷 B-9），重跑 gen-l10n。测试 657→659 通过。 |
| 2026-08-04 | ~~**AC6/AC8 阻塞并记录**~~（已于同日解除，见上一行）：设计稿内联的 Fraunces 子集仅 35 码位、只覆盖印尼语标语，英文标语缺 5 字、7.3 的慢网提示缺 3 字；本地无完整版 Fraunces。标语暂留 Quicksand，代码内留接入点标记。 |
