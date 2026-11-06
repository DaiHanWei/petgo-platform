# Story 6.1: banner 轮播与领券跳转、商品下架与评价（后端）

Status: review

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全线 25 story 同一分支、云端单会话串行）
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9）> PRD `电商/1-4-2电商prd.md`（§3.6 FR-132、§3.7 FR-133「找商品 / 商品详情」、§3.0 旅程 E 第 2 条、§4 banner 埋点）> `architecture-v1.4.2-shop-delta.md`（D-16、D-17、§1.2 第 12 / 14 条、P-5 / P-8 ⑤⑥）> UI 稿（A1 / A2 / A5 / A7 / A9 / B1 / B13 / B14 说明）；跨 story 契约以 `CROSS-STORY-DECISIONS.md` 为准；云端执行以 `cloud-rules-shop.md` 为准。
> 前置：**2.1**（`shop_coupons` 表与实体、`ShopCouponRepository`）、**2.2**（`POST /me/coupons/claim {source: BANNER}`、商品卡 `promo` 计算组件）。
> 范围层：**只后端**。banner / 评价的后台**页面**不在本线（S-0 / S-2）：本 story 实现 service 层（banner 配置、评价隐藏、评价展示开关）+ 测试；App 首页轮播 / 下架页 / 评价列表归 8.1 / 8.2。
> 迁移：`V20261105_1645__shop_banner_schedule_and_coupon_target.sql`（⑤）、`V20261105_1650__shop_review_hidden_and_display_flag.sql`（⑥）（sprint-status 预分配；实现时若撞号按 CLAUDE.md 取创建时刻重新取号并回写 sprint-status 头注释）。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-9**：评价出参补 `nickname`、`specName`；商品详情补 `reviewListEnabled`（开关状态）；商品列表 / 搜索补 `totalCount`（首页「{n} produk」）。
- **C-2**：错误码一律连字符风格（`coupon-not-found`、`coupon-code-used`、`coupon-not-in-period`、`coupon-already-claimed`、`coupon-sold-out`、`coupon-redeem-locked`、`coupon-invalid`、`quote-expired`、`quote-failed`、`not-serviceable`、`sku-not-sellable`、`product-delisted`），登记进 `ErrorTypes`。

## Story

As a 用户,
I want 首页看到多张有效 banner、点进下架商品有明确提示、能看评价,
so that 不会点进空页。

## Acceptance Criteria

> 层级：`[L0]` 纯单元 / 静态（云端可跑）· `[L1]` 需 Docker postgres + redis（本地）· `[L2]` 本 story 无。

### AC1 — 迁移 ⑤ `V20261105_1645__shop_banner_schedule_and_coupon_target.sql` `[L0]` `[L1]`（D-16）
```sql
ALTER TABLE shop_banners
    ADD COLUMN start_at TIMESTAMPTZ,
    ADD COLUMN end_at   TIMESTAMPTZ;
ALTER TABLE shop_banners ADD CONSTRAINT ck_shop_banners_schedule
    CHECK (start_at IS NULL OR end_at IS NULL OR start_at < end_at);
-- 跳转类型加 COUPON：DROP + ADD 重列全集（取 V20261030_0330 原文 4 值 + COUPON）
ALTER TABLE shop_banners DROP CONSTRAINT ck_shop_banners_target_type;
ALTER TABLE shop_banners ADD CONSTRAINT ck_shop_banners_target_type
    CHECK (target_type IN ('NONE', 'PRODUCT', 'CATEGORY', 'EXTERNAL', 'COUPON'));
COMMENT ON COLUMN shop_banners.start_at IS '排期起（含），NULL = 不限，V1.4.2';
COMMENT ON COLUMN shop_banners.end_at   IS '排期止（不含），NULL = 不限，V1.4.2';
COMMENT ON COLUMN shop_banners.target_value IS '跳转目标：商品 public_token / ProductCategory / https 链接 / 券模板 public_token；NONE 时为空';
```
- 存量 banner 两列为 NULL（= 一直有效，行为不变）；`ck_shop_banners_target_value`（NONE ⇔ 值为空）保留不动。
- 文件头注释写明：取代 V20261030_0330 「轮播仍不做」的口径（PRD FR-132 多张轮播上限 6）；CATEGORY 值保留在库里，下发时转 `TOKO_HOME`（品类筛选本版下线）。

### AC2 — 迁移 ⑥ `V20261105_1650__shop_review_hidden_and_display_flag.sql` `[L0]` `[L1]`（D-17）
```sql
ALTER TABLE shop_reviews ADD COLUMN hidden_at TIMESTAMPTZ;
-- 详情页评价区：只读「已发布且未隐藏」
CREATE INDEX ix_shop_reviews_product_visible ON shop_reviews (product_id, created_at DESC, id DESC)
    WHERE review_status = 'PUBLISHED' AND hidden_at IS NULL;
-- 评价列表展示开关（PRD：默认关闭，运营过完历史评价后打开）
ALTER TABLE shipping_settings ADD COLUMN review_display_enabled BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN shop_reviews.hidden_at IS '运营隐藏时刻（V1.4.2）；非空 = 不展示、不计入均分与条数';
COMMENT ON COLUMN shipping_settings.review_display_enabled IS '商品评价列表展示开关（V1.4.2，默认关）；关闭时只展示均分与条数';
```
- 开关放在 `shipping_settings`（电商全局单行配置，已承载退货地址等非运费项）：架构 D-17 写的「`platform_config` 键 `shop.review.display-enabled`」**在代码中不存在**（无键值配置表，见「待确认」1）。
- 旧索引 `ix_shop_reviews_product` 保留（不 DROP，避免影响已应用环境）。
- 无 `SMALLINT`。

### AC3 — 实体与枚举 `[L0]` `[L1]`
1. `BannerTargetType` 加 `COUPON`（`target_value` = 券模板 `public_token`），改类注释（删去「领券没有对应功能，本版不加」）。**`TOKO_HOME` 不进枚举**：它只是下发值，不落库。
2. `ShopBanner` 加 `startAt / endAt` 映射与 `schedule(Instant start, Instant end)`（`start >= end` 抛 `AppException.validation`）、`isScheduledAt(Instant now)`（`(start 为空或 ≤ now) 且 (end 为空或 > now)`）。
3. `ShopReview` 加 `hiddenAt` 映射与 `hide(Instant)`（已隐藏幂等，不改写时刻）/ `unhide()` / `isHidden()`；`isPublished()` 语义不变（隐藏的评价状态仍是 `PUBLISHED`，可撤销隐藏）。
4. `ShippingSettings` 加 `reviewDisplayEnabled` 映射 + getter / setter（单行 id=1）。
5. `ddl-auto=validate` 通过。

