---
baseline_commit: 5f5982cb
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 8
story: 8-3
ad: [AD-S10, F-2, F-8, M6]
decisions: [C5, E7, E4, AB-19A]
fr: [SHOP-FR-12]
---

# Story 8-3: SKU 级图片

Status: ready-for-dev

> 自包含 story，可本地或云端执行（云端只跑到 L0）。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、每条 AC 标 L0/L1/L2）。
>
> 🔴 **前置：admin 主题 Epic 10 完成**（shop-v2 第二批，SD-16）。后台改动落在 Epic 10 Story 10.3 重构后的商品表单「规格与价格」卡里。
>
> 本 story **三段都碰**：后端（1 支 Flyway 迁移 + 实体 + 表单 + DTO）→ 后台前端（Thymeleaf + `admin.js`）→ App（DTO + 详情页图区）。按后端 → 后台 → App → 联调推进，一次只碰一侧。

## Story

As a 用户，
I want 选不同规格时看到对应的图，
so that 我知道自己买的到底是哪一款（同一款粮的 3kg 和 10kg 包装长得不一样）。

---

## Context

### 现状一：`ShopSku` **一个图片字段都没有**

`petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopSku.java`，`@Table(name="shop_skus")`:27，class:28。
字段全集（逐字核实）：`id:32` · `publicToken:35` · `productId:38` · `specName:41`(len40) · `price:45`(**`long`**，BIGINT 最小币种单位，禁 DECIMAL) · `netWeightG:49` · `costPrice:56` · `returnPolicy:61`(nullable=继承商品级) · `createdAt:64` / `updatedAt:67`。
方法：`create(String publicToken, Long productId, String specName, long price, …)`:73（内部调 `apply`:78）· `apply(specName, price, netWeightG, returnPolicy)`:83 · `applyCostPrice`:94 · `effectiveReturnPolicy`:117 · getter `:121-158`。

✅ **确认：无 `mainImageKey` / `mainImageW` / `mainImageH`，也无任何其它图片字段。**

### 现状二：商品级图片三件套长什么样（本 story 要照抄的范式）

`petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopProduct.java`：
- `mainImageKey`:52 — `@Column(name = "main_image_key", nullable = false, length = 255)`，javadoc `:51` 写明「OSS **objectKey，非 URL** —— 签名 URL 禁入库与日志（NFR-5）」
- `mainImageW`:65 / `mainImageH`:68 — `Integer`（可空），javadoc `:56-64` 明写「**只存原始宽高，不存比例、不存算好的高度**」「存量不回填，客户端占位兜底不可取消」
- `galleryKeys`:73 — JSONB

对应迁移 `petgo-backend/src/main/resources/db/migration/V20260827_1400__add_shop_product_main_image_size.sql`：`ALTER TABLE … ADD COLUMN IF NOT EXISTS main_image_w INTEGER;`（**DDL 幂等**，E7 常设要求）+ 两条 `COMMENT ON COLUMN`。

### 现状三：对外契约链路（C5 三处同改的确切落点）

```
ShopProductQueryService.detail(publicToken)  ← petgo-backend/src/main/java/com/tailtopia/shop/service/ShopProductQueryService.java:105-145
  ├─ :113-128  rows.stream().map(s -> new ShopSkuView(publicToken, specName, price, netWeightG,
  │                                  effectiveReturnPolicy, status, remaining))
  └─ :129-145  new ShopProductDetailView(token, name, brand, category,
                   mainImageKey, imageUrls.publicUrl(mainImageKey),      ← :134-135
                   galleryKeys, imageUrls.publicUrls(galleryKeys),       ← :136-137
                   species, bodySize, ageStage, detailHtml, feedingGuide,
                   shelfLifeNote, returnPolicy, skuViews)
```

- `ShopSkuView` — `petgo-backend/src/main/java/com/tailtopia/shop/dto/ShopSkuView.java:17-24`，7 个分量：`token` / `specName` / `price(long)` / `netWeightG` / `returnPolicy`(**effective 值**) / `stockStatus` / `remaining`。
  ✅ 全仓**只有一个使用点**：`ShopProductDetailView.java:36` 的 `List<ShopSkuView> skus` + 上面那个构造处。改它的爆炸半径很小。
- `ShopProductDetailView` — `:20-37`，javadoc `:16` 写明「**图片字段一律 OSS objectKey，非 URL**（NFR-5）」，但实际同时下发 key 与 url 两套（`:25-28`）—— **SKU 级照抄这个双字段范式**。
- `ShopImageUrlResolver.publicUrl(String objectKey)` — `petgo-backend/src/main/java/com/tailtopia/shop/service/ShopImageUrlResolver.java:38`；`publicUrls(List<String>)`:50。

App 侧：
- `petgo_app/lib/features/shop/domain/shop_product_detail.dart`
  - `class ShopSku`:54，字段 `token` / `specName` / `price` / `returnPolicy` / `stockStatus` / `netWeightG?` / `remaining?`；`fromJson`:77-99
  - `class ShopProductDetail`:116，`mainImageUrl`:135 / `galleryUrls`:138 / `minPrice` getter / `isSingleSku` getter；`fromJson`:154-183；`_blankToNull`:185
- 契约测试：`petgo-backend/src/test/java/com/tailtopia/shop/service/ShopProductQueryServiceTest.java:252-269`（`viewsNeverExposeInternalId` —— 用 `getRecordComponents()` 断言无 `id` 分量）

