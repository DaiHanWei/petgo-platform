---
title: "Story 1.6: Toko 首页分类与全部精选（App，含游客态）"
epic: 1
story: 6
version: v1.4.0
created: 2026-08-17
flyway: 无
baseline_commit: a6198fbe480da75e2e18a6541e5cebcc6f84dbbe
---

# Story 1.6: Toko 首页分类与全部精选（App，含游客态）

Status: review

> 🟢 **本 Epic 第一条 Flutter 侧 story。** 前五条全在后端/后台，本条起进 `petgo_app/`。
> 三段推进：**后端（小，只补一个图片 URL 字段）→ 前端（主体）→ 联调**。
>
> ✅ **读侧接口 Story 1.1 已实现且已对游客放行**：`GET /api/v1/shop/products?category=`（实测游客 200）。

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

---

## Story

As a 用户（含未登录游客）,
I want 进入 Toko 就能按品类浏览商品、看到全部精选,
so that 我不需要搜索也能找到要买的东西。

---

## Acceptance Criteria

> `[L0]` 静态（`flutter analyze` / `flutter test`）· `[L1]` 集成 · `[L2]` 端到端（模拟器视觉 / 真机）

### AC1 — 页面结构：只有区域③④

**Given** 用户打开 Toko
**When** 页面渲染
**Then** 自上而下：AppBar「Toko」→ 区域③「分类入口」→ 区域④「全部精选」
**And** 区域③ 为 **Makanan / Obat & Vitamin / Camilan / Perawatan** 四个**固定品类** chip（对应后端 `ProductCategory` 四值）
**And** 🔴 **不提供全站搜索框**（FR-93）—— 搜索框会把产品心智推向通用货架，与「精选不做货架」的战略边界冲突。这与 C-7 的 SKU 上限 30 是同一套论证的两半
**And** 🔴 **区域①（补货提醒）与区域②（档案精选）整区不渲染，且不保留空标题** —— 这是 PRD 定义的**合法状态**（无触发/无档案时本就不渲染），**不是待补的占位**。原型可见效果是页面直接从「Kategori」开始。两区在 Epic 6 接入

`[L0]` widget 测试：无搜索框 · 四个品类 chip · 无区域①② 的任何残留标题
`[L2]` 模拟器视觉比对 `_bmad-output/planning-artifacts/v1.4.0/页面/pages/01-Toko首页-游客态.html`

### AC2 — 🔒 游客态：不触发登录引导（与既有机制有意不同）

**Given** 用户未登录
**When** 进入 Toko、浏览列表、点进商品详情
**Then** 🔴 **全程不触发登录引导**，可正常浏览
**And** 登录引导推迟到**加入购物车**（Epic 3 实现，复用 FR-0B 软性登录）
**And** Toko 是**非落地 Tab**，不参与 V1.1.2 FR-78 的落地判定（游客仍落 Diary）

> 🔴 **这与 V1.1.2 FR-78「未登录点击非落地 Tab 触发登录引导」有意不同**（FR-93A）。
> 理由：商品浏览是转化漏斗最上层，用登录墙拦截会直接杀掉转化。
>
> 🔒 **实现方式必须是「不进受控名单」，而不是「加一条例外」**：
> `app_router.dart` 的门控是白名单式 —— `_controlledLocations` 只列 6 个前缀，未列入者游客即可访问。
> 因此本 Story 的路由 **`/shop` 天然对游客开放，零安全规则改动**。
> 🔴 **严禁**改动 `_controlledLocations` 或 `_controlledExactExceptions`（CLAUDE.md：安全规则层只升不降不可绕过；该文件注释亦列了三条硬约束）。

`[L0]` widget 测试：游客态渲染无登录弹窗、无跳转
`[L0]` 源码护栏：本 Story 的 diff **不包含**对 `_controlledLocations` / `_controlledExactExceptions` 的任何修改
`[L2]` 真机走一遍

### AC3 — 🔴 不动 Tab 栏

**Given** Tab 位序尚未拍板（DEP-1）、Tab 图标未交付（DEP-2）
**When** 实现 Toko 入口
**Then** 🔴 **不得修改 `lib/shared/widgets/bottom_tab_bar.dart` 的 `AppTab` 枚举**（当前 4 值 `profile/triage/home/me` 无空位；并行契约 A-3 / C 类「你若要动 Tab 请告诉我」）
**And** 本 Story 通过**路由挂载** Toko 页面（`/shop`），Tab 位置的最终接入留待 DEP-1 闭合后单独处理