### AC4 — banner 选取单一来源 `ShopBannerQueryService` `[L0]` `[L1]`（D-16、PRD FR-132 展示规则）
新类 `B/shop/service/ShopBannerQueryService.liveBanners(Instant now)` → `List<LiveBanner>`（最多 6 张），**`/shop/banners`、旧 `/shop/banner`、后台「生效中」标记三处都只调它**：
1. 取候选：`active = true AND (start_at IS NULL OR start_at <= now) AND (end_at IS NULL OR end_at > now)`，按 `sort_weight DESC, id DESC`，最多取 30 行（走现有 `ix_shop_banners_active_pick`）。
2. 逐张判目标有效（**批量查，禁止逐张查库**）：
   - `PRODUCT`：商品在售——一次 `ShopProductRepository.findActivePublicTokens(tokens)`（已有，:25）；不在售 → **整张剔除**（取代 1.4.1「降级为不可点」）。
   - `COUPON`：券模板存在、`status = ACTIVE`、`acquire_type = CLAIM`、当前在发放期（`issue_start_at <= now` 且 `issue_end_at` 为空或 `> now`）、未领完（`total_quantity IS NULL OR issued_count < total_quantity`）——一次批量查（在 2.x 的 `ShopCouponRepository` 上加只读方法 `findClaimableTokens(Collection<String> tokens, Instant now)`；2.x 已有等价方法则复用）；不满足 → 整张剔除。**是否被当前用户领过不影响展示**（点击时由领券接口返回 `already-claimed`）。
   - `CATEGORY`：保留，下发时 `targetType = "TOKO_HOME"`、`targetValue = null`（品类筛选下线，PRD FR-132 / FR-133.1）。
   - `EXTERNAL` / `NONE`：保留原样。
3. 图片 URL 拼不出（CDN base 未配置，`ShopImageUrlResolver.publicUrl` 返回 null / 空）→ 整张剔除（与旧端点同口径）。
4. 过滤后按原顺序取前 **6** 张。
5. `LiveBanner(ShopBanner banner, String imageUrl, String wireTargetType, String wireTargetValue)`；纯函数部分（给定候选 + 在售集合 + 可领券集合 + url 函数 → 结果）抽成静态方法 `select(...)` 供 L0 单测。

### AC5 — `GET /api/v1/shop/banners` `[L0]` `[L1]`（D-16）
1. 新端点（放 `ShopBannerController`，类级 `@RequestMapping` 改为方法级路径），游客可访问：`SecurityConfig` 在现有 `GET /api/v1/shop/banner`（:272）旁加 `.requestMatchers(HttpMethod.GET, "/api/v1/shop/banners").permitAll()`。
2. 响应 200：`{ "items": [ { "bannerId", "imageUrl", "imageW", "imageH", "targetType", "targetValue" } ] }`，`items` 复用 `ShopBannerView` 记录（字段名不变），`targetType ∈ NONE | PRODUCT | TOKO_HOME | EXTERNAL | COUPON`；无生效 banner → `{"items": []}`（App 整块不渲染）。
3. 不返回自增 id；`bannerId` = `public_token`（埋点 `banner_id` 用，position 由 App 按数组下标 + 1 上报）。

### AC6 — 旧 `GET /api/v1/shop/banner`（旧版 App）`[L0]` `[L1]`（D-16、NFR6）
1. 返回 `liveBanners(now)` 的**第一张**（无则 204，同现状）；下发形状不变（`ShopBannerView` 单对象）。
2. 对旧版 App 的兼容转换：`COUPON` → `targetType = "NONE"`、`targetValue = null`（旧版不认识领券，按不可点渲染）；`CATEGORY` 保持 `CATEGORY` + 原值（旧版 App 支持品类跳转）；`TOKO_HOME` 不出现在旧端点。
3. 行为变化（按设计）：目标商品下架的 banner 从「降级不可点」变为「整张不展示、轮到下一张」；排期外的 banner 不再返回。`ShopBannerControllerTest.delistedProductIsServedAsNotTappable` 改断言表达新规则（不删）。

### AC7 — 后台 banner 配置 service（供后台线）`[L0]` `[L1]`（S-2、D-16）
`B/admin/shop/service/AdminShopBannerService`：
1. `ShopBannerForm` 加 `Instant startAt`、`Instant endAt`（可空）；`create / update` 调 `banner.schedule(startAt, endAt)`；`start >= end` → 422 `admin.err.banner.scheduleInvalid`。
2. `target(form)`（:158-193）加 `COUPON` 分支：值为券模板 `public_token`，校验存在、`status = ACTIVE`、`acquire_type = CLAIM`、未过发放期止（`issue_end_at` 为空或 > now）——不满足 → 422 `admin.err.banner.targetCoupon`（「券不存在、已停用、不是领取类或已过发放期」）。`NEWBIE / CODE` 类券不可绑 banner（PRD「不做只在 banner 里领的券」，banner 券 = 可领取券）。
3. `liveId()`（:52-56）改为 `ShopBannerQueryService.liveBanners(now)` 第一张的 id（保持「与 App 同源」这一注释的承诺）；新增 `liveIds()`（全部生效张的 id 集合）。`stateOf(b, liveId)` 不改签名（后台页面由后台线升级为多张「生效中」）。
4. 四个 `messages*.properties` 补 `admin.err.banner.scheduleInvalid`、`admin.err.banner.targetCoupon`；审计沿用现有 `SHOP_BANNER_CREATED / UPDATED`。