### 现状四：App 详情页图区与规格选择

`petgo_app/lib/features/shop/presentation/product_detail_page_v2.dart`：

- `_selectedSkuToken`:70 —— 注释 `:68` 逐字：「🔴 初值 null 且**不在任何地方被自动赋值** —— 多规格必须由用户显式选择（FR-94A）」
- `_galleryIndex`:72（页码指示器用）
- `_effectiveSku(d)`:82-90 —— 单规格直接返回唯一那个（`isSingleSku`），多规格未选返回 null
- `_content`:163-181 —— 依次 `_gallery` / `_priceBlock` / `_titleBlock` / `_variantBlock`（仅 `d.skus.length > 1`）/ `_returnPolicyBlock` / `_detailBlock`
- 🔴 **图区 `_gallery`:186** —— 图片列表在 `:187-190`：
  ```dart
  final images = <String?>[
    if (d.mainImageUrl != null) d.mainImageUrl,
    ...d.galleryUrls,
  ];
  final pages = images.isEmpty ? <String?>[null] : images;
  ```
  下面是 `SizedBox(height: 266)` + `Stack` + `PageView.builder(itemCount: pages.length, onPageChanged: (i) => setState(() => _galleryIndex = i), …)`
  —— ⚠️ **这个 `PageView` 今天没有 `PageController`**（本 story 要加一个，见 Tasks）。
  每页是 `ShopImage(url: pages[i], size: 266, fillWidth: true, radius: 0)`；点击进全屏查看器时传的是**过滤掉空串后的原图列表**（`:224-236`，注释 `:225-227` 明写「传原图 URL 不是缩略图」、`:232-233` 明写「下标在过滤后的表里查，不要直接用 i」）
- `_variantBlock`:428-462 —— `ShopChip`，`selected: _selectedSkuToken == s.token`（`:444`），`onTap: () => setState(() => _selectedSkuToken = s.token)`（`:447`）；售罄规格**仍可选中**（`:445-446` 注释）；未选时的提示 `:454-459`
- `ShopImage` — `petgo_app/lib/features/shop/presentation/widgets/shop_decor.dart:22-74`：`url == null || url.isEmpty` → `_StripePlaceholder`（`:59`）；否则 `AppImage.widget(url, …, thumbWidth: size × devicePixelRatio, errorBuilder: → placeholder)`

👉 **「不闪烁」的技术含义就在这里**：切换规格时若整个 `PageView` 被重建、或 `pages` 列表长度变化导致 `itemBuilder` 重跑并拿到一个新的 URL 字符串，`AppImage` 会重新走一次加载 ⇒ 中间那一帧是 `_StripePlaceholder` 的斜纹占位 ⇒ 肉眼可见的一闪。

### 现状五：后台上传链路（复用，零新增端点）

- 端点：`POST /admin/shop/products/images` — `AdminShopProductController.java:205-208`，`@PreAuthorize(EDIT_AUTH)`:206，方法体 `:209-217` 只做一件事：`images.upload(file, "shop-product")`（`:210`）
- `folder` 取值 **`shop-product`** 已登记（objectKey 形如 `public/shop-product/<UUID>.<ext>`，`AdminSeedImageService:75-76`）
- 闸门：`AdminSeedImageService` `MAX_BYTES = 10MB`:36 · 白名单 `{image/jpeg, image/png, image/webp}`:39（HEIC 显式拒）· `MAX_IMAGES = 9`:33（批量用，本路径一次一张）
- 返回体：`UploadedImage(String url, int w, int h, String warning, String objectKey, long sizeBytes)` — `petgo-backend/src/main/java/com/tailtopia/admin/seed/dto/UploadedImage.java:16-17`
- 前端控件：`petgo-backend/src/main/resources/static/admin/admin.js` 的 IIFE（`:255` 起），objectKey 模式分叉在 `:274`；`var MAX = 9;` 在 **`:256`（IIFE 级常量，不是每根节点可配）**
- 模板里 SKU 表单在 `shop-product-form.html:250-289`（独立 `<form>`，action 拼 `{productId}/skus`），字段 `:255-283`，**今天没有任何图片输入**

---

## Acceptance Criteria