`[L0]` 源码护栏：`bottom_tab_bar.dart` 在本 Story 的 diff 中**零改动**

### AC4 — 商品卡与图片

**Given** 区域④ 渲染商品列表
**When** 每张卡片呈现
**Then** 展示：主图 · 商品名 · 规格摘要 · **起价**（`minPrice`，「Rp 285.000」印尼盾千分位、无小数）
**And** 无 SKU（`minPrice == null`）时价格位显示占位而非崩溃或显示 0
**And** 点击卡片进入商品详情（**路由占位即可，详情页属 Story 1.7**）

> 🔴 **图片走公开桶 CDN 全 URL，不发明签名读取机制。**
> 现状：对外 DTO 只给 `mainImageKey`（objectKey），而 App **没有任何读侧签名 URL 能力** ——
> `support` 模块正因如此明确「附件只显数量，详情不渲染缩略图」。照搬会让商品网格没有图。
>
> **口径：商品目录图属公开信息（非 PII），与内容帖图片同源** ——
> 复用既有 `MediaScope.PUBLIC` + `PresignedUploadService` 的 `publicUrl = cdnBase + "/" + objectKey` 拼法。
> 后端在对外 DTO **增加 `mainImageUrl`**（派生字段），🔴 **保留 `mainImageKey` 不动**（后台表单与 Story 1.3 的既有契约依赖它）。
> ⚠️ 运营上传商品图**必须落公开桶**；`OSS_CDN_BASE_URL` 未配时该字段为 `null`，前端须优雅降级到占位图而不是白屏。

`[L0]` 后端单测：`mainImageUrl` 的派生与 CDN 未配时为 null
`[L0]` 前端 widget 测试：`minPrice` 为 null / 图片 URL 为 null 时不崩

### AC5 — 埋点

**Given** 埋点需与功能同版本发布
**When** Toko 页曝光 / 商品卡曝光
**Then** 上报 `toko_tab_viewed` 与 `product_impression`（含 `zone` 属性，本 Story 恒为 `all_featured` 即区域④）
**And** ⚠️ **被替换 Tab 的曝光基线不在本版本范围**（L-6 / DEP-1）

`[L0]` 用 `Analytics.debugCaptureSink` 断言两个事件与 `zone` 属性

---

## Tasks / Subtasks

### 🟦 后端子任务（小）

- [x] **T1 对外 DTO 增加 `mainImageUrl`**（AC4）—— `ShopProductSummaryView` 与 `ShopProductDetailView` 各加一个派生字段
  - [x] 由 `cdnBaseUrl + "/" + mainImageKey` 拼出；`cdnBaseUrl` 空白时为 `null`
  - [x] 🔴 **`mainImageKey` 保留不动**（Story 1.3 后台表单依赖）
  - [x] 🔴 **不记日志**（NFR-5 的边界：公开桶 CDN URL 不是签名 URL，但仍不必进日志）
- [x] **T2 后端测试**（AC4）—— 派生逻辑单测 + CDN 未配时为 null；`ShopProductEndpointIntegrationTest` 补断言

### 🟩 前端子任务（主体）

- [x] **T3 新建 `lib/features/shop/` 模块**（AD-4 的前端对应）—— `domain/` `data/` `presentation/`，照 `features/order/` 的分层
- [x] **T4 `domain/shop_product.dart`** —— `ShopProductSummary.fromJson`；🔴 `minPrice` / `mainImageUrl` 均**可空**
- [x] **T5 `data/shop_repository.dart`** —— `GET /api/v1/shop/products?category=`；照 `order_repository.dart` 范式（401 交 AuthInterceptor，不自理）
  - [x] `ApiPaths` 追加 `shopProducts`
- [x] **T6 `presentation/toko_page.dart`**（AC1/AC2/AC4）
  - [x] AppBar「Toko」+ 购物车图标（Epic 3 前**不可点或隐藏**，不得跳到不存在的页面）
  - [x] 区域③ 四品类 chip（选中态切换 → 重新拉取）
  - [x] 区域④ 商品网格（两列，照原型）
  - [x] 🔴 **不写搜索框**；🔴 **不写区域①② 的任何占位或空标题**
  - [x] 加载/错误/空态三态