### AC8 — 商品详情区分下架 410 `[L0]` `[L1]`（D-17、P-5、PRD FR-133.7）
1. `ShopProductQueryService.detail`（:181-183）：先 `findByPublicToken(token)`（`ShopProductRepository` 新增派生查询）；不存在 → 404（原文案）；存在但 `active = false` → **410**，ProblemDetail `type = https://petgo/errors/product-delisted`（`ErrorTypes` 新增 `SHOP_PRODUCT_DELISTED`；`AppException` 新增工厂 `productDelisted(String detail)`，`HttpStatus.GONE`），`detail` 为中文说明（App 按 type 分流，不读 detail）。
2. `GlobalExceptionHandler` 对 410 不需特殊处理（按 `AppException.status` 输出）；核对 410 能正常序列化为 ProblemDetail。
3. 评价接口 `GET /shop/products/{token}/reviews`（AC10）对下架商品保持 404（现状），不改为 410（App 先请求详情，已在详情处分流）。
4. 按设计被打破的测试：`ShopProductEndpointIntegrationTest`「未上架商品不可见 —— 详情 404」（:98-102）改为 410 且断言 `type`；`ShopProductQueryServiceTest` 中 mock `findByPublicTokenAndActiveTrue` 的详情用例改 mock `findByPublicToken`。

### AC9 — 评价隐藏、展示开关与聚合口径 `[L0]` `[L1]`（D-17、PRD FR-133.6）
1. `ShopReviewRepository`：`findByProductIdAndReviewStatusOrderByCreatedAtDescIdDesc`、`countByProductIdAndReviewStatus`、`averageRating`（:17-29）全部加 `hidden_at IS NULL` 条件（派生查询改名为 `...AndHiddenAtIsNull...` 或改 `@Query`）；新增按游标分页查询（`(created_at, id) < (:ts, :id)`，`LIMIT :size + 1`）。
2. `ShopReviewService.publishedFor / publishedCount / averageRating`（:156-170）随之只算「已发布且未隐藏」；`myReviews`（本人评价列表）**不受隐藏影响**（作者仍能看到自己的评价，状态照旧）。
3. 新类 `B/admin/shop/service/AdminShopReviewService`（供后台线）：
   - `hide(long reviewId, Long actorAccountId)` / `unhide(long reviewId, Long actorAccountId)`：不存在 → 404；幂等；审计新常量 `SHOP_REVIEW_HIDDEN / SHOP_REVIEW_UNHIDDEN`（摘要只记评价 id 与商品 token，**不记评价正文**——`content` 已是日志打码键）。
   - `setDisplayEnabled(boolean enabled, Long actorAccountId)`：写 `shipping_settings.review_display_enabled`，审计新常量 `SHOP_REVIEW_DISPLAY_TOGGLED`。
   - `isDisplayEnabled()` 只读。
   - 四个 `messages*.properties` 补三个 `admin.audit.action.*` 键。

### AC10 — `GET /api/v1/shop/products/{token}/reviews` 扩展 `[L0]` `[L1]`（D-17）
1. 现有端点（`ShopReviewController.forProduct`:40-57）保持游客可访问、商品不存在或已下架 404、`limit` 参数（上限 20）不变；**新增**可选 `cursor`。
2. 响应 `ProductReviewsView` 末尾追加 `boolean displayEnabled`、`String nextCursor`、`boolean hasMore`（全局 NON_NULL；原 `total / averageRating / items` 语义不变，旧版 App 只读前两者）：
   - `displayEnabled = false`（默认）→ `items = []`、`hasMore = false`、无 `nextCursor`；`total` / `averageRating` 照常（只算已发布且未隐藏）。
   - `displayEnabled = true` → 按 `created_at DESC, id DESC` 游标分页返回已发布且未隐藏的评价；`nextCursor` 为不透明 Base64 串（坏游标按首页）。
3. 无评价 → `total = 0`、`averageRating = null`（不是 0，现有约定）、`items = []`。

### AC11 — 商品详情带评价摘要 `[L0]` `[L1]`（epics 6.1「true → 详情带前 3 条」）
1. `ShopProductDetailView` **末尾追加** `ReviewSummary reviewSummary`：`record ReviewSummary(long total, Double averageRating, boolean displayEnabled, List<ShopReviewView> items)`。
2. 开关关 → `items = []`；开关开 → 已发布且未隐藏的最新 **3** 条（图片 URL 同 `ShopReviewController` 写法用 `ShopImageUrlResolver.publicUrls`）。
3. 只多两次查询（计数 + 均分可一条 SQL 合并；列表一条），不产生 N+1。
4. 详情仍对游客开放；410 / 404 路径不组装摘要。

### AC12 — `GET /api/v1/shop/products/top?limit=6` `[L0]` `[L1]`（D-17、PRD FR-133.2 / FR-133.8 无档案推荐）
1. 新端点（`ShopProductController`，字面路径 `/top` 优先于 `/{token}`），游客可访问（已被 `/api/v1/shop/products/**` permitAll 覆盖）。
2. 返回在售商品按运营权重（`sort_weight DESC, id DESC`，即 `findByActiveTrueOrderBySortWeightDescIdDesc`）前 N 个，`limit` 默认 6、范围 1 ~ 20（越界钳位，不报错）；元素为 `ShopProductSummaryView`（含 2.2 加的 `promo`，**复用 2.2 的批量 promo 组件**，与列表口径一致；游客按「发放期内 ACTIVE 的 CLAIM 券覆盖」算）。
3. 响应为 JSON 数组（与列表无分页参数的旧形态一致）；无在售商品 → `[]`。

### AC13 — `/me/shop/recommendations` 加 `profileSummary` `[L0]` `[L1]`（D-17、就绪度 I10、PRD A1 / A9）
1. `RecommendationView` **末尾追加** `ProfileSummary profileSummary`：`record ProfileSummary(String petName, String species, String sizeClass, String ageStage, boolean weightMissing)`：
   - `species` ∈ `CAT | DOG | UNIVERSAL`（`PetType.OTHER` → `UNIVERSAL`，同 `speciesOf`）；
   - `sizeClass` ∈ `SMALL | MEDIUM | LARGE`，体重未填为 null（`ProfileFacts.bodySize()`）；
   - `ageStage` ∈ `PUPPY | ADULT | SENIOR`，生日缺失为 null（`ProfileFacts.ageStage()`）；
   - `weightMissing = pet.getWeightKg() == null`。
2. 取值只来自 `factsOf(pet)` 已算出的 `ProfileFacts`（与推荐理由同一份事实，不重复推算）；**App 用这些枚举按 PRD §6 A1 / A9 拼本地化文案**，后端不拼句子。
3. 未建档（`missing = "PROFILE"`）→ `profileSummary = null`；**静默期**（`silence.isSilenced`）→ `profileSummary = null`（不能让客户端从摘要推断出静默态，同现有注释 :90-95 的隐私考虑）。
4. 每条 `Item.reason` 照旧下发（PRD：接口仍返回，App 不用）；`RecommendationView.empty(...)` 等工厂补参数；5 处构造调用点（主代码 + 测试）同步。