**AC1 · 加列（Flyway 时间戳迁移）** `[L0]`
**Then** 新增一支迁移 `V<yyyyMMdd_HHmm>__add_shop_sku_main_image.sql`（**取实际创建时刻**，E7 时间戳制，禁序列号）`[L0]`
**And** 加三列：`shop_skus.main_image_key` varchar **NULL**（🔴 **可空** —— 规格无图是常态，回退商品主图）· `main_image_w` INTEGER NULL · `main_image_h` INTEGER NULL `[L0]`
**And** DDL 幂等：三条都用 `ADD COLUMN IF NOT EXISTS`（照抄 `V20260827_1400__add_shop_product_main_image_size.sql`）`[L0]`
**And** 三条 `COMMENT ON COLUMN`，`main_image_key` 的注释写明「**OSS objectKey，非 URL**（NFR-5）」，`_w`/`_h` 写明「原始像素；null=未知，客户端走占位/回退兜底」`[L0]`
**And** **存量不回填**（与商品级同口径）`[L0]`
**And** ⚠️ **列宽取 255 而不是 AD-S10 写的 200**：商品级同构列是 `@Column(length = 255)`（`ShopProduct.java:51`），两列不同宽只会制造后续困惑。此为对 AD-S10 的一处收敛，**在 Completion Notes 记一笔**`[L0]`
**And** ⚠️ **本 story 只加这三列，不加 `drug_reg_no`** —— 架构 delta 的 M6 把它和 8-4 的注册号列写在一支迁移里，但两条 story 独立排期，合并会互相阻塞。8-4 另起一支时间戳迁移（E7 常设，跨分支乱序是预期行为）`[L0]`
**And** 提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`，`flyway-guard` 绿 `[L0]`

**AC2 · 实体与后台表单** `[L0]`
**Then** `ShopSku` 增 `mainImageKey` / `mainImageW` / `mainImageH`（`String` + 两个 `Integer`），javadoc 照抄 `ShopProduct.java:51`、`:56-64` 的口径（objectKey 非 URL；只存原始宽高，不存比例、不存算好的高度）`[L0]`
**And** 🔴 `apply(...)`（`ShopSku.java:83`）的签名**不动** —— 它被 `create:78` 与 `AdminShopProductService.upsertSku:107` 调用；新增一个独立的 `applyMainImage(String key, Integer w, Integer h)`，在 `upsertSku` 里调 `[L0]`
**And** `ShopSkuForm`（`petgo-backend/src/main/java/com/tailtopia/admin/shop/dto/ShopSkuForm.java:16-37`）增 `mainImageKey` / `mainImageW` / `mainImageH` 三个字段 + getter/setter `[L0]`
**And** 🔴 **不得**给 `ShopSkuForm` 加任何与路径变量同名的字段；`POST /admin/shop/products/{productId}/skus` 的 `{productId}` 命名与 `AdminShopProductController:253-262` 那段事故注释**一字不动**（story 8-2 的硬纪律，本 story 同样受约束）`[L0]`
**And** 保存规格时把三个值写进库；**留空 = 清空该规格的图**（而不是保持原值）—— 与商品级「表单字段即权威」同口径 `[L1]`
**And** 🔴 `ddl-auto=validate` 是硬约束：实体列必须与迁移逐字对齐（类型、可空性）。`Integer` ↔ `INTEGER`，**禁 SMALLINT**（已踩过） `[L0]`

**AC3 · 后台规格表单可上传（复用既有 multipart 路径与 `folder=shop-product`）** `[L0]`
**Then** `shop-product-form.html` 的规格表单（`:250-289`）内新增单图上传区，复用 `data-seed-uploader` 控件的 **objectKey 模式** `[L0]`
**And** 上传走既有端点 `POST /admin/shop/products/images`（模板 `:123` 那个 `data-upload-url` 的同一个 URL），`folder` 仍是 `shop-product`；🔴 **不新增任何端点**（AB-19A，本 story 不在 AD-S13 例外清单里），也不新建 `AdminSeedImageService` 之外的上传服务 `[L0]`
**And** 该上传区**上限 1 张**：把 `admin.js:256` 的 `var MAX = 9;` 改为**每根节点可配**（`parseInt(root.getAttribute('data-max') || '9', 10)`），SKU 上传区传 `data-max="1"`；🔴 **缺 `data-max` 的老模板行为逐字不变**（沿用 `:379-380`「没有 `data-max-bytes` 的老模板自动跳过本检查」的既有做法）`[L0]`
**And** 只给 `data-field-main` / `data-field-w` / `data-field-h`、**不给 `data-field-gallery`** —— `sync` 的 `if (galEl)`（`admin.js:297`）已能容忍缺席，不需要新分支 `[L0]`
**And** 编辑既有规格时能看到已上传的图（服务端回填缩略图，结构与 `:141-151` 商品图那段一致），并能删除/替换 `[L0]`
**And** 写操作三件套：`@PreAuthorize` 复用 `AdminShopProductController:264`（`EDIT_AUTH`）；审计复用 `AdminShopProductService:115-117` 的 `AuditActions.SHOP_SKU_UPSERTED`（`AuditActions.java:128`），**不新增动作码**；新增文案进 **4 个** bundle（`messages.properties` 英文基线 + `_zh_CN` + `_en` + `_id`），`AdminMessagesParityTest`（`petgo-backend/src/test/java/com/tailtopia/shared/i18n/AdminMessagesParityTest.java:29`）绿 `[L0]`

**AC4 · 契约同改三处（C5）** `[L0]`
**Then** ① 后端 record：`ShopSkuView`（`:17-24`）增 `mainImageKey` 与 `mainImageUrl` 两个分量（照抄 `ShopProductDetailView:25-26` 的 key+url 双字段范式），`ShopProductQueryService:117-127` 的构造处填 `s.getMainImageKey()` 与 `imageUrls.publicUrl(s.getMainImageKey())` `[L0]`
**And** ② 契约 test：扩 `ShopProductQueryServiceTest:252-269` 的 `viewsNeverExposeInternalId` 仍绿，并**新增**一条断言 `ShopSkuView` 的分量集合**恰好等于** 9 个名字（集合相等，不是「包含」）`[L0]`
**And** ③ App data DTO：`petgo_app/lib/features/shop/domain/shop_product_detail.dart` 的 `ShopSku`（`:54-101`）增 `final String? mainImageUrl;`，`fromJson:77-99` 用同文件 `_blankToNull`（`:185`）的口径解析（空串 → null）`[L0]`
**And** ⚠️ **C5 原文写「四处」含 App mock，但 mock 子系统已于 `8e85b40d` 整体删除 —— 实为三处**；等价的第四个落点是 App 测试里的 `ShopSku(...)` 内联构造（`petgo_app/test/shop/product_detail_page_v2_test.dart`），一并补 `[L0]`
**And** 🔴 **`mainImageKey` 不得下发签名 URL、不得写日志**（NFR-5 / 日志纪律）；`publicUrl` 走的是**公共桶裸 CDN URL**（`ShopImageUrlResolver:38-47`），不是预签名 `[L0]`
**And** 老版本 App 行为零变化（只新增字段，未删未改语义）`[L0]`

**AC5 · App：选中规格时主图切换** `[L0]`
**Given** 一个多规格商品，其中 SKU-A 有图、SKU-B 有图
**When** 用户在规格区点 SKU-A
**Then** 图区的**第一页换成 SKU-A 的图**，且 `PageView` 自动回到第 0 页（新增 `PageController`，用 `jumpToPage(0)`，🔴 **不是 `animateToPage`** —— 换的是「看哪一款」不是「翻页」，动画会让人以为自己滑了一下）`[L0]`
**And** 商品图集（`d.galleryUrls`）仍在第 0 页之后，顺序不变 `[L0]`
**And** 页码指示器计数与 `_galleryIndex` 同步正确（切换后显示 1/N）`[L0]`
**And** 全屏查看器的 `srcs`（`:228-236`）包含切换后的那张图，且 `initialIndex` 仍在**过滤后**的表里查（`:232-233` 的既有纪律不得被破坏）`[L0]`
**And** 🔴 `_selectedSkuToken` 的初值仍是 null 且**仍不在任何地方被自动赋值**（`:68-70` 的 FR-94A 纪律不可破）—— 本 story 只在「用户已选」时换图，**不得**为了让图好看而自动选中第一个规格 `[L0]`
**And** 单规格商品（`isSingleSku`，`_effectiveSku:83` 直接返回唯一那个）若该 SKU 有图，落地即显示 SKU 图 `[L0]`
**And** `flutter test` 覆盖：多规格未选 → 显示商品主图；选 SKU-A → 显示 A 的图；改选 SKU-B → 显示 B 的图 `[L0]`

**AC6 · App：无图回退商品主图，且切换不闪烁** `[L0]`
**Given** SKU-C 没有图（`mainImageUrl == null`）
**When** 从 SKU-A（有图）切到 SKU-C
**Then** 图区回退到 `d.mainImageUrl`，**不出现空白、不出现 `_StripePlaceholder` 斜纹占位**（`shop_decor.dart:59`）`[L0]`
**And** 🔴 **不闪烁的可测化定义**：从 SKU-C 切到另一个同样无图的 SKU-D 时，图区第 0 页的 URL 字符串**逐字相同** ⇒ Flutter 不重建 ImageProvider ⇒ 零重新解码。测试断言「切换前后 `ShopImage` 的 `url` 属性相等」`[L0]`
**And** 给 `PageView` 一个**稳定的 `key`**，切换规格时不得整体重建；`itemBuilder` 里 `ShopImage` 的 `key` 用 URL 派生（同 URL ⇒ 同 key ⇒ 复用 element）`[L0]`
**And** 商品本身也没有主图时（`d.mainImageUrl == null` 且 SKU 无图）⇒ 沿用既有兜底：`pages = <String?>[null]` 渲染一个占位位，**页面结构不塌陷**（`:191` 的既有注释与行为）`[L0]`
**And** `flutter analyze` 零 issue；既有 `petgo_app/test/shop/product_detail_page_v2_test.dart` 全绿 `[L0]`

**AC7 · L0/L1 全绿** `[L0]`
**Then** `mvn -B clean package` 通过（🔴 **必须 `clean`** —— 改迁移后 `target/classes` 的旧文件会让 Flyway 报重复版本，2026-08-21 实际踩过）`[L0]`
**And** `bash scripts/ci/check-flyway-versions.sh origin/main` 绿 `[L0]`
**And** `AdminMessagesParityTest` 绿；`ShopProductQueryServiceTest` 绿（含新增的分量集合断言）`[L0]`
**And** `flutter analyze` 零 issue、`flutter test` 全绿 `[L0]`
**And** 迁移真跑一次：`shop_skus` 三列在库、`ddl-auto=validate` 通过、应用起得来 `[L1]`
**And** 既有 `AdminShopProductEndpointIntegrationTest`（`petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminShopProductEndpointIntegrationTest.java:37`）全绿，特别是 `:256-276`（空 id 隐藏域仍是新建）与 `:227-255`（新建 SKU 建库存行）`[L1]`

**AC8 · 模拟器验收** `[L2]`
**Given** Android 模拟器连 staging，后台已给某商品的两个规格各传一张不同的图、第三个规格不传图
**When** 在商品详情页依次点三个规格
**Then** 主图随之变化；点到无图的第三个规格时回退成商品主图 `[L2]`
**And** 🔴 反复来回切换十次，**图区不出现任何一帧的白屏或斜纹占位** `[L2]`
**And** 点开全屏查看器，看到的是当前规格那张图的原图（不是 266px 缩略图）`[L2]`
**And** 未选规格时落地显示商品主图，且**没有**任何规格被自动选中（FR-94A）`[L2]`

---

## Tasks / Subtasks

### 第一段 · 后端（迁移 + 实体 + 契约）

- [ ] **T1 · Flyway 迁移**（AC1）
  - [ ] `petgo-backend/src/main/resources/db/migration/V<yyyyMMdd_HHmm>__add_shop_sku_main_image.sql`，版本号取**创建时刻**
  - [ ] 逐字照抄 `V20260827_1400__add_shop_product_main_image_size.sql` 的形态：文件头注释说明「为什么需要 / 与商品级同一口径 / 存量不回填 / DDL 幂等」，三条 `ADD COLUMN IF NOT EXISTS`，三条 `COMMENT ON COLUMN`
  - [ ] `main_image_key VARCHAR(255)`（AC1 的收敛决定）· `main_image_w INTEGER` · `main_image_h INTEGER`，**全部 NULL**
  - [ ] 🔴 **不写 `drug_reg_no`**（属 8-4）
  - [ ] 🔴 **不动任何既有迁移文件**；跑 `bash scripts/ci/check-flyway-versions.sh origin/main`

- [ ] **T2 · `ShopSku` 实体**（AC2）
  - [ ] 三个字段 + `@Column(name = "main_image_key", length = 255)` / `@Column(name = "main_image_w")` / `@Column(name = "main_image_h")`
  - [ ] javadoc 照抄 `ShopProduct.java:51` 与 `:56-64`（objectKey 非 URL；只存原始宽高；可空且存量不回填）
  - [ ] 新增 `applyMainImage(String key, Integer w, Integer h)` + 三个 getter；🔴 **不改 `apply`:83 与 `create`:73 的签名**
  - [ ] 🔴 `Integer` 对 `INTEGER`，禁 SMALLINT

- [ ] **T3 · 后台表单与服务**（AC2/AC3）
  - [ ] `ShopSkuForm`（`:16-37`）加三个字段 + getter/setter；🔴 不加任何名为 `id` 之外的会与路径变量撞名的字段
  - [ ] `AdminShopProductService.upsertSku`（`:90-119`）在 `sku.apply(...)`（`:107`）/ `ShopSku.create(...)`（`:97`）之后调 `sku.applyMainImage(form.getMainImageKey(), form.getMainImageW(), form.getMainImageH())`
        —— 新建与更新**两条分支都要调**（AC2 的「留空=清空」在更新分支尤其重要）
  - [ ] 审计沿用 `:115-117` 的 `SHOP_SKU_UPSERTED`，摘要文案不变；🔴 **不加新的 `AuditActions` 常量**
  - [ ] `AdminShopProductController.detail`（`:140-190`）为每个 SKU 组装回填缩略图数据（照抄商品图 `:154-168` 的 `existingImages` 做法：key + `imageUrls.publicUrl(key)` + w/h，空值用空串不用 null —— `:152-153` 的既有理由）

- [ ] **T4 · 契约三处 + 测试**（AC4）
  - [ ] `ShopSkuView`（`:17-24`）加 `String mainImageKey` + `String mainImageUrl`；更新 javadoc，写明「null = 该规格无图，客户端回退商品主图」
  - [ ] `ShopProductQueryService:117-127` 的构造处填两个新值（`imageUrls.publicUrl` 已注入，`:135` 在用）
  - [ ] `ShopProductQueryServiceTest`：新增「`ShopSkuView` 分量集合恰好等于 9 个名字」的**集合相等**断言；确认 `viewsNeverExposeInternalId`（`:252-269`）仍绿
  - [ ] ⚠️ 检查 `publicUrl(null)` 的返回（`ShopImageUrlResolver:38`）—— 是 null 还是空串？**实施时确认**并把预期钉进测试；Dart 侧用 `_blankToNull` 两种都能吃下，但测试要写死一种

### 第二段 · 后台前端

- [ ] **T5 · `admin.js` 每根节点可配的张数上限**（AC3）
  - [ ] `:256` 的 `var MAX = 9;` 改为函数内按 root 取：`function maxOf(root) { return parseInt(root.getAttribute('data-max') || '9', 10); }`，`upload`（`:371-376`）改用它
  - [ ] 🔴 缺 `data-max` 的老模板（商品图、banner、内容侧、退货质检）行为**逐字不变**
  - [ ] ⚠️ **与 Story 8-1 改同一个 IIFE**：8-1 会把 `addThumb` 拆成 `buildThumb`/`applyThumbData` 并给 `upload` 加第三个参数。**先做 8-1，本 story 在其之上叠加**；若本 story 先落地，8-1 按当时签名调整。任一顺序下都不要复制一份 JS

- [ ] **T6 · 模板：规格表单的单图上传区**（AC3）
  - [ ] 在 `shop-product-form.html` 的规格 `<form>`（`:250-289`）里、进货价那一行（`:280-283`）之前，加一个 `s-form-row`：
        `<div data-seed-uploader data-mode="objectkey" data-max="1" data-field-main="sku-mainImage" data-field-w="sku-mainImageW" data-field-h="sku-mainImageH" th:attr="data-upload-url=@{/admin/shop/products/images}, data-msg-…">`
  - [ ] 三个隐藏/文本字段：`<input id="sku-mainImage" type="text" name="mainImageKey">`（可折进 `<details>` 兜底，比照 `:160-171`）+ 两个 `type="hidden"` 的 w/h
  - [ ] `accept="image/jpeg,image/png,image/webp"`（与 `:133` 一致）
  - [ ] 提示文案「规格图可留空；留空时 App 显示商品主图」走 `#{...}`
  - [ ] 🔴 该区**不给 `data-field-gallery`**（SKU 只有一张图，没有图集概念）
  - [ ] ⚠️ 编辑既有规格时的回填：今天这个 `<form>`（`:250`）**从不预填任何已有规格的值**（`:254` 的隐藏 id 恒空，`:257`/`:262` 等输入也无 `th:value`）—— 也就是说它今天只是个「新增」表单。**实施时确认**：是否在本 story 里给已有规格补「编辑」入口（点表格某行把值填进表单）。若不做，则 SKU 图只能在新建规格时上传，**必须在 story Completion Notes 与页面提示里写明**，否则运营会以为功能坏了