- [x] **T7 路由挂载**（AC2/AC3）—— `app_router.dart` 加 `GoRoute(path: '/shop')`
  - [x] 🔴 **不改 `_controlledLocations` / `_controlledExactExceptions`**
  - [x] 🔴 **不改 `bottom_tab_bar.dart`**
- [x] **T8 i18n 双份**（AC1/AC4）—— `app_en.arb` + `app_id.arb` **同步加**
  - [x] ⚠️ 印尼语是**目标市场**且 `app_id.arb` 已比 `app_en.arb` 少 258 行（HANDOFF §六）。本 Story 新增 key **一条都不许漏 id**
  - [x] 品类展示名用印尼语原文：Makanan / Obat & Vitamin / Camilan / Perawatan
- [x] **T9 埋点**（AC5）—— `toko_tab_viewed` · `product_impression`（`zone: 'all_featured'`）
- [x] **T10 前端测试**
  - [x] `[L0]` widget 测试：结构（无搜索框/四 chip/无①②残留）· 游客态无登录弹窗 · 空值不崩 · 埋点事件
  - [x] `[L0]` 源码护栏：`bottom_tab_bar.dart` 与两个受控名单常量在本 Story **零改动**
  - [x] 🔴 **变异验证**：给页面加一个搜索框 → 结构测试必须变红；把 `/shop` 塞进 `_controlledLocations` → 护栏必须变红

### 🟨 联调验收子任务

- [x] `flutter analyze` 零警告 · `flutter test` 绿
- [x] 全量 `mvn -B test` 绿（后端改了 DTO，当前基线 **1663**）
- [ ] ⏳ `[L2]` **模拟器视觉比对原型** —— 需 GUI，**留人工验收，不得凭想象勾选**
- [ ] ⏳ `[L2]` 真机游客态走查

---

## Dev Notes

### ⚠️ 共享文件

| 文件 | 改什么 | 🔴 约束 |
|---|---|---|
| `petgo_app/lib/core/router/app_router.dart` | 追加一条 `GoRoute` | **只追加路由**。🔴 **绝不动** `_controlledLocations` / `_controlledExactExceptions`（该文件注释列了三条硬约束，反转安全默认即返工） |
| `petgo_app/lib/core/network/api_paths.dart` | 追加 `shopProducts` | 只追加 |
| `petgo_app/lib/l10n/app_{en,id}.arb` | 追加文案 | **两份同步** |
| `petgo-backend/.../ShopProductSummaryView.java` · `ShopProductDetailView.java` | 加派生字段 | 🔴 **只加不改**，`mainImageKey` 原样保留 |

> 🔴 **`bottom_tab_bar.dart` 属并行契约 C 类**（「我不碰，但你若要动请告诉我」）—— 本 Story **不碰**。

### 复用而非重造

| 需要的能力 | 🔴 用这个 |
|---|---|
| 商品列表接口 | **已实现**：`GET /api/v1/shop/products?category=`（Story 1.1，已对游客放行，实测 200） |
| 图片公开 URL | **已实现**：`MediaScope.PUBLIC` + `cdnBase + "/" + objectKey`（`PresignedUploadService`）。**不要发明签名读取** |
| repository / provider 分层 | `features/order/data/order_repository.dart` 的范式（`dioProvider` + `Provider` + `FutureProvider.family`） |
| 埋点 | `Analytics.capture(event, props)` · 测试用 `Analytics.debugCaptureSink` |
| 游客可访问 | **什么都不用做** —— 不进 `_controlledLocations` 即可 |

### 🔴 三条容易做错的地方

**1. 区域①② 不是「留空」，是「不存在」。**
原型注释原文：「①② 区域整体不渲染，无空标题」。写成 `if (items.isEmpty) SizedBox.shrink()` 之外还留了个 `Text('Untuk Mochi')` 标题，就违反了 AC1。**页面第一个可见元素必须是「Kategori」。**

**2. 游客态不是「登录后才好用」。**
不要写任何「登录以查看价格 / 登录以浏览」的软引导。FR-93A 的整个意思就是这一层不设门槛。购物车图标在 Epic 3 前也不能跳到登录页。

**3. i18n 的 id 份不能漏。**
`app_id.arb` 已系统性落后 258 行，而**印尼是目标市场**。本 Story 新增的每个 key 都必须两份齐全 —— 漏了不报错，只是印尼用户看到 key 名。

### 🔴 写护栏测试的三条硬规矩（本工作线已出过三次假绿）