### AC14 — 回归 `[L0]` `[L1]`
1. `./mvnw -B clean package -DskipTests` 绿。
2. 按设计打破并改断言：`ShopBannerControllerTest.delistedProductIsServedAsNotTappable`（AC6.3）、`ShopProductEndpointIntegrationTest` 下架 404 → 410（AC8.4）、`ShopProductQueryServiceTest` 详情 mock、`AdminShopBannerStateTest` 若依赖 `findFirstByActiveTrue...` 的 mock 改为 mock `ShopBannerQueryService`、`MeRepurchaseControllerTest` / 推荐相关测试补 `profileSummary` 构造参数。
3. 既有 `AdminShopBannerTargetTest` 五条用例全部保留通过（新增 COUPON / 排期用例）。

## Tasks / Subtasks

- [x] **T0 开工核对**：对照 2.1 / 2.2 **已提交代码**核对 `ShopCoupon` 字段名（`acquireType / status / issueStartAt / issueEndAt / totalQuantity / issuedCount / publicToken`）、`ShopCouponRepository`、promo 组件名；无 `V20261105_1645 / _1650` 撞号；有出入先改本文件并在 Completion Notes 记一句
- [x] **T1 迁移 + 实体**（AC1 ~ AC3）
- [x] **T2 banner**（AC4 ~ AC7）：`ShopBannerQueryService`、两个端点、`SecurityConfig` 一条、后台 service 扩展 + messages
- [x] **T3 下架 410**（AC8）：仓库方法、`ErrorTypes` / `AppException` 新工厂
- [x] **T4 评价**（AC9 ~ AC11）：仓库条件与游标、`AdminShopReviewService`、`/reviews` 扩展、详情摘要
- [x] **T5 top 与推荐摘要**（AC12、AC13）
- [x] **T6 测试**：L0 全写并跑绿；L1 写好能编译（云端不跑）
- [x] **T7** `./mvnw -B clean package -DskipTests` + 指定 L0；Completion Notes 标「**L1 待本地验收**」

## Dev Notes

### 现状（改动前必读，2026-11-05 核 `dev_1.4.2` @ 2c507755）

路径前缀 `B/` = `petgo-backend/src/main/java/com/tailtopia/`，`R/` = `petgo-backend/src/main/resources/`。

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `R/db/migration/V20261030_0330__shop_banner_token_and_target.sql` | `ck_shop_banners_target_type IN (NONE,PRODUCT,CATEGORY,EXTERNAL)`；`ck_shop_banners_target_value`（NONE ⇔ 值为空）；注释「轮播仍不做、领券不加」 | 新迁移 DROP + ADD（不改本文件，已应用） | `ck_shop_banners_target_value` |
| `B/shop/domain/BannerTargetType.java` | 4 值，注释「领券没有对应功能」 | 加 `COUPON` | — |
| `B/shop/domain/ShopBanner.java` | 无排期列；`retarget`（:113-117）保证 NONE ⇔ 值为空 | 加排期 | `retarget` 不变式 |
| `B/shop/web/ShopBannerController.java` | 类级 `@RequestMapping("/api/v1/shop/banner")`；`current()`（:53-75）取 `findFirstByActiveTrueOrderBySortWeightDescIdDesc`，无 banner / 拼不出 URL → 204；商品下架 → 降级 NONE | 拆成两个方法级路径；都走 `ShopBannerQueryService` | 204 语义、`ShopBannerView` 字段名 |
| `B/shop/dto/ShopBannerView.java` | `(imageUrl, imageW, imageH, bannerId, targetType, targetValue)` | 复用，不改 | 字段名 |
| `B/shop/repository/ShopBannerRepository.java` | `findFirstByActiveTrueOrderBySortWeightDescIdDesc`（:20）、`findAllByOrderBySortWeightDescIdDesc`（:23） | 新增候选查询（active + 排期，`Pageable` 取 30） | 原方法保留（不再被 App 路径调用） |
| `B/admin/shop/service/AdminShopBannerService.java` | `liveId()`（:52-56）刻意调 App 同一方法（注释强调同源）；`stateOf`（:64-69）；`target(form)`（:158-193）校验 PRODUCT / CATEGORY / EXTERNAL；`isHttpsUrl`（:195-205） | AC7 | 同源承诺；既有校验 |
| `B/admin/shop/dto/ShopBannerForm.java` | `imageKey / imageW / imageH / sortWeight / targetType / targetValue` | 加 `startAt / endAt` | — |
| `B/shop/service/ShopProductQueryService.java` | `detail`（:181-183）`findByPublicTokenAndActiveTrue` → 不存在与下架都 404 | AC8 | 列表 / 分页 / 搜索行为 |
| `B/shop/repository/ShopProductRepository.java` | `findByPublicTokenAndActiveTrue`（:18）、`findActivePublicTokens(tokens)`（:25）、`findByActiveTrueOrderBySortWeightDescIdDesc`（:28） | 加 `findByPublicToken` | — |
| `B/shop/web/ShopProductController.java` | `GET /api/v1/shop/products`（:58-81，无分页参数返回数组的旧版兼容分支）、`GET /{token}`（:83-87） | 加 `GET /top` | 旧版兼容分支 |
| `B/shop/dto/ShopProductDetailView.java` | 16 字段，无评价 | 末尾加 `reviewSummary` | 字段名 |
| `B/shop/review/web/ShopReviewController.java` | `GET /api/v1/shop/products/{token}/reviews`（:40-57）：商品不存在或下架 404；`limit` ≤ 20；返回 `ProductReviewsView(total, averageRating, items)`，空态 `empty()` | AC10 | 游客可访问、不下发评价者身份 |
| `B/shop/review/repository/ShopReviewRepository.java` | 列表 / 计数 / `AVG` 只按 `PUBLISHED`（:17-29） | 加 `hidden_at IS NULL` + 游标 | 无评价 `AVG` 返回 null |
| `B/shop/review/dto/ShopReviewView.java` | `(long id, rating, content, imageUrls, createdAt, reviewStatus)`——**下发自增 id** | 不改（见「待确认」3） | — |
| `R/db/migration/V20260818_0553__init_shop_reviews.sql` | `ix_shop_reviews_product (product_id, created_at DESC) WHERE review_status='PUBLISHED'` | 新增带 `hidden_at IS NULL` 的部分索引 | 旧索引保留 |
| `B/shop/shipping/domain/ShippingSettings.java` | 单行（`Short id`，:17-19）：免运门槛、退货地址三列 | 加 `reviewDisplayEnabled` | — |
| `B/config/service/PlatformConfigService.java` | 只有强类型单行配置（pricing / pawcoin / feedRank / supportContact / tiers），**无键值读法** | 不用 | — |
| `B/shop/repurchase/web/MeRepurchaseController.java` | `GET /me/shop/recommendations`（:61-64）→ `ProfileRecommendationService.recommendFor` | 不改控制器 | 与复购同包，勿连带删（PRD FR-136） |
| `B/shop/repurchase/service/ProfileRecommendationService.java` | `recommendFor`（:80-99）：未建档 `empty(null, true, "PROFILE")`；静默期 `empty(name, false, "NONE")`；`recommend(facts)`（:102-116）；`factsOf`（:208-216）；词表 `speciesWord / ageWord / sizeWord`（:180-205） | 组装 `profileSummary` | 静默期不泄露健康信号；理由照旧下发 |
| `B/shop/repurchase/dto/RecommendationView.java` | `(degraded, missing, petName, items)`；`empty(...)` | 末尾加 `profileSummary` | — |
| `B/shared/error/ErrorTypes.java` / `AppException.java` | `BASE = "https://petgo/errors/"`；无 410 工厂 | 新 `SHOP_PRODUCT_DELISTED`、`productDelisted` | — |
| `B/shared/security/SecurityConfig.java` | `GET /api/v1/shop/products/**` permitAll（:267-268）；`GET /api/v1/shop/banner` permitAll（:272）；`/api/v1/me/shop/**` USER（:369） | 加 `/api/v1/shop/banners` | 其余顺序 |