- [ ] **T7 · 四个 message bundle**（AC3）
  - [ ] 新 key 沿用 `admin.shop.sku.*` 前缀（既有见 `messages_zh_CN.properties:1479-1486`），建议 `admin.shop.sku.image` / `admin.shop.sku.imageHint`
  - [ ] 4 个文件同批（`messages.properties` 是英文基线且必须是三语超集，`AdminMessagesParityTest:24-25`）

### 第三段 · App

- [ ] **T8 · Dart DTO**（AC4）
  - [ ] `shop_product_detail.dart` 的 `ShopSku`（`:54-101`）加 `final String? mainImageUrl;`（构造为具名可选），`fromJson:77-99` 加 `mainImageUrl: _blankToNull(json['mainImageUrl']?.toString())`
  - [ ] 🔴 **不加 `mainImageKey`** —— App 从不消费 objectKey（`ShopProductDetail` 也只吃 `mainImageUrl` / `galleryUrls`，不吃两个 key 字段）。后端下发 key 是给后台/导出用的，Dart 侧不要跟着长
  - [ ] `petgo_app/test/shop/product_detail_page_v2_test.dart` 里的 `ShopSku(...)` 内联构造补参数（C5 的等价第四处）

- [ ] **T9 · 详情页图区**（AC5/AC6）
  - [ ] `_ProductDetailPageV2State` 加 `final _galleryController = PageController();`，`dispose` 里释放；`PageView.builder` 接上（`:198-201` 附近）
  - [ ] `_gallery(...)`（`:186`）的图片列表改为：
        ```dart
        final skuImage = _effectiveSku(d)?.mainImageUrl;          // 无图 → null
        final head = skuImage ?? d.mainImageUrl;                  // 🔴 回退在这一行，只有一行
        final images = <String?>[ if (head != null) head, ...d.galleryUrls ];
        ```
        —— 🔴 **回退必须是「取值时的 `??`」，不是「渲染时的 if/else 两条分支」**：两条分支会让 Flutter 换 widget 子树，那正是闪烁的来源（AC6）
  - [ ] `PageView` 给固定 `key`（如 `const ValueKey('pdpGalleryPageView')`）；`itemBuilder` 里 `ShopImage` 的 `key` 用 `ValueKey(pages[i] ?? '_placeholder_$i')`
  - [ ] 规格切换时跳回第 0 页：在 `_variantBlock:447` 的 `onTap` 里 `setState` 之后 `_galleryController.jumpToPage(0)`（并同步 `_galleryIndex = 0`）
        —— ⚠️ `jumpToPage` 要在 `PageView` 已 attach 时才安全；若 `d.skus.length <= 1` 时图区结构不同，加 `if (_galleryController.hasClients)` 保护
  - [ ] 全屏查看器（`:224-236`）不改逻辑，它读的就是 `pages` —— 确认 `srcs` 过滤与 `indexOf` 的既有写法未被破坏（`:232-233`）
  - [ ] 🔴 **一行都不要碰 `_selectedSkuToken` 的赋值时机**（`:70` / `:447`）：FR-94A「多规格不自动选中」不可破
  - [ ] 若图区还有「页码指示器」的渲染处（**实施时确认行号**），确认切换后计数正确