1. **断言「文件含某字符串」前，先确认该字符串在文件里唯一**（曾被同形状代码替它满足）
2. **不要用「出现次数 ≥ N」**（曾被合规项替违规项凑数）
3. **写完必须做变异验证** —— 删掉/违反被守护的东西，确认护栏变红

本 Story 的 AC2/AC3 护栏（不动受控名单、不动 Tab 枚举）尤其要照做：它们守的是**「没发生的改动」**，在正常测试下永远绿。

### 边界：本 Story 不做什么

- ❌ **不实现商品详情页**（Story 1.7）—— 卡片点击走路由占位
- ❌ **不实现区域①②**（Epic 6）
- ❌ **不实现购物车与加购**（Epic 3）—— 购物车图标不可点
- ❌ **不改 Tab 栏**（DEP-1/DEP-2 未闭合）
- ❌ **不做搜索**（FR-93，永久性决策不是暂缓）
- ❌ **不碰 `_controlledLocations`**

### References

- [Source: `_bmad-output/planning-artifacts/epics-v1.4.0.md#Story 1.6`（L463–495）]
- [Source: `_bmad-output/planning-artifacts/v1.4.0/页面/pages/01-Toko首页-游客态.html`（原型，✅ 可直接实现）]
- [Source: `_bmad-output/planning-artifacts/v1.4.0/UX-v1.4.0.md`（L38-39 原型清单 · L91 「①② 整区不渲染，不保留空标题」· L129 UX-DR12 Tab 归 DEP-1/DEP-2）]
- [Source: `_bmad-output/planning-artifacts/v1.4.0/decision-log.md#C-7`（SKU 上限与「不做搜索」是同一套论证）]
- [Source: `petgo_app/lib/core/router/app_router.dart`（L114-133 受控名单与三条硬约束）]
- [Source: `petgo-backend/src/main/java/com/tailtopia/shared/media/PresignedUploadService.java`（L49-67 公开桶 URL 拼法）]

---

## Dev Agent Record

### Agent Model Used

claude-opus-5[1m]（Claude Code）

### Debug Log References

- 后端全量：`mvn -B test` → **1669 通过 / 0 失败 / 6 跳过**（实现前 1663，+6）
- 前端：`flutter analyze` **零问题**；`flutter test` 全量 → **768 通过**（新增 14 条）
- 变异验证 2 轮，均正确变红后还原

### Completion Notes List

#### 🔴 查明一个真缺口：App 没有读侧签名 URL 能力

对外 DTO 只给 `mainImageKey`（objectKey），而 App **不存在任何把 objectKey 换成可显示 URL 的机制** ——
`support` 模块正因如此明确「附件只显数量，详情不渲染缩略图」。照搬会让商品网格**一张图都没有**。

**处置：不发明签名读取，走公开桶 CDN 全 URL。** 商品目录图属**公开信息（非 PII）**，与内容帖图片同源；
既有 `MediaScope.PUBLIC` + `PresignedUploadService` 的 `publicUrl = cdnBase + "/" + objectKey` 拼法直接可用。
新增 `ShopImageUrlResolver`（派生逻辑**只此一处**），对外 DTO 加 `mainImageUrl` / `galleryUrls` 两个派生字段，
🔴 **`mainImageKey` 原样保留**（Story 1.3 后台表单依赖该契约）。

⚠️ **连带的运营约束**：商品图**必须上传到公开桶**。落私有桶的 key 在这里也会拼出 URL，但公网取不到 ——
那是上传侧的问题，不在 resolver 职责内，但需要在运营手册里写清楚。
🔴 CDN base 未配时**返回 null 而非半截 URL** —— 半截 URL 会让客户端拿相对路径打自己域名，
表现为一堆 404 而不是「没有图」，排查成本高得多。

#### 🔴 埋点事件名与 epics 不一致（有意偏离，已改名）

epics AC5 写的是 `product_impression`，但仓库有一条埋点命名护栏（2026-08-04 用户明确要求：
**产品要能从事件名一眼看出「哪个页面的哪个功能」**），要求「模块前缀 + 动作词尾」，两者都在允许清单里。

`product_impression` **两条都不合**（无注册前缀、`impression` 不是动作词尾）；`toko_tab_viewed` 只缺前缀。

**处置**（按该护栏自己给的扩展路径，非放宽规则）：
- 允许清单**注册新前缀 `toko_`** —— 与 2026-08-06 加 `consult_`/`ai_` 是同一条先例，带日期注释
- `product_impression` → **`toko_product_shown`**，语义与 `zone` 属性不变