### 拍板点（按建议实现，Completion Notes 复述）

- **① 评价展示开关放 `shipping_settings.review_display_enabled`**：架构所说的 `platform_config` 键值表不存在；`shipping_settings` 是现成的电商全局单行配置，加一列成本最低，后台线可直接改。
- **② 旧 `/shop/banner` 改为「新列表的第一张」**：与新端点同源，排期与失效规则对旧版 App 同样生效；`COUPON` 对旧版降级为不可点。epics「行为不变」理解为「形状与 204 语义不变」。
- **③ `/reviews` 在开关关闭时仍返回均分与条数**：旧版 App 详情页靠这个端点显示「★ 4,8 · 24 ulasan」，开关只控制列表。
- **④ banner 券只允许 `CLAIM` 类**：PRD 已删「只在 banner 里领的券」，banner 是可领取券的一个入口。
- **⑤ 下架 = `active=false` 一律 410**：库里没有「曾上架」标记，从未上架的草稿商品同样返回 410（token 不可枚举，暴露面可忽略）。

### 实现提示

- `liveBanners` 骨架：
  ```java
  public List<LiveBanner> liveBanners(Instant now) {
      List<ShopBanner> cands = banners.findLiveCandidates(now, PageRequest.of(0, 30));
      Set<String> onSale = products.findActivePublicTokens(tokensOf(cands, PRODUCT));
      Set<String> claimable = coupons.findClaimableTokens(tokensOf(cands, COUPON), now);
      return select(cands, onSale, claimable, imageUrls::publicUrl, 6);   // 纯函数，L0 单测
  }
  ```
  `tokensOf` 为空时不发查询（直接空集）。
- 候选查询 JPQL：`WHERE b.active = true AND (b.startAt IS NULL OR b.startAt <= :now) AND (b.endAt IS NULL OR b.endAt > :now) ORDER BY b.sortWeight DESC, b.id DESC`。
- 评价游标：复用 `shared.paging.KeysetCursor`（`Base64Url("<epochMicros>:<id>")`，**微秒**——毫秒截断会跳过同毫秒评价），解析失败按首页（同 `OrderCenterCursor` / social 列表的坏游标口径）。〔6.1 实现对齐〕
- `ProfileSummary` 由 `ProfileRecommendationService.recommend(facts)` 内组装（`facts` 已在手）；`recommendFor` 的两个 `empty` 分支传 null。
- 410 工厂：`new AppException(HttpStatus.GONE, ErrorTypes.SHOP_PRODUCT_DELISTED, detail)`，`ErrorTypes.SHOP_PRODUCT_DELISTED = URI.create(BASE + "product-delisted")`（P-5 后缀）。

### 架构约束

- D-16：banner 排期、COUPON 目标、`/shop/banners` 最多 6 张、过滤 active + 排期 + 目标有效、CATEGORY → TOKO_HOME、旧端点保留、领券已领 409（2.2）。
- D-17：下架 410 / 不存在 404；`hidden_at`；均分与条数只算已发布未隐藏；评价分页；开关默认关；`hideReview` 为服务层；`/me/shop/recommendations` 加 `profileSummary`；`/shop/products/top`。
- P-5：`product-delisted`。P-8：⑤ ⑥ 两条迁移各管一件事（开关列与评价同属「评价展示」，放 ⑥）。
- 护栏：不引新依赖 / 缓存；对外标识用 `public_token`；`ddl-auto=validate`；日志不记评价正文。

### 与前后 story 的边界（本 story 不做）

- **2.2**：领券接口（含 `source=BANNER`、`already-claimed` 409）、`promo` 计算本身、「给你的券」。
- **6.2**：订单列表 / 详情扩展、复购下线（同在 `shop/repurchase` 包，6.2 只停复购卡与扫描，`/me/shop/recommendations` 不动）。
- **8.1 / 8.2**：App 轮播（`PageView`）、`toko_banner_*` 埋点 `position`、B13 下架页、评价列表与「查看全部」页、栏头文案拼装。
- 后台线：banner 排期与券目标表单、多张「生效中」展示、评价隐藏按钮与展示开关页面。

### 测试