- [ ] **T10 · Flutter 测试**（AC5/AC6）
  - [ ] `petgo_app/test/shop/product_detail_page_v2_test.dart` 新增三条：
        - 多规格未选 → 图区第 0 页 URL == 商品 `mainImageUrl`
        - 点 SKU-A（有图）→ 图区第 0 页 URL == A 的 `mainImageUrl`
        - 点 SKU-C（无图）→ 图区第 0 页 URL == 商品 `mainImageUrl`；再点 SKU-D（也无图）→ URL **逐字相同**（AC6 的不闪烁可测化定义）
  - [ ] `flutter analyze` + `flutter test` 全绿

### 第四段 · 联调与云端须知

- [ ] **T11 · 联调与交付**
  - [ ] 本地起后端（L1）→ 后台传两张规格图 → App 连本地/staging 验 AC8
  - [ ] 云端只跑 L0：`mvn -B clean package`、`check-flyway-versions.sh`、`AdminMessagesParityTest`、`ShopProductQueryServiceTest`、`flutter analyze`、`flutter test`
  - [ ] AC7 的 L1 与 AC8 全部留本地；Completion Notes 标注「L1/L2 待本地验收」
  - [ ] Completion Notes 记：列宽 255 的收敛（AD-S10 写 200）、M6 拆成两支迁移、T6 的「编辑既有规格」是否落地