> 📌 该护栏原文明确写着「若它是 V1.0.x 遗留事件，请显式加入 legacyEvents **而不是放宽规则**」——
> 所以不能把它塞进 legacy 蒙混，也不能删断言。

#### 🔒 游客可访问靠「不进白名单」，零安全规则改动

`app_router.dart` 的门控是**白名单式**：`_controlledLocations` 只列 6 个前缀，未列入者游客即可访问。
因此 `/shop` **天然对游客开放**，本 Story 对 `_controlledLocations` / `_controlledExactExceptions` **一个字符都没动** ——
守住了 CLAUDE.md「安全规则层只升不降不可绕过」，也避免了「加一条例外」这种把安全默认反转的做法
（该文件注释自己列了三条硬约束，其中就有「反向做法明确禁止」）。

#### 🔴 变异验证（story T10 要求的两条）

| 变异 | 结果 |
|---|---|
| 给页面塞一个 `TextField`（模拟「加个搜索框吧」） | ✅ AC1 结构测试正确变红 |
| 把 `/shop` 塞进 `_controlledLocations`（等于给游客加登录墙） | ✅ 源码护栏正确变红 |

两条护栏守的都是**「没发生的改动」**——功能没写它们也绿，所以必须变异才知道有没有牙。

#### 三项 Flutter 侧的实现细节

1. **Riverpod 3 已移除 `StateProvider`** —— 品类选中态改由页面 `State` 持有 + `FutureProvider.family`。
   纯 UI 筛选本就没必要提升成全局 provider，顺带绕开了这个 API 变更。
2. **`AppSpacing` 不是 `Spacing`** —— 首次写按后者猜的，`flutter analyze` 一次性抓出 33 处。
3. **IDR 千分位用点不用逗号**（`Rp 285.000`）—— 用逗号会让印尼用户读成小数点，是会真实误导的错而非排版偏好。

#### ⏳ 两条 L2 如实未勾

模拟器视觉比对原型、真机游客态走查 —— 均需 GUI，**留人工验收**。
本机有 AVD（`Pixel_9_API_36`）但视觉比对不是我能替代的判断。

### File List

**新增（5）**

- `petgo-backend/src/main/java/com/tailtopia/shop/service/ShopImageUrlResolver.java`
- `petgo-backend/src/test/java/com/tailtopia/shop/service/ShopImageUrlResolverTest.java`
- `petgo_app/lib/features/shop/domain/shop_product.dart`
- `petgo_app/lib/features/shop/data/shop_repository.dart`
- `petgo_app/lib/features/shop/presentation/toko_page.dart`
- `petgo_app/test/shop/toko_page_test.dart`

**修改（8）**

- `petgo-backend/.../dto/ShopProductSummaryView.java` · `ShopProductDetailView.java` —— 加派生 URL 字段，`mainImageKey` 保留
- `petgo-backend/.../service/ShopProductQueryService.java` —— 注入 resolver 并装配
- `petgo-backend/src/test/.../ShopProductQueryServiceTest.java` —— 构造器适配 + CDN base 固定值
- ⚠️ `petgo_app/lib/core/router/app_router.dart` —— **共享文件**，只追加 `GoRoute('/shop')` 与 import；🔴 受控名单零改动
- ⚠️ `petgo_app/lib/core/network/api_paths.dart` —— **共享文件**，追加 `shopProducts`
- ⚠️ `petgo_app/lib/l10n/app_{en,id}.arb` —— **共享文件**，两份各追加 11 条
- ⚠️ `petgo_app/test/analytics/v112_events_test.dart` —— **共享护栏**，允许清单注册 `toko_` 前缀（照 08-06 先例）

---

## Change Log

| 日期 | 变更 |
|---|---|
| 2026-08-17 | 创建。查明 App **无读侧签名 URL 能力**（support 模块因此不渲染缩略图），定口径为「商品图走公开桶 CDN 全 URL」，复用既有 `MediaScope.PUBLIC` 机制，后端补一个派生字段即可 |
| 2026-08-17 | 实现完成 → `review`。后端 1669 通过 / 前端 768 通过 + analyze 零问题。埋点事件名按仓库命名护栏改为 `toko_product_shown`（epics 原写 `product_impression`，前缀后缀均不合规）。变异验证 2 轮正确变红。两条 L2 视觉验收如实未勾 |