**L0（云端可跑，纯单元）**
- `shop/service/ShopBannerQueryServiceTest`：`select(...)` 纯函数——排期内外（由仓库候选保证，另测 `ShopBanner.isScheduledAt` 边界：`start == now` 有效、`end == now` 无效）、商品不在售剔除、券不可领剔除、CATEGORY → `TOKO_HOME` 且值为空、URL 为空剔除、上限 6、顺序保持；PRODUCT / COUPON 均无时不调批量查询。
- `shop/web/ShopBannerControllerTest`（改）：旧端点取第一张、COUPON 降级 NONE、CATEGORY 保持、无 banner 204；新端点 `items` 形状、空数组。
- `admin/shop/service/AdminShopBannerTargetTest`（补）：COUPON 合法 / 不存在 / STOPPED / 非 CLAIM / 已过发放期；排期 `start >= end` 拒绝。
- `admin/shop/service/AdminShopBannerStateTest`（核对）：`liveId` 取 `liveBanners` 第一张。
- `shop/service/ShopProductQueryServiceTest`（改）：不存在 404；`active=false` → 410 且 `type` 为 `product-delisted`；在售详情带 `reviewSummary`，开关关时 `items` 为空、开时最多 3 条。
- `shop/review/ShopReviewControllerTest`（新）：开关关 → `items=[]` 且 `total / averageRating` 照给；开关开 → 游标分页、`hasMore`、坏游标按首页；无评价 `averageRating=null`。
- `admin/shop/service/AdminShopReviewServiceTest`：隐藏 / 撤销幂等、不存在 404、审计摘要不含正文、开关读写。
- `shop/review/ShopReviewHiddenTest`：`ShopReview.hide` 幂等不改写时刻、`unhide`。
- `shop/web/ShopTopProductsTest`：`limit` 钳位（0 → 1、99 → 20、缺省 6）、顺序按权重。
- `shop/repurchase/service/ProfileSummaryTest`：猫 / 狗 / 其他物种；无体重 → `sizeClass=null, weightMissing=true`；无生日 → `ageStage=null`；未建档与静默期 `profileSummary=null`。
- `shop/ShopBannerScheduleMigrationTest`、`shop/review/ShopReviewHiddenMigrationTest`（读迁移文本）：约束全集含原 4 值 + `COUPON`、排期 CHECK、`hidden_at` 与部分索引、`review_display_enabled BOOLEAN NOT NULL DEFAULT false`、无 `SMALLINT`。

**L1（需 Docker postgres + redis，云端不跑，待本地验收）**
- `shop/web/ShopBannerEndpointIntegrationTest`（`extends ApiIntegrationTest`）：8 张 active banner（含排期外 1、下架商品 1、领完券 1、CATEGORY 1）→ `/shop/banners` 返回 ≤ 6 张且剔除正确、CATEGORY 为 `TOKO_HOME`；游客 200；旧 `/shop/banner` 返回第一张、COUPON 为 NONE；全不生效 → 新端点 `[]`、旧端点 204。
- `shop/web/ShopProductEndpointIntegrationTest`（改）：下架 410 + `type`；不存在 404；`/top` 游客可用、带 `promo`。
- `shop/review/ShopReviewIntegrationTest`（补）：隐藏后列表与 `total / averageRating` 重算；开关默认关 → 详情 `reviewSummary.items` 空、`/reviews` `items` 空；打开后详情 3 条、`/reviews` 翻页无重无漏；作者「我的评价」仍能看到被隐藏的评价。
- `shop/repurchase/RepurchaseIntegrationTest`（补）：`/me/shop/recommendations` 带 `profileSummary`；未建档为 null。
- 迁移：`ddl-auto=validate` 启动通过；存量 banner 排期为 NULL 仍生效。
- 云端只跑 L0：`./mvnw -B test -Dtest='ShopBannerQueryServiceTest,ShopBannerControllerTest,AdminShopBannerTargetTest,AdminShopBannerStateTest,ShopProductQueryServiceTest,ShopReviewControllerTest,AdminShopReviewServiceTest,ShopReviewHiddenTest,ShopTopProductsTest,ProfileSummaryTest,ShopBannerScheduleMigrationTest,ShopReviewHiddenMigrationTest,MeRepurchaseControllerTest,RepurchaseScanServiceTest,ReviewerNameMaskerTest,ShopProductPagingTest,ShopProductListLegacyShapeTest,AuditActionLabelsTest,ShopSharedFileGuardTest,AdminMessagesExternalizedTest' -Dsurefire.failIfNoSpecifiedTests=false`（复审修复后列表；后 6 个为受影响的既有守卫 / 新增打码单测）（`LANG=C.UTF-8 LC_ALL=C.UTF-8`）。Completion Notes 标「**L1 待本地验收**」。

### Project Structure Notes

- 新文件：`B/shop/service/ShopBannerQueryService`（+ `LiveBanner`）、`B/shop/dto/ShopBannerListView`、`B/admin/shop/service/AdminShopReviewService`、`B/shop/repurchase/dto/RecommendationView.ProfileSummary`（内嵌记录）、`B/shop/dto/ShopProductDetailView.ReviewSummary`（内嵌记录）。
- 接口：`GET /api/v1/shop/banners`（新）、`GET /api/v1/shop/banner`（保留）、`GET /api/v1/shop/products/top`（新）、`GET /api/v1/shop/products/{token}/reviews`（扩展）、`GET /api/v1/shop/products/{token}`（410）、`GET /api/v1/me/shop/recommendations`（加字段）。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-shop.md#Story 6.1]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md#§1.2 第 12 / 14 条、D-16、D-17、P-5、P-8 ⑤⑥、§5.1]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md#§3.6 FR-132、§3.7 FR-133（找商品 / 商品详情）、§3.0 旅程 E 第 2 条、§4 banner 埋点、§6 A1 / A9 / B1 / B13]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html#A1 / A9（推荐栏头「Anjing · dewasa · berat belum diisi」）、B13（下架页，后端须区分下架）、B14（暂无评价）]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-shop.md#S-2]
- [Source: _bmad-output/implementation-artifacts/v1.4.2/cloud-rules-shop.md]

## 待确认 / 发现的规划冲突