---

## Dev Notes

### 「不闪烁」为什么要写成 AC 而不是 Task

这是本 story 唯一容易做成「功能对了但体感很差」的地方。三个常见做法都会闪：

| 做法 | 为什么闪 |
|---|---|
| 切换时 `if (skuImage != null) ShopImage(skuImage) else ShopImage(d.mainImageUrl)` | 两条分支是两棵 widget 子树，切换时 element 被销毁重建 ⇒ `AppImage` 重新加载 ⇒ 中间帧是 `_StripePlaceholder`（`shop_decor.dart:59`） |
| 每次切换重建 `PageView`（不给 key / key 随 sku 变） | 同上，整个图区重建 |
| 用 `animateToPage` 做切换 | 不闪但会误导：用户以为自己滑了一下，而实际是「换了一款」 |

正确做法只有一条：**URL 在取值时用 `??` 回退，widget 树形状恒定，同 URL ⇒ 同 key ⇒ 同 ImageProvider ⇒ Flutter 走图片缓存不重新解码。**

### 为什么 `ShopSkuView` 同时下发 key 和 url

沿用 `ShopProductDetailView:25-28` 的既有范式（`mainImageKey` + `mainImageUrl` 并列）。url 是**公共桶裸 CDN URL**（`ShopImageUrlResolver.publicUrl:38-47`，与 `AliyunOssClient.publicUrl` 重复实现），**不是预签名 URL** —— 所以既不过期、也不触犯「签名 URL 禁入库禁日志」的纪律。

⚠️ 顺带一条已知事实（**本 story 不修**）：公共桶的投递路径**没有套 `x-oss-process` 剥 EXIF**（F-10：EXIF 剥离只在 5 处施用，feed / shop / admin 全未套）。SKU 图与商品图处境完全相同，本 story 不引入新问题也不顺手修（那是 E4 的另一半，范围外）。

### 迁移纪律速查

- **时间戳版本号**（E7 常设）：`V<yyyyMMdd_HHmm>__<snake>.sql`，取创建时刻，禁序列号
- **已被任何环境应用过的迁移绝不可改**（checksum 对不上，启动即失败）
- **`mvn -B clean package`**：不 `clean` 会把旧 class 一起打进 jar，Flyway 报 `Found more than one migration with version X`
- **`out-of-order=true` 全环境常开**，跨分支合并乱序是预期行为 —— 所以 8-3 与 8-4 各起一支迁移不会出问题
- 提交前 `bash scripts/ci/check-flyway-versions.sh origin/main`

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `shop/domain/ShopSku.java` | `:28-158`，**无任何图片字段**；`apply:83` 被 `create:78` 与 `upsertSku:107` 调 | +3 字段 + `applyMainImage` | 🔴 `apply` / `create` 签名不动 · `price` 为 `long` · `effectiveReturnPolicy:117` |
| `shop/dto/ShopSkuView.java` | `:17-24`，7 分量；全仓唯一使用点是 `ShopProductDetailView:36` | +`mainImageKey` +`mainImageUrl` | 7 个既有分量的顺序与语义（`returnPolicy` 是 effective 值、`remaining` 仅低库存时给） |
| `shop/service/ShopProductQueryService.java` | `detail:105-145`，SKU 映射 `:113-128`，批量取库存避免 N+1 `:110-112` | 构造处填两个新值 | 批量取库存的写法 · `status == LOW_STOCK ? available : null` |
| `admin/shop/dto/ShopSkuForm.java` | `:16-37`，`id:18` … `returnPolicy:23` | +3 字段 | `id` 字段（编辑态靠它区分新建/更新） |
| `admin/shop/service/AdminShopProductService.java` | `upsertSku:90-119`（`ensureRow:113`、审计 `:115-117`）、`validateSku:208-216` | `upsertSku` 调 `applyMainImage` | `ensureRow` · 审计码不新增 · 进货价结构性门控（类注释 `:27-29`） |
| `admin/shop/web/AdminShopProductController.java` | 上传 `:205-218` · `detail` 的 `existingImages:154-168` · 🔴 `{productId}` + 事故注释 `:253-263` | `detail` 补每个 SKU 的回填图数据 | 🔴 `:253-262` 注释一字不删 · 无新端点 · 本地 catch AppException |
| `static/admin/admin.js` | `MAX=9:256`（IIFE 级）· `sync:261`（objectKey 分叉 `:274`，`if (galEl):297`）· `upload:371` | `MAX` 改每根节点可配 | 缺属性的老模板行为逐字不变 · `sync` 是唯一写回口径 · 平铺模式 |
| `templates/admin/shop-product-form.html` | 规格 `<form>` `:250-289`，无图片输入；商品图控件范式 `:117-153` | 规格表单加单图上传区 | 编辑态/新建态分叉 `:221` · 隐藏 `id` `:254` · 主图不写 `required`（`:157-159`） |
| `petgo_app/.../domain/shop_product_detail.dart` | `ShopSku:54-101`（无图片字段）· `ShopProductDetail:116-186`（`mainImageUrl:135`） | `ShopSku` + `mainImageUrl` | `_blankToNull:185` 口径 · `StockStatus.fromApi` 未知降级为售罄 · `isSingleSku` |
| `petgo_app/.../presentation/product_detail_page_v2.dart` | `_selectedSkuToken:70`（FR-94A）· `_galleryIndex:72` · `_effectiveSku:82-90` · `_gallery:186`（图列表 `:187-190`，PageView **无 controller**）· `_variantBlock:428-462`（`onTap:447`） | 图列表首项改 `skuImage ?? d.mainImageUrl`；加 `PageController` + `jumpToPage(0)`；稳定 key | 🔴 `_selectedSkuToken` 不自动赋值 · 全屏查看器传原图且 index 在过滤后表里查（`:225-233`） · 售罄规格仍可选中 |
| `i18n/messages{,_zh_CN,_en,_id}.properties` | `admin.shop.sku.*:1479-1486`（zh 行号） | +2 key × 4 包 | 四包键集相等 + 基线是超集 |

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 8-3]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#SHOP-FR-12]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S10（`shop_skus.main_image_key` / `_w` / `_h`；复用后台 multipart 与 `folder=shop-product`）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#4 迁移清单 M6]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#5 契约变更清单（C5 同改三处；App mock 已随 `8e85b40d` 删除）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#0 前置事实 F-2（E7 时间戳制）/ F-8（写操作三件套 + AB-19A）/ F-10（EXIF 未剥）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#D.2（`ShopProduct` / `ShopSku` 字段与 publicToken）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#D.10（上传链路、`folder=shop-product`、`AdminSeedImageService` 闸门、公共桶裸 CDN URL）]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#C5 / #E4 / #E7]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/HANDOFF.md#三 纪律 3B（路径变量与 `@ModelAttribute` 同名）:111-119]