1. **评价开关存放位置**：架构 D-17 写「`platform_config` 键 `shop.review.display-enabled`（沿用现有平台配置读法）」，但仓库里没有键值配置表（`PlatformConfigService` 只有强类型单行配置）。本 story 改放 `shipping_settings.review_display_enabled`（迁移 ⑥），需后台线知悉。
2. **旧 `/shop/banner`「行为不变」与新规则冲突**：epics 要求旧端点行为不变，但新增排期与 COUPON 后若完全不变，旧版 App 会看到已过期的 banner 或不认识的领券类型。本 story 让旧端点取新列表第一张并把 COUPON 降级为不可点，形状与 204 语义不变（拍板点 ②）。
3. **评价条目下发自增 id**：现有 `ShopReviewView.id` 是 `shop_reviews.id`（违反「对外不暴露自增 id」护栏），本 story 扩大了它的曝光面（评价列表分页）。未改（改字段会打断旧版 App 与「我的评价」重提流程），建议另立 story 给评价加 `public_token`。
4. **后台「生效中」标记的语义**：后台 banner 页现在只有一张「生效中」（`liveId`），轮播后最多 6 张；本 story 保留 `liveId()`（取第一张）并新增 `liveIds()`，页面升级由后台线完成。
5. **PRD §6 A1 备注「由后端按档案摘要拼」**：本 story 按架构 D-17 只下发枚举（`species / sizeClass / ageStage / weightMissing`），由 App 用 arb 词表拼印尼语 / 英语两份文案；后端拼句只能出一种语言。
6. **下架与草稿不区分**：`shop_products` 无「曾上架」标记，从未上架的草稿商品同样 410（拍板点 ⑤）。

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5（claude-opus-5-5），云端 headless，L0 only。

### Debug Log References

- `./mvnw -B clean package -DskipTests`：BUILD SUCCESS。
- story 列 L0（+ 受影响的 `ShopProductPagingTest`、`ShopProductListLegacyShapeTest`、`AuditActionLabelsTest`、`ShopSharedFileGuardTest`）18 类 107 用例全绿。
- 全量非集成单测（`unit-tests.sh`，747 类）：5971 用例，0 失败 / 0 错误 / 10 skipped（既有）。
- 复审修复后重跑：`clean package -DskipTests` 绿；L0 列表 20 类 118 用例全绿；全量非集成单测 748 类 5977 用例 0 失败 / 0 错误 / 10 skipped；flyway-guard OK。首轮 `AdminMessagesExternalizedTest` 红（`AdminShopReviewService` 两处 `AppException` 未挂码）→ 补 `admin.err.shopReview.notFound / settingsMissing` 三语后转绿。
- `bash scripts/ci/check-flyway-versions.sh origin/main`：OK。

### Completion Notes List

- **L1/L2 待本地验收**：本 story 全部 `[L1]` AC（AC1~AC13 的 L1 半边：`ddl-auto=validate`、迁移落库、HTTP 410 序列化、JPQL 构造表达式与游标查询实跑）未在云端验证。留待本地的测试类：`ShopBannerEndpointIntegrationTest`（新）、`ShopProductEndpointIntegrationTest`（改 410 + 新增 `/top`、`reviewSummary`、`totalCount`）、`ShopReviewIntegrationTest`（新增隐藏重算 / 开关 / 翻页无重无漏）、`RepurchaseIntegrationTest`（新增 `profileSummary`）、`AdminShopListingEndpointIntegrationTest` 与 `Epic1ChainIntegrationTest`（下架详情 404 → 410 按设计改断言）。L2 本 story 无。
- **名字对齐（story 文字 → 实际代码）**：
  - 详情评价摘要与 `/reviews` 分页的组装放在新类 `shop/review/service/ProductReviewQueryService`（story 未命名）；`ShopProductQueryService` 构造器加该依赖，`ShopReviewController` 改为只依赖它 + `ShopProductRepository`。
  - 条数 + 均分合并查询为 `ShopReviewRepository.visibleStats` → 顶层投影 `shop/review/repository/ShopReviewStats`（JPQL 构造表达式）。
  - 评价游标复用 `shared.paging.KeysetCursor`（微秒精度），而非 story 写的 epochMillis 自拼串。
  - `ShopBannerController` 构造器改为 `(ShopBannerQueryService)`（另有包内 `(…, Clock)` 供测试）。
  - 2.1 券字段名与 story 一致（`acquireType / status / issueStartAt / issueEndAt / totalQuantity / issuedCount / publicToken`）；`issue_end_at` 实为 NOT NULL，「为空或 > now」中的空分支恒不成立，照写无害。2.x 无等价方法，新增 `ShopCouponRepository.findClaimableTokens`。
  - 测试类名按 story；新增审计 i18n / 错误码 i18n 进四个 `messages*.properties`。
- **C-9 落地**：评价条目 `ShopReviewView` 末尾加 `nickname`（`AccountQueryService.findActorViews` 取社区昵称后**经 `ReviewerNameMasker` 打码**，见「复审修复」；注销 / 封号 → null）与 `specName`（订单行 `spec_name` 快照），整页各一次批量查；详情加 `reviewListEnabled`（= `reviewSummary.displayEnabled`）；分页信封 `ShopProductPageResponse` 加 `totalCount`（同筛选条件在售总数，四条 count 查询与列表过滤逐字一致；旧版无分页参数的数组形态无法加字段，不变）。商品详情规格 `sellable` 已由 1.1 提供。
- **C-2**：`ErrorTypes.SHOP_PRODUCT_DELISTED = product-delisted` + `AppException.productDelisted`（410）已登记。
- 拍板点 ①~⑤ 照 story 实现：开关放 `shipping_settings.review_display_enabled`；旧 `/shop/banner` = 新列表第一张、COUPON → NONE、CATEGORY 保持；`/reviews` 开关关时照给条数与均分；banner 券只允许 CLAIM（且 ACTIVE、未过 `issue_end_at`；发放期尚未开始的允许绑定，便于预排期）；`active=false` 一律 410。
- 排期非法：`ShopBanner.schedule` 抛 422 并挂码 `admin.err.banner.scheduleInvalid`；后台表单 `ShopBannerForm.startAt/endAt` 为 `Instant`，页面控件与绑定格式由后台线补。
- 旧索引 `ix_shop_reviews_product` 保留；迁移无 SMALLINT；`ck_shop_banners_target_type` 按 V20261030_0330 原 4 值 + COUPON 重建。迁移 ⑤ 额外重写了 `target_type` 的列注释（加 COUPON）。
- `AdminShopBannerStateTest` 的同源守卫改为断言 `liveId()` 调 `liveQuery.liveBanners(` 且不再调 `findFirstByActiveTrue…`（反向断言保留）；`ShopBannerControllerTest.delistedProductIsServedAsNotTappable` 保留方法名、改断言为「整张剔除、轮到下一张」；`AdminShopBannerTargetTest.unknownTypeRejected` 原用 COUPON 作非法值，改用 `TOKO_HOME` / 小写 `coupon`。