---

## 验收与交付

| 层 | 怎么验 | 在哪跑 |
|---|---|---|
| **L0** | `mvn -B clean package`；`bash scripts/ci/check-flyway-versions.sh origin/main` 绿；`ShopProductQueryServiceTest`（含分量集合相等断言）+ `AdminMessagesParityTest` 绿；`flutter analyze` 零 issue；`flutter test` 全绿（含三条图区用例） | 云端 / 本地 |
| **L1** | 迁移真跑：`shop_skus` 三列在库、`ddl-auto=validate` 通过、应用起得来；`AdminShopProductEndpointIntegrationTest` 全绿；后台保存带图规格后库里三列有值、留空则被清空 | 本地（Docker postgres + redis） |
| **L2** | Android 模拟器连 staging：三个规格（两有图一无图）来回切换十次，主图跟着变、无图回退、**零白屏零斜纹**；全屏查看器是原图；未选规格时无自动选中。截图/录屏留档 | 本地 |

**交付物**：1 支 Flyway 迁移 + `ShopSku` + `ShopSkuForm` + `AdminShopProductService.upsertSku` + `AdminShopProductController.detail` + `ShopSkuView` + `ShopProductQueryService` + `admin.js`（`MAX` 可配）+ `shop-product-form.html` + 4 个 message bundle + `shop_product_detail.dart` + `product_detail_page_v2.dart` + 后端 1 条契约断言 + Flutter 3 条用例。
**不交付**：`drug_reg_no`（属 8-4）、新端点、新审计码、App 侧 `mainImageKey`、EXIF 剥离、SKU 图集（只有一张图）。

## Definition of Done

- [ ] AC1~AC8 全部满足（L1/L2 未跑的写明「待本地验收」）
- [ ] 迁移用时间戳版本号、三条 `ADD COLUMN IF NOT EXISTS`、三条 `COMMENT ON COLUMN`、存量不回填；`check-flyway-versions.sh` 绿
- [ ] 迁移里**没有 `drug_reg_no`**；本次 diff 中**没有改动任何既有迁移文件**
- [ ] `main_image_w/h` 是 `INTEGER` ↔ `Integer`，**没有 SMALLINT**；`ddl-auto=validate` 通过
- [ ] `ShopSku.apply` / `ShopSku.create` 的签名未变，新图字段走独立的 `applyMainImage`
- [ ] C5 三处同改完成（后端 record + App DTO + 契约 test）+ App 测试内联构造；契约断言用**集合相等**
- [ ] App 侧**没有**新增 `mainImageKey` 字段（只吃 url）
- [ ] 回退写成取值时的 `??`（一行），**不是**渲染时的两条 if/else 分支；`PageView` 有稳定 key，`ShopImage` 的 key 由 URL 派生
- [ ] 有「两个无图规格互切时第 0 页 URL 逐字相同」的 Flutter 测试（AC6 的不闪烁定义）
- [ ] 🔴 `_selectedSkuToken` 的初值与赋值时机未变，FR-94A「多规格不自动选中」未被破坏
- [ ] diff 中**没有新增 `@PostMapping` / `@GetMapping`**（AB-19A）、**没有新增 `AuditActions` 常量**
- [ ] `admin.js` 的 `MAX` 改为每根节点可配后，缺 `data-max` 的四条老上传线行为逐字不变，已实际点过
- [ ] 新增 message key 写进 **4 个** bundle，`AdminMessagesParityTest` 绿
- [ ] `mvn -B clean package`（带 `clean`）+ `flutter analyze` + `flutter test` 全通过
- [ ] Completion Notes 记录：列宽取 255（AD-S10 写 200）的收敛理由、M6 拆成两支迁移、「编辑既有规格」入口是否落地、与 Story 8-1 的 `admin.js` 改动先后、L1/L2 验收状态