#### 复审修复（2026-11-05）

1. **评价公开昵称打码（隐私）**：Google 注册时 `nickname` 初始化为 Google displayName（多为真名），原样公开 = 「真名 + 购买处方粮」对游客可见。新增纯函数 `shop/review/service/ReviewerNameMasker.mask`：「首词 + 末词首字符.」（`Dewi Sartika` / `Dewi Ayu Sartika` → `Dewi S.`；单词名只保留该词；null / 全空白 → null）；分隔按 Unicode 空白（含 NBSP、全角空格、零宽空格），首字符按字素簇取（组合重音、emoji、ZWJ 序列不劈开）。评价出参只用打码值（`ProductReviewQueryService.toPublicViews`），社区原样昵称串不出现在评价接口。L0 `ReviewerNameMaskerTest`（多词 / 单字名 / 空白 / Unicode 空白 / 字素簇边界），`ShopReviewControllerTest` 改为喂原名 `Dewi Ayu Sartika` 断言下发 `Dewi S.`。
2. **审计不再因无操作人跳过**：`AdminShopReviewService.hide / unhide / setDisplayEnabled` 一律 `audit.record`（actor 可为 null，`AdminAuditService` 支持）；`AdminShopReviewServiceTest.nullActorStillAudits` 钉住。

#### 待确认

1. （沿用正文「待确认」1~6，均按拍板点实现。）
2. **评价公开昵称打码格式需产品确认并入决策日志**：C-9 要求下发 `nickname`，与 Story 7.3「不下发评价者身份」冲突；复审后按最保守做法服务端打码为「首词 + 末词首字母.」（对齐 UI 稿「Dewi S.」）。单词名会完整保留（印尼常见单名，可能即真名）；不下发头像与用户 token。
3. **详情摘要查询数**：AC11.3「只多两次查询」——计数 + 均分 1 条、列表 1 条；开关开且有评价时为补 C-9 的昵称 / 规格名再各多 1 条批量查询（共 4 条，无 N+1）。
4. **`totalCount` 的放置**：只加在分页信封（`cursor` / `size` 任一出现时）；不带分页参数的旧数组形态与 `/top` 不带总数。
- **PR#49 CI 修复（2026-11-06）**：`BannerTargetType.COUPON` 新增后后台 banner 列表 / 抽屉按 `admin.shop.banners.target.{type}` 取文案，四份 `i18n/messages*.properties` 缺 `COUPON` 键 → `AdminPagesRenderSmokeTest` 红。已补（zh_CN「领券」/ en·默认「Claim coupon」/ id「Ambil kupon」）。L1 本地已跑绿。

### File List

后端 · 迁移
- `petgo-backend/src/main/resources/db/migration/V20261105_1645__shop_banner_schedule_and_coupon_target.sql`（新）
- `petgo-backend/src/main/resources/db/migration/V20261105_1650__shop_review_hidden_and_display_flag.sql`（新）

后端 · 主代码
- `petgo-backend/src/main/java/com/tailtopia/shop/service/ShopBannerQueryService.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/dto/ShopBannerListView.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/review/service/ProductReviewQueryService.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/review/repository/ShopReviewStats.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/review/service/ReviewerNameMasker.java`（新，复审修复）
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopReviewService.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/domain/BannerTargetType.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopBanner.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/repository/ShopBannerRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/repository/ShopProductRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/web/ShopBannerController.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/web/ShopProductController.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/service/ShopProductQueryService.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/dto/ShopProductDetailView.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/dto/ShopProductPageResponse.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/coupon/repository/ShopCouponRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/review/domain/ShopReview.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/review/dto/ProductReviewsView.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/review/dto/ShopReviewView.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/review/repository/ShopReviewRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/review/service/ShopReviewService.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/review/web/ShopReviewController.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/shipping/domain/ShippingSettings.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/repurchase/dto/RecommendationView.java`
- `petgo-backend/src/main/java/com/tailtopia/shop/repurchase/service/ProfileRecommendationService.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopBannerService.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/shop/dto/ShopBannerForm.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/audit/service/AuditActions.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/error/ErrorTypes.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/error/AppException.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java`
- `petgo-backend/src/main/resources/i18n/messages.properties`、`messages_en.properties`、`messages_id.properties`、`messages_zh_CN.properties`

后端 · 测试（L0 新）
- `shop/service/ShopBannerQueryServiceTest`、`shop/review/service/ReviewerNameMaskerTest`（复审修复）、`shop/web/ShopTopProductsTest`、`shop/review/ShopReviewControllerTest`、`shop/review/ShopReviewHiddenTest`、`shop/review/ShopReviewHiddenMigrationTest`、`shop/ShopBannerScheduleMigrationTest`、`shop/repurchase/service/ProfileSummaryTest`、`admin/shop/service/AdminShopReviewServiceTest`

后端 · 测试（L0 改）
- `shop/web/ShopBannerControllerTest`、`admin/shop/service/AdminShopBannerTargetTest`、`admin/shop/service/AdminShopBannerStateTest`、`shop/service/ShopProductQueryServiceTest`、`shop/service/ShopProductPagingTest`

后端 · 测试（L1，写好能编译，云端不跑）
- `shop/web/ShopBannerEndpointIntegrationTest`（新）、`shop/web/ShopProductEndpointIntegrationTest`、`shop/review/ShopReviewIntegrationTest`、`shop/repurchase/RepurchaseIntegrationTest`、`admin/shop/web/AdminShopListingEndpointIntegrationTest`、`shop/Epic1ChainIntegrationTest`

### Change Log

- 2026-11-05：创建 story 文件（ready-for-dev）。
- 2026-11-05：dev-story 实现（后端，L0 绿），Status → review；名字对齐见 Completion Notes。
- 2026-11-05：复审修复——评价昵称服务端打码（`ReviewerNameMasker`）、评价运营审计不因无 actor 跳过；L0 重跑全绿。
