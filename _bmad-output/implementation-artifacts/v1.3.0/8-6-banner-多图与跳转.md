---
baseline_commit: 5f5982cb
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 8
story: 8-6
ad: [AD-S11]
decisions: [ACT-D, OD-6, UX-S3, C5]
fr: [SHOP-FR-22]
---

# Story 8-6: banner 多图与跳转（AD-S11）

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端 → 前端 → 联调、AC 标 L0/L1/L2、Flyway **时间戳版本号**、`mvn -B clean package`）。
>
> 🔴 **前置：admin 主题 Epic 10 完成。** 本 story 要改后台 banner 页（`/admin/shop/banners`），而 admin Epic 10 的 Story 10.3（B17 Banner）要把它整页迁到**模板 B** 并把「新建/编辑收进抽屉」。Epic 10 之前动它 = Epic 10 迁移时整体返工（架构 delta §1）。代码冻结时 Epic 10 未完成则随第二批整组顺延（SD-16）。
>
> 🔴 **本 story 是一次口径翻案，不只是加两列。** 电商一期把「单图 + 纯展示、不可点、不做轮播」这条产品口径**逐字写死在 6 个地方的注释里**（建表注释、列注释、实体 javadoc、对外 DTO javadoc、仓储方法 javadoc、后台控制器 javadoc、App 模型 doc comment）。AC3 要求**连注释一起改**，并按 ACT-D 留痕。只改代码不改注释，下一个人会照着注释把你的改动改回去。
> 🔴 **对外契约变更（C5 三处）**，且**有老版本兼容陷阱**，见 AC2 与 Dev Notes「老版本 App 会炸在哪」。

## Story

As a 用户，
I want 首页 banner 能看到多张、点得动，
so that 我不会点了没反应，以为 App 坏了。

---

## Context

### 现状一：口径写死在哪几处（AC3 要逐处改的清单，附原文）

**① 建表迁移头注释** —— `petgo-backend/src/main/resources/db/migration/V20260827_1500__init_shop_banner.sql:1-14`：

```
 1: -- Toko 顶部 banner（2026-08-27 产品需求）。
 3: -- 产品口径（拍板时定死，实现按此，勿自行扩展）：
 4: --   · **同一时间只展示一张** —— 表里可以配多条，但 App 只取「已上架 + 权重最高」的那一条。
 5: --     不做轮播：轮播的第二张之后经常没被看到就被划走，收益不抵实现与运营成本。
 6: --   · **纯展示，不可点** —— 本版本不带跳转目标。所以表里没有 target/link 列：
 7: --     🔴 宁可以后加列，也不要现在放一个恒为空的 link 字段 —— 空字段会让下一个人
 8: --     以为「跳转已经做了只是没配」，进而在 App 侧写出永远走不到的分支。
```

**② 表注释（COMMENT ON TABLE）** —— 同文件 `:35`：

```sql
COMMENT ON TABLE  shop_banners IS 'Toko 顶部 banner；同一时间只展示一张（active + 权重最高）';
```

**③ 实体 javadoc** —— `petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopBanner.java:13-27`，关键两段：

```
16:  * <p><b>同一时间只展示一张</b>：表里可以配多条，但取用时只取「已上架 + 权重最高」的那一条
17:  * （见 {@code ShopBannerRepository#pickActive}）。不做轮播 —— 轮播的第二张之后经常
18:  * 没被看到就被划走，收益不抵实现与运营成本。
20:  * <p>🔴 <b>本版本纯展示、不可点</b>，故实体上没有任何跳转目标字段。
21:  * 宁可以后加列，也不要现在放一个恒为空的 link —— 空字段会让下一个人以为
22:  * 「跳转已经做了只是没配」，进而在客户端写出永远走不到的分支。
```

⚠️ `:16-17` 提到的 `ShopBannerRepository#pickActive` **这个方法名在树里不存在**（实际方法名见 ④），注释本身已经过期一次。

**④ 仓储 javadoc** —— `petgo-backend/src/main/java/com/tailtopia/shop/repository/ShopBannerRepository.java:11-23`：

```
11:     /**
12:      * 当前该展示的那一张：已上架 + 权重最高；同权重取后建的。
13:      *
14:      * <p>🔴 <b>返回单个而不是列表</b> —— 产品口径是「同一时间只展示一张」。
15:      * 让它返回列表再由调用方取 first，等于把这条口径散到每个调用点上，
16:      * 早晚有一处忘了取 first 而把全部 banner 都渲染出来。
17:      *
18:      * <p>走部分索引 {@code ix_shop_banners_active_pick}（只覆盖 active 行）。
19:      */
20:     Optional<ShopBanner> findFirstByActiveTrueOrderBySortWeightDescIdDesc();
22:     /** 后台列表：全部 banner（含已下架），按取用顺序排列，运营一眼看出「哪张会生效」。 */
23:     List<ShopBanner> findAllByOrderBySortWeightDescIdDesc();
```

**⑤ 对外 DTO javadoc** —— `petgo-backend/src/main/java/com/tailtopia/shop/dto/ShopBannerView.java:3-16`：

```
 6:  * <p>🔴 <b>不含任何跳转字段</b>：本版本 banner 纯展示、不可点。加一个恒为 null 的
 7:  * link 会让客户端写出永远走不到的分支 —— 要跳转时再加字段，那是明确的契约变更。
```

record 本体 `:16`：`public record ShopBannerView(String imageUrl, Integer imageW, Integer imageH) {}`

**⑥ 后台控制器 javadoc** —— `petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopBannerController.java:36-38`：

```
36:  * <p>🔴 <b>同一时间只展示一张</b>：本页可以配多条，但 App 只取「已上架 + 权重最高」的那条。
37:  * 列表按取用顺序排列，第一条已上架的就是用户会看到的那张 —— 页面上会明确标出来，
38:  * 否则运营配了三条却不知道哪条生效。
```

以及方法体内的行内注释 `:69-70`：

```
69:         // 🔴 标出"当前生效"的那一条：列表里可能有多条 active，但只有第一条会被 App 取到。
70:         //    不标的话运营会以为所有 active 的都在轮播 —— 而本版本根本没有轮播。
```

**⑦ App 模型 doc comment** —— `petgo_app/lib/features/shop/domain/shop_banner.dart:1-6`：

```
 1: /// Toko 顶部 banner（2026-08-27）。
 3: /// 🔴 **纯展示、不可点**：本版本后端不下发任何跳转目标，所以这里也没有 target 字段。
 4: /// 要加跳转时是一次明确的契约变更，不该靠一个恒为 null 的字段先占位 ——
 5: /// 那会让人写出永远走不到的分支。
```

> ✅ **以上 7 处就是 AC3 的清单。** 注释作者已经预判了这次翻案（「要跳转时再加字段，那是明确的契约变更」），所以翻案时把这些话改写成「已经做了、是怎么做的」即可，不是删掉了事。

### 现状二：表结构与索引（全部列）

`V20260827_1500__init_shop_banner.sql:15-33`：

| 列 | 行 | 定义 |
|---|---|---|
| `id` | :16 | `BIGSERIAL PRIMARY KEY` |
| `image_key` | :17 | `VARCHAR(255) NOT NULL` —— OSS objectKey，**非 URL**（签名 URL 禁入库，NFR-5） |
| `image_w` | :18 | `INTEGER`（可空；手填 key 的兜底路径给不出尺寸） |
| `image_h` | :19 | `INTEGER` |
| `active` | :22 | `BOOLEAN NOT NULL DEFAULT FALSE` —— 🔴 默认**未上架**（安全默认，注释 :20-21） |
| `sort_weight` | :24 | `INTEGER NOT NULL DEFAULT 0` —— 越大越优先，同权重按 id 倒序（注释 :23） |
| `created_at` | :25 | `TIMESTAMPTZ NOT NULL DEFAULT now()` |
| `updated_at` | :26 | 同上 |

✅ **确认：没有 `target_type` / `target_value` / `link` / `url` 任何跳转列。**

**部分索引** `:29-33`：

```sql
-- App 每次进 Toko 都会查一次「当前该显示哪张」，走这条索引；
-- 部分索引只覆盖已上架的行 —— 下架的历史 banner 不该占索引体积。
CREATE INDEX IF NOT EXISTS ix_shop_banners_active_pick
    ON shop_banners (sort_weight DESC, id DESC)
    WHERE active = TRUE;
```

⚠️ **这条索引本身在多图下是够用的**（`WHERE active = TRUE` + `(sort_weight DESC, id DESC)` 正是「取全部已上架、按取用顺序」所需）。真正要改的是它的**注释**（`:29-30` 说「查当前该显示哪张」）与**上层的 `findFirst` 语义**（④），不是索引的列定义。**实施时按实际执行计划判断是否需要动索引本身；若不需要动，只改注释，并在 Completion Notes 说明**（AC2）。

**列注释** `:36-40`（`image_key` / `image_w` / `image_h` / `active` / `sort_weight` 各一条）—— 新增两列要按同样密度补注释。

### 现状三：对外端点与 App 渲染链路

**端点** `petgo-backend/src/main/java/com/tailtopia/shop/web/ShopBannerController.java`：
- `@RestController` :21，`@RequestMapping("/api/v1/shop/banner")` :22（**单数**）
- `current()` :48-60 → `ResponseEntity<ShopBannerView>`（**单对象**）
- 🔴 **两条 204 路径**：没有可展示的 banner `:51-53`；CDN base 未配置拼不出 URL `:56-58`。javadoc `:36-46` 详述了为什么是 204 而不是 404 或 200+空对象 —— 「204 让『没有 banner』成为一个明确的、无歧义的状态」。
- 🔴 **对游客放行**（javadoc :16-17，需在 `SecurityConfig` 一并放行）。

**App 链路**：
- 路径常量 `petgo_app/lib/core/network/api_paths.dart:276-278` —— `static const String shopBanner = '$base/shop/banner';`，注释 `:276-277` 写明「无 banner 时后端回 **204 No Content**」
- 仓库 `petgo_app/lib/features/shop/data/shop_repository.dart:50-62` —— `fetchBanner()` :58，🔴 **`dio.get<Map<String, dynamic>>(ApiPaths.shopBanner)`** :60（**按单对象解析**），`ShopBanner.fromJson(resp.data!)` :62；注释 :53-58 明写「照状态码判，不要改成判 data」「拉取失败一律当作没有 banner（返 null）而不是抛错」
- provider `shop_repository.dart:99-105` —— `shopBannerProvider`，🔴 注释 :101-102 说明**刻意不用 autoDispose**
- 模型 `petgo_app/lib/features/shop/domain/shop_banner.dart` —— `kShopBannerFallbackAspect = 3.0` :13（注释 :8-12 解释为什么要固定兜底比例）；`aspect` getter :33-40，🔴 注释 :29-32 明写「与商品图不同，这里**不做区间收敛**：banner 是运营精心裁过的横幅，clamp 会把 4:1 的长横幅压成 1.34」；`fromJson` :42-46
- 页面 `petgo_app/lib/features/shop/presentation/toko_page_v2.dart`：
  - `final banner = ref.watch(shopBannerProvider).asData?.value;` :95
  - 有无 banner 决定顶栏 tone :96-102、胶囊底色 :104-111（`capsuleFill = banner != null ? ShopColors.imageCapsuleScrim : bar.capsule` :111）
  - 无 banner → `Scaffold.appBar` 走实心白条 :143-150；有 banner → 顶栏**整条搬进滚动区** :136-142、:165-176（`_BannerAppBar` :169-172）
  - `_BannerAppBar` 类 :414-543（类 javadoc :414-434），`_scrimOpacity` :456-461，`build` :464-542：`imageHeight = width / banner.aspect` :468、`gradientHeight` :469、`SliverAppBar(pinned: true, expandedHeight: imageHeight…)` :474-490、图 `Image.network(banner.imageUrl, fit: BoxFit.cover, errorBuilder → ColoredBox(ShopColors.ink))` :500-506、**三段遮罩** :509-537（`stops: [0, (minExtent / gradientHeight).clamp(0.0, 1.0), 1]` :531）
  - 🔴 **今天整块没有任何点击手势**：`Image.network` 外没有 `GestureDetector` / `InkWell`；`SliverAppBar` 的 `automaticallyImplyLeading: false` :485。这正是「点了没反应」的成因。

### 现状四：🔴 顶栏三段遮罩与「9.09:1」—— 两个数不是同一口径

色值 `petgo_app/lib/core/theme/shop_tokens.dart`：
- `bannerScrimTop = Color(0xC7000000)` :215（rgba(0,0,0,**.78**)）
- `bannerScrimMid = Color(0x9E000000)` :218（rgba(0,0,0,**.62**)，停靠在**顶栏下沿**）
- `bannerScrimBottom = Color(0x00000000)` :220（全透明）
- `imageCapsuleScrim = Color(0xE02E2742)` :240（rgba(46,39,66,**.88**)，顶栏胶囊压在图上时的底色）

注释 `:198-214` 原文关键两句：

```
205:  /// 实测白色「Shop」压在 banner 亮部只有 <b>2.45:1</b>，低于图形/大字的 3:1 下限。
211:  /// 标题带因此稳定在 ~.66–.70，最坏情形（近白的运营图，L≈.9）白字仍有 3.4:1。
```

注释 `:231-239`（胶囊）原文：

```
233:  /// 🔴 .88 不是"看着深一点"，是按最坏情形算出来的下限：banner 图由运营上传、内容不可控，
234:  ///    亮部可以接近纯白，白字要在那上面仍过 AA（4.5:1）就只能压这么深。
235:  /// 实测（stag banner，1080px 截图取样）：原来的 [onInk12]（白 12%）只有 <b>1.78:1</b>，
236:  ///    购物车图标 2.27:1
```

**「9.09:1」的出处是 commit `cd291b5d` 的标题**：
> `fix(shop): banner 遮罩改三段——罩住标题那一带，白色「Shop」由 2.45:1 提到 9.09:1`

🔴 **这两个数不能混用**：
- **9.09:1** = 该 commit 在**某一张具体的 stag 实测图**上、改成三段后测到的值。
- **3.4:1** = 同一套色值在**最坏情形**（近白运营图，L≈.9）下的推算下限（`shop_tokens.dart:211` 自己写的）。

所以「每一张图都要满足基线 9.09:1」按字面是**不可达的**：近白图上按现有色值算出来只有 3.4:1，要让近白图也到 9.09:1 得把遮罩压到几乎全黑，等于毁掉 banner 主视觉。
👉 **AC8 把它改写成可测口径**，并把这次口径澄清作为交付物之一（见 AC8 与 Dev Notes「9.09 与 3.4 谁是基线」）。

### 现状五：后台页与写路径

`AdminShopBannerController.java`：
- 权限 🔒 **复用商品的码，不另立 banner 码**（javadoc :32-34 说明理由）：`VIEW_AUTH` :43-45（`shop.product_view` 或 `shop.product_edit`）· `EDIT_AUTH` :46-47（`shop.product_edit`）
- 列表 `list(Model)` :65-93 —— `findAllByOrderBySortWeightDescIdDesc()` :68、`liveId` :71-72（第一条 active）、逐行 Map :74-87（含 `live` 标记 :85）、`hasLive` :89、返回 `"admin/shop-banners"` :92
- 图片直传 `uploadImage` :96-108，`folder = "shop-banner"` :101
- 写端点五个，全是 PRG + 本地 `catch (AppException)`（注释 :111-113 说明为什么必须本地 catch）：`create` :114-125 · `update` :127-138 · `activate` :140-151 · `deactivate` :153-164 · `delete` :166-177
- 表单 DTO `ShopBannerForm.java` :12-49 —— `imageKey` / `imageW` / `imageH` / `sortWeight`，**无跳转字段**
- 服务 `AdminShopBannerService.java:20`，**每次变更都写审计**（javadoc :12-17），动作码已备齐 5 个：`AuditActions.java:150`(`SHOP_BANNER_CREATED`) `:152`(`_UPDATED`) `:154`(`_ACTIVATED`) `:156`(`_DEACTIVATED`) `:158`(`_DELETED`)

### 现状六：测试覆盖

- **后端零 banner 测试**：`find petgo-backend/src/test -name "AdminShopBanner*"` 零命中；`ShopBannerView` / `ShopBannerController` 在 `src/test` 下无专属测试。
  ⚠️ `target/surefire-reports/` 里有 `AdminShopBannerStateTest` 的陈旧产物，**源文件已不在树内** —— 别被它误导（实施时确认）。
- **App 侧有两条**：`petgo_app/test/shop/toko_page_v2_test.dart` —— `shopBannerProvider.overrideWith(...)` :43（注释 :42 说明必须 override，否则真 provider 会留未完成 Timer）；`:90`「无 banner（白底）→ 标题与购物车图标是主体色」；`:106`「有 banner（图作背景）→ 前景回到白色」。

### 现状七：站内路由白名单的现成范式

`petgo_app/lib/core/router/deep_link_routes.dart`（142 行）就是为「外部给的目标 → 本地路由」这件事写的：
- `notificationsCenter = '/notifications'` :11 —— 🔴 **未知/缺失映射的安全兜底落点**（类 doc :4：「未知 type 兜底落通知中心，不崩溃」）
- `shellTabRoots = {'/home', '/profile', '/shop', '/me'}` :24 + `isShellTabRoot(location)` :27
- 🔴 :14-23 的大段注释：**分支根只能 `go`，绝不能 `push`** —— push 会 GlobalKey 撞车导致 release 白屏且该 Tab 永久点不进去（bug 20260729）；「**今后新增任何通向 `/shop` 的跳转，一律用 `go`**」
- 该约束由 `petgo_app/test/shop/toko_page_test.dart` 的**源码护栏**机械守门（全量扫 `lib/`），注释 :23 说明护栏按原始文本匹配、不剔注释

主路由表 `petgo_app/lib/core/router/app_router.dart`（991 行，78 个 `GoRoute`）。

### 现状八：ACT-D 留痕落点

`_bmad-output/planning-artifacts/v1.4.0/decision-log.md`（电商一期决策日志，编号 `S-n`）—— **grep `banner|Banner|轮播` 零命中**。也就是说「单图 + 纯展示 + 不做轮播」这条口径**从来没有进过决策日志**，只活在代码注释里（2026-08-27 拍板）。
ACT-D 原文（`PRD-v1.3.0-shop-v2.md:300`）：「在电商一期决策日志补记本版推翻的口径：…**banner 轮播翻案**；…」

---

## Acceptance Criteria

**AC1 · 加列 `target_type` / `target_value`** `[L0]`
**Given** `shop_banners` 今天没有任何跳转列（现状二已逐列确认）
**When** 新增一支 Flyway 迁移
**Then** `target_type VARCHAR(16) NULL`（值域 `NONE` / `INTERNAL` / `EXTERNAL`）+ `target_value VARCHAR(300) NULL`，与 AD-S11 逐字一致 `[L0]`
**And** 迁移文件名为**时间戳版本号** `V<yyyyMMdd_HHmm>__add_shop_banner_target.sql`，取创建时刻 `[L0]`
**And** 值域约束用 `CHECK`，且**按 CLAUDE.md 纪律列全集**（新表新约束，一次写全 3 个值）；⚠️ 若实施时已存在同名约束，必须 `DROP + ADD` 全量重建、值取自**当前树里最后一条重建它的迁移** `[L0]`
**And** 两列各补 `COMMENT ON COLUMN`，与既有列注释同密度（`:36-40` 的范式）`[L0]`
**And** 存量行 `target_type` 回填为 `'NONE'`（语义 = 不可点，与翻案前行为一致），或保持 NULL 并在实体侧按 `NONE` 处理 —— **二选一并在注释里写死**，不要留「NULL 和 NONE 都行」的模糊地带 `[L0]`
**And** `bash scripts/ci/check-flyway-versions.sh origin/main` 通过 `[L0]`

**AC2 · 多图：仓储、端点与索引** `[L0]`
**Given** 仓储今天返回 `Optional<ShopBanner>`（`ShopBannerRepository.java:20`），端点返回单对象（`ShopBannerController.java:49`）
**When** 改为支持多张同时上架
**Then** 仓储增一个**返回列表**的查询（已上架、按 `sort_weight DESC, id DESC`）`[L0]`
**And** 🔴 **新增复数端点 `GET /api/v1/shop/banners` 返回数组**；**既有 `GET /api/v1/shop/banner` 保留不动**（仍返首张 + 两条 204 语义），理由见 Dev Notes「老版本 App 会炸在哪」`[L0]`
**And** 新端点同样**对游客放行**（`SecurityConfig` 一并放行，与既有 `/api/v1/shop/banner` 同理）`[L0]`
**And** 新端点在**一张可展示的 banner 都没有**时返回**空数组 + 200**（不是 204）—— 数组端点的「空」有天然表达，不需要再借状态码；这条与单数端点的 204 口径**刻意不同**，须在 javadoc 里写明为什么 `[L0]`
**And** 拼不出 URL 的行（CDN base 未配置）在数组里**被过滤掉**，沿用单数端点 `:43-46` 的纪律（不把必然显示不出来的空壳交给客户端）`[L0]`
**And** 部分索引 `ix_shop_banners_active_pick`（`:31-33`）：**注释必须改**（`:29-30` 现写「查当前该显示哪张」）；索引**列定义是否需要改由实际执行计划决定** —— 现有 `(sort_weight DESC, id DESC) WHERE active = TRUE` 已能服务「取全部已上架、按取用顺序」；若不改，在 Completion Notes 写明判断依据 `[L0]`

**AC3 · 🔴 口径翻案：7 处注释连同代码一起改（硬 AC）** `[L0]`
**Given** 「单图 / 纯展示 / 不可点 / 不做轮播」写死在现状一列出的 7 处
**When** 本 story 实施
**Then** 下列 7 处的口径表述**全部改写为翻案后的现状**（说明「现在是多图、可点，怎么取、怎么跳」），不是删空：
  ① `V20260827_1500__init_shop_banner.sql:1-14` 头注释 —— ⚠️ **该迁移已被环境应用过，文件本身绝不可改**（改 checksum 启动即失败）。口径改写落在**新迁移**的头注释里，并在新迁移中显式写「本迁移推翻 `V20260827_1500` 头注释 :3-8 的口径」
  ② 表注释 `:35` —— 由**新迁移**执行 `COMMENT ON TABLE shop_banners IS '…'` 覆盖（`COMMENT ON` 是幂等 DDL，不动老文件）
  ③ `ShopBanner.java:13-27` 实体 javadoc（含 `:16-18` 与 `:20-22` 两段）
  ④ `ShopBannerRepository.java:11-20` 的「🔴 返回单个而不是列表」整段
  ⑤ `ShopBannerView.java:6-7` 的「🔴 不含任何跳转字段」整段
  ⑥ `AdminShopBannerController.java:36-38` javadoc + `:69-70` 行内注释
  ⑦ `petgo_app/lib/features/shop/domain/shop_banner.dart:3-5` doc comment `[L0]`
**And** 🔴 **可 grep 验证**：改动后全仓（`petgo-backend/src/main` + `petgo_app/lib` + 新迁移）搜索「同一时间只展示一张」「纯展示」「不可点」「不做轮播」——除**新迁移中显式引用旧口径以说明「已推翻」**的那一处外，**零命中** `[L0]`
**And** ③ 中那个已经过期的 `{@code ShopBannerRepository#pickActive}` 引用（方法名在树里不存在）一并修正为真实方法名 `[L0]`
**And** ⑦ 的注释里那句「要加跳转时是一次明确的契约变更」改写为「跳转已于 V1.3.0 Story 8-6 落地，字段语义见 …」—— **让下一个人读到的是现状，不是一条与代码相反的笔记** `[L0]`

**AC4 · ACT-D 留痕** `[L0]`
**Given** `_bmad-output/planning-artifacts/v1.4.0/decision-log.md` 中**没有任何 banner 条目**（grep 零命中），该口径只活在代码注释里
**When** 本 story 实施
**Then** 在电商一期决策日志中补记一条：原口径（单图 + 纯展示 + 不做轮播，2026-08-27 拍板）、**翻案理由**（SHOP-FR-22：现状看起来能点、点了没反应，属 UJ-1②「点不动/被误导」，运营已提报）、翻案版本（V1.3.0 shop-v2 Story 8-6）、新口径（多图 + 可点，两类跳转目标）`[L0]`
**And** 编号沿用该文件的 `S-n` 体系（**不要用 `SD-n` / `D-n` / `AD-S n`，那是别的主题的号段**）`[L0]`
**And** 若该文件的结构不适合追加（如已定稿封存），改为在 `_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md` 留痕并在决策日志里加一行指针 —— **两者择一，不能都不做** `[L0]`

**AC5 · 站内跳转：App 可达路由白名单** `[L0]`
**Given** `target_type = INTERNAL` 时 `target_value` 是一个 App 内路由 path
**When** 用户点击该 banner
**Then** path 必须命中**白名单**才导航；白名单是一份**显式常量集合**，不是「凡是 `/` 开头就放行」`[L0]`
**And** 白名单落在 App 侧（与 `deep_link_routes.dart` 同一范式与同一目录），🔴 **不要**在后台去校验「这个 path App 认不认」—— 后台不知道用户装的是哪个版本 `[L0]`
**And** 🔴 **目标是 shell Tab 分支根（`/home` `/profile` `/shop` `/me`，`deep_link_routes.dart:24`）时必须用 `go` 而非 `push`** —— 违反会 GlobalKey 撞车、release 白屏、该 Tab 永久点不进去（`deep_link_routes.dart:14-23` 记录的 bug 20260729）。`isShellTabRoot(...)`:27 是现成判据，直接复用 `[L0]`
**And** ⚠️ `petgo_app/test/shop/toko_page_test.dart` 的**源码护栏按原始文本全量扫 `lib/`、不剔注释** —— 新增代码与注释里都不要出现那个被禁的字面量（`deep_link_routes.dart:22-23` 已说明这条陷阱）`[L0]`
**And** 单测覆盖：白名单内 path → 导航到对应路由；白名单外 path → 走 AC7 的兜底 `[L0]`

**AC6 · 站外跳转：域名白名单（配置项）** `[L0]`
**Given** `target_type = EXTERNAL` 时 `target_value` 是一个外部 URL
**When** 后台保存该 banner，或 App 准备打开该链接
**Then** **域名白名单是配置项，不是代码常量**（AD-S11 原文「站外按域名白名单（配置项）」）—— 换域名不用发版 `[L0]`
**And** 🔴 **后台保存时就校验**（运营配错要当场知道，不能等用户点了才发现），校验失败走既有 `catch (AppException) → flash` 通道 `[L0]`
**And** 只接受 **https**（不接受 http、不接受任何非 http(s) scheme）；比对的是**主机名**，不是「URL 里包含该字符串」—— 后者能被 `https://evil.com/?x=tailtopia.id` 绕过 `[L0]`
**And** 单测覆盖至少：白名单内域名放行 · 白名单外域名拒绝 · http 拒绝 · 子域名处置（明确是「精确匹配」还是「后缀匹配」并写进注释）· 形如 `https://evil.com/#tailtopia.id` 的绕过尝试被拒 `[L0]`
**And** 配置项的默认值**不写死任何具体域名在代码里**；默认为空集合时，`EXTERNAL` 类型直接不可配（fail-closed，不是 fail-open）`[L0]`

**AC7 · 站内目标失效 → 回首页并静默上报** `[L0]`
**Given** 运营配的站内 path 在当前 App 版本里不存在（老版本装了新配的 banner），或目标资源已删除
**When** 用户点击
**Then** **回首页**（不是白屏、不是弹错、不是留在原地没反应）`[L0]`
**And** **静默上报**一个埋点事件：只含**枚举与数值**（如失效原因类别、target_type），🔴 **不含 `target_value` 原文**（那是运营填的自由文本，可能带参数）、不含任何个人信息；走既有 `Analytics` 门面与 `scrub`，按钮类事件 id 进 `_allowedButtonIds` 白名单与 `button_ids.dart` 常量 `[L0]`
**And** 「静默」= 不给用户任何错误提示（他点的是一张运营图，看到首页是合理落点）`[L0]`
**And** ⚠️ `target_type = NONE` 的 banner **完全不可点**：不挂手势、不上报、不回首页 —— 与翻案前行为一致，这是存量数据的落点 `[L0]`
**And** 单测覆盖三支：`NONE` 不可点 · `INTERNAL` 白名单外 → 回首页 + 上报一次 · `EXTERNAL` 白名单外 → 拒绝打开 `[L0]`

**AC8 · 🔴 顶栏三段遮罩对比度：每一张图都验（口径须先澄清）** `[L0]` `[L2]`
**Given** 「9.09:1」出自 commit `cd291b5d` 标题（**某一张 stag 实测图**上的测值），而同一套色值在**最坏情形**（近白运营图 L≈.9）下 `shop_tokens.dart:211` 自己写的是 **3.4:1**（见现状四）
**When** 本 story 验收对比度
**Then** 🔴 **第一步是澄清基线口径并写进 `shop_tokens.dart` 的注释**：哪个数是「验收门槛」、哪个数是「最好情形参考」。**不得把 9.09:1 当作每张图的通过线**（近白图上按现有色值不可达，强行达到要把遮罩压到近全黑、毁掉主视觉）`[L0]`
**And** 验收门槛按**可达且有依据**的口径写死，建议采用 `shop_tokens.dart:205` 与 `:233-234` 已在用的两条标准：**顶栏标题/大字 ≥ 3:1**（图形与大字下限，`:205` 的原话）、**胶囊内文字与图标 ≥ AA 4.5:1**（`:234` 的原话）；若产品坚持更高门槛，需**同批调整遮罩色值并重新核算近白图最坏情形**，那是超出本 story 的改动，须停手确认 `[L0]`
**And** 🔴 **每一张上架 banner 都要验，不能只验第一张**（UX-S3 的实质要求）—— 多图轮播后，第 2、3 张同样会成为顶栏文字的背景 `[L2]`
**And** 验收方法可复现：对每张图在 1080px 宽下截屏取样，取**标题带**（状态栏下沿到顶栏中部）与**胶囊区**的最亮像素算对比度；样本至少覆盖一张**近白图**与一张**深色图** `[L2]`
**And** 🔴 **本 story 不改遮罩色值**（`bannerScrimTop/Mid/Bottom` :215/:218/:220 与 `imageCapsuleScrim` :240）—— 它们是 2026-09-03 按最坏情形算出来的下限，改动须另案 `[L0]`
**And** ⚠️ 遮罩今天挂在 `_BannerAppBar`（`toko_page_v2.dart:509-537`），轮播后**必须确保它压在当前显示的那张图之上**、且随轮播切换保持覆盖，不能只在第一张上生效 `[L0]`

**AC9 · 🔴 轮播参数待 OD-6，定值前不写死在代码里** `[L0]`
**Given** OD-6 未定：张数上限、停留时长、是否自动轮播、各图比例不一致时的高度策略
**When** 实现轮播
**Then** 上述四个参数**全部从一处配置读取**（配置项或单一常量模块），**不散落在 widget 里**；改一个值不需要改多个文件 `[L0]`
**And** 每个参数的默认值旁注明「OD-6 待定，当前为默认值」`[L0]`
**And** 🔴 **「比例不一致时的高度策略」尤其不能随手定**：`shop_banner.dart:29-32` 明确写了 banner **不做比例 clamp**（「运营精心裁过的横幅，clamp 会把 4:1 压成 1.34，主视觉直接被裁掉」）。多图比例不同时若按「取最高」会留大片空白、按「取最低」会裁掉内容 —— **两种都有代价，必须由产品拍（OD-6），代码里只留一个可切换的策略点 + 一个标注为默认值的选择** `[L0]`
**And** 单图时的表现与翻案前**逐像素一致**（`kShopBannerFallbackAspect = 3.0` :13 的兜底、`aspect` :33-40 的不 clamp、`imageHeight = width / banner.aspect` :468 的算法都不变）`[L0]`
**And** OD-6 定值后要改哪几行，写进 Completion Notes `[L0]`

**AC10 · 后台 banner 表单可配置跳转目标** `[L0]`
**Then** `ShopBannerForm`（:12-49）增 `targetType` / `targetValue`；表单提供类型选择（三值）+ 目标输入 `[L0]`
**And** 选 `NONE` 时目标输入不可填/被忽略；选 `INTERNAL` / `EXTERNAL` 时目标必填 `[L0]`
**And** `EXTERNAL` 的域名白名单校验在保存时执行（AC6）`[L0]`
**And** 写操作三件套：`@PreAuthorize(EDIT_AUTH)`（沿用 `shop.product_edit`，🔴 **不新增权限码** —— 控制器 javadoc :32-34 已论证过为一个页面引入新码不划算）+ `AdminAuditService.record`（沿用 `AuditActions.SHOP_BANNER_UPDATED` :152 等 5 个既有码，**不新增动作码**）+ **三语 key** `[L0]`
**And** 🔴 审计 detail **不写 `target_value` 原文**（自由文本，可能含 URL 参数）—— 只记「跳转目标已变更」+ 类型 `[L0]`
**And** 后台列表页的「当前生效」标记（`:71-72` `liveId` / `:85` `live` / `:89` `hasLive`）与对应模板文案**必须改**：多图下「只有第一条生效」不再成立，运营需要看到的是「这 N 张都在轮播、顺序是这样」`[L0]`

**AC11 · 契约同改（C5 三处）与老版本兼容** `[L0]`
**Then** 三处同改：① 后端 record `ShopBannerView`（增 `targetType` / `targetValue`）② App DTO `shop_banner.dart` 的 `ShopBanner` + `fromJson`（:42-46）③ 契约 test —— ⚠️ C5 原文写「四处」含 App mock，**mock 子系统已于 `8e85b40d` 整体删除，实为三处** `[L0]`
**And** 🔴 **契约 test 是新建的**：`petgo-backend/src/test` 下今天**没有任何 banner 测试**（`find` 零命中；`target/surefire-reports` 里的 `AdminShopBannerStateTest` 是陈旧产物，源文件已不在树内）。本 story 补第一条 `[L0]`
**And** 契约 test 用**集合相等**断言 JSON 键集合（不是「包含」）`[L0]`
**And** 🔴 **老版本兼容**：既有 `GET /api/v1/shop/banner` 的**响应形状不变**（单对象 + 两条 204），老版本 App 的 `dio.get<Map<String, dynamic>>`（`shop_repository.dart:60`）仍能解析；新增的两个字段对老版本是**多余字段**，不影响解析 `[L0]`
**And** App 侧测试夹具同步：`petgo_app/test/shop/toko_page_v2_test.dart:43` 的 `shopBannerProvider.overrideWith(...)` 与 `:109` 的 `ShopBanner(...)` 内联构造补新字段；既有两条用例（`:90` 无 banner 白底 / `:106` 有 banner 前景回白）**保持绿且语义不变** `[L0]`

**AC12 · L0/L1 全绿** `[L0]` `[L1]`
**Then** `mvn -B clean package` 通过（🔴 必须 `clean`）`[L0]`
**And** `flutter analyze` 零 issue、`flutter test` 全绿（含 `toko_page_v2_test.dart` 与 `toko_page_test.dart` 的源码护栏）`[L0]`
**And** `ddl-auto=validate` 下应用起得来（两个新列与实体映射对得上）`[L1]`
**And** 集成测试：0 张 / 1 张 / 3 张已上架 banner 三种情况下，新端点返回的数组长度与顺序正确（`sort_weight DESC, id DESC`）；未上架的不出现 `[L1]`

**AC13 · 模拟器验收** `[L2]`
**Given** staging 配了 3 张已上架 banner（其中一张 `NONE`、一张 `INTERNAL` 指向有效路由、一张 `EXTERNAL` 指向白名单内域名），另配一张 `INTERNAL` 指向**不存在的路由**
**When** 在 Android 模拟器上打开 Toko
**Then** 三张轮播可见、顺序符合权重 `[L2]`
**And** `NONE` 那张点了**没有任何反应且不上报**；`INTERNAL` 有效的那张跳到正确页面；`EXTERNAL` 那张拉起外部浏览器 `[L2]`
**And** 指向不存在路由的那张 → **回首页**、无错误提示、埋点有一条 `[L2]`
**And** 🔴 **逐张滚动顶栏，每一张图上的标题与胶囊文字都清晰可读**，按 AC8 澄清后的门槛逐张取样核对（至少含一张近白图）`[L2]`
**And** 单张 banner 的场景下表现与翻案前一致（高度、滚动收起、白色顶栏空态）`[L2]`

---

## Tasks / Subtasks

- [ ] **T0 · 开工前三件事**
  - [ ] 确认 admin Epic 10 的 Story 10.3（B17 Banner，模板 B + 抽屉）已完成，拿到重构后的模板与控制器实际形态
  - [ ] **先做 AC8 的口径澄清**（9.09 vs 3.4），拿到产品或设计的一句确认再动遮罩相关的任何验收
  - [ ] **先做 AC4 的 ACT-D 留痕**（写文档，零代码），把翻案这件事落在纸上再改代码

- [ ] **T1 · 迁移**（AC1/AC3①②）
  - [ ] 新建 `V<yyyyMMdd_HHmm>__add_shop_banner_target.sql`
  - [ ] `ALTER TABLE shop_banners ADD COLUMN target_type VARCHAR(16)` + `ADD COLUMN target_value VARCHAR(300)` + `CHECK` 列全 3 值 + 存量回填 `'NONE'`
  - [ ] `COMMENT ON COLUMN` ×2（与 `:36-40` 同密度）
  - [ ] 🔴 `COMMENT ON TABLE shop_banners IS '…'` **覆盖** `V20260827_1500:35` 的表注释（AC3②）
  - [ ] 迁移头注释写清：本迁移推翻 `V20260827_1500` 头注释 `:3-8` 的「单图 + 纯展示」口径；新口径是什么；留痕在哪（指向 AC4 的决策日志条目）
  - [ ] 🔴 **绝不修改 `V20260827_1500__init_shop_banner.sql` 本身**（已被环境应用，改 checksum 启动即失败）
  - [ ] `bash scripts/ci/check-flyway-versions.sh origin/main`

- [ ] **T2 · 后端实体 / 仓储 / DTO / 端点**（AC1/AC2/AC3③④⑤/AC11）
  - [ ] `ShopBanner.java`：加 `targetType`（枚举，`@Enumerated(EnumType.STRING)`，len16）+ `targetValue`（len300）；`apply(...)`:84-89 扩展承载
        ⚠️ `apply` 的 javadoc `:80-82` 已警告「`imageW/imageH` 同为 `Integer`，**传反不会编译报错**」—— 再加两个 `String` 参数会让这个陷阱更宽，考虑改用参数对象或命名清晰的顺序，并在 javadoc 里更新那段警告
  - [ ] 新建 `BannerTargetType` 枚举（`NONE` / `INTERNAL` / `EXTERNAL`），javadoc 说明三值语义与 fail-closed 纪律
  - [ ] `ShopBannerRepository`：加返回 `List<ShopBanner>` 的已上架查询；🔴 改写 `:11-20` 那段「返回单个而不是列表」的 javadoc（AC3④），保留 `findFirst...`:20 供单数端点用
  - [ ] `ShopBannerView`：增两个分量；🔴 改写 `:6-7`（AC3⑤）
  - [ ] `ShopBannerController`：**保留** `current()`:48-60 原样（含两条 204）；**新增** `GET /api/v1/shop/banners` 返数组，javadoc 写明「为什么数组端点用空数组+200 而不是 204」与「为什么保留单数端点」
  - [ ] `SecurityConfig` 放行新端点（与 `/api/v1/shop/banner` 同处）

- [ ] **T3 · 站外域名白名单（后端校验）**（AC6/AC10）
  - [ ] 白名单做成配置项（`@ConfigurationProperties` 或 `application.yml` + env），**默认空集合 = `EXTERNAL` 不可配**
  - [ ] 校验器：只接 https · **比对主机名**（`URI.getHost()`，不是字符串 contains）· 明确精确匹配 or 后缀匹配并写进注释
  - [ ] 接进 `AdminShopBannerService.validate(...)`（该方法在 `AdminShopBannerService.java:31`/`:44` 的 create/update 里已被调用），失败抛 `AppException.validation(...).code("admin.err.banner.<原因>")`
  - [ ] 单测覆盖 AC6 列的 5 类

- [ ] **T4 · 后台表单与列表页**（AC10/AC3⑥）
  - [ ] `ShopBannerForm`：加 `targetType` / `targetValue` + getter/setter
  - [ ] 模板（Epic 10 重构后的实际文件）：类型下拉 + 目标输入；`NONE` 时目标输入禁用/忽略
  - [ ] 🔴 改写 `AdminShopBannerController.java:36-38` javadoc 与 `:69-70` 行内注释（AC3⑥）
  - [ ] 🔴 **改「当前生效」的展示语义**：`liveId`:71-72 / `live`:85 / `hasLive`:89 与模板文案 —— 多图下要展示「这 N 张在轮播、顺序如下」而不是「只有这一张生效」
  - [ ] 审计：沿用 `SHOP_BANNER_UPDATED` 等 5 个既有码；detail 不写 `target_value` 原文
  - [ ] 三语 key 三包同批（`i18n/messages_{zh_CN,en,id}.properties`）

- [ ] **T5 · App 模型与仓库**（AC11/AC3⑦）
  - [ ] `shop_banner.dart`：`ShopBanner` 加 `targetType`（本地枚举，**未知值降级为 `NONE`** —— 后端加了新值而老 App 不认时不能崩）+ `targetValue`；`fromJson`:42-46 同改
  - [ ] 🔴 改写 `:3-5` 的 doc comment（AC3⑦）；`kShopBannerFallbackAspect`:13 与 `aspect`:29-40 **不动**
  - [ ] `api_paths.dart`：加复数路径常量（保留 `shopBanner`:278）
  - [ ] `shop_repository.dart`：加 `fetchBanners()` 返 `List<ShopBanner>`，沿用 `:56-58` 的「失败一律当作没有、返空、不抛错」纪律；provider 加复数版（🔴 同样**不用 autoDispose**，理由见 `:101-102`）
  - [ ] ⚠️ 老 `fetchBanner()`:58 与 `shopBannerProvider`:103 是留是删由 T6 的实现决定；删之前确认无其它调用点

- [ ] **T6 · App 轮播与遮罩**（AC8/AC9）
  - [ ] `toko_page_v2.dart`：`_BannerAppBar`:435-543 由单图改为多图
  - [ ] 🔴 **遮罩必须压在当前显示的那张图之上**：`:509-537` 的 `Positioned` + `Opacity` + 三段 `LinearGradient` 结构保留，确保它在轮播容器**之上**而不是跟着某一张图走
  - [ ] 🔴 `_scrimOpacity`:456-461 与 `stops: [0, (minExtent / gradientHeight).clamp(0.0, 1.0), 1]`:531 的算法**不改**
  - [ ] 轮播四参数（AC9）收进一处配置/常量模块，各注「OD-6 待定」
  - [ ] 高度策略留一个可切换的策略点；单图路径**逐像素等价于**现状（`imageHeight = width / banner.aspect`:468）
  - [ ] `Image.network` 的 `errorBuilder`:505（`ColoredBox(ShopColors.ink)`）对每一张都要有
  - [ ] 🔴 **不改** `shop_tokens.dart` 的 `bannerScrimTop`:215 / `Mid`:218 / `Bottom`:220 / `imageCapsuleScrim`:240 色值；只按 AC8 改**注释里的基线口径说明**

- [ ] **T7 · App 点击与白名单**（AC5/AC6/AC7）
  - [ ] 站内白名单常量：与 `deep_link_routes.dart` 同目录同范式；复用 `isShellTabRoot(...)`:27 判 `go` / `push`
  - [ ] 🔴 shell Tab 分支根一律 `go`（`deep_link_routes.dart:14-23`）；⚠️ 代码与注释里都不要出现被源码护栏禁掉的那个字面量（`:22-23`）
  - [ ] 站外：App 侧再校一次域名（后台校验是第一道，客户端是第二道；配置随端点下发或与后端同源，实施时确认下发方式）
  - [ ] 失效兜底：回首页 + 静默上报；埋点属性只含枚举与数值，**不含 `targetValue`**；按钮 id 进 `_allowedButtonIds` 与 `button_ids.dart`
  - [ ] `NONE` 完全不挂手势

- [ ] **T8 · 测试**（AC11/AC12）
  - [ ] **新建**后端契约测试（今天零覆盖）：`ShopBannerView` JSON 键集合**集合相等**断言
  - [ ] 后端集成测试：0/1/3 张的数组返回与顺序；未上架不出现；CDN base 缺失的行被过滤
  - [ ] 域名白名单单测 5 类（AC6）
  - [ ] Flutter 单测：白名单内/外站内跳转、`NONE` 不可点、失效兜底上报一次；`toko_page_v2_test.dart:43,:109` 夹具补字段，`:90` `:106` 两条既有用例保持绿
  - [ ] `flutter analyze` + `flutter test`；`mvn -B clean package`

- [ ] **T9 · 云端执行须知**
  - [ ] 云端只跑 L0：`mvn -B clean package` + `flutter analyze` + `flutter test`
  - [ ] AC12 的 `validate` 与集成测试是 L1、AC8 后半与 AC13 是 L2，留本地；Completion Notes 标「L1/L2 待本地验收」
  - [ ] 🔴 **前后端必须同批部署**：新端点 + App 新解析路径是一组

---

## Dev Notes

### 🔴 老版本 App 会炸在哪（AC2 / AC11 的由来）

架构 delta §5 的契约变更清单里**没有 banner 这一条**，所以这个兼容陷阱在规划层是没被点出来的。实测：

`petgo_app/lib/features/shop/data/shop_repository.dart:60`
```dart
final resp = await dio.get<Map<String, dynamic>>(ApiPaths.shopBanner);
```

泛型参数是 `Map<String, dynamic>`。如果把 `/api/v1/shop/banner` 的响应从**对象**改成**数组**，已经装在用户手机上的所有版本在这一行会拿到 `List` 而不是 `Map` —— dio 的类型转换会抛，被 `:56-58` 那句「拉取失败一律当作没有 banner」吞掉，结果是**所有老版本 App 的首页 banner 集体消失**，而且悄无声息（不报错、不上报）。

所以 AC2 的做法是：**老端点原样不动，新增复数端点。**
- `GET /api/v1/shop/banner` → 单对象 + 两条 204，老版本继续用，语义不变；
- `GET /api/v1/shop/banners` → 数组，新版本用。

代价是两个端点并存一段时间。收益是**零破坏**。等最低支持版本升上来再退役单数端点（与 K5「商品列表分页对老版本仍返全量」是同一个判断）。

⚠️ 顺带：新增两个字段（`targetType` / `targetValue`）到 `ShopBannerView` 对老版本是**多余字段**，Dart 的 `fromJson` 只读它认识的键，不影响解析 —— 这部分是安全的。

### 🔴 9.09 与 3.4 谁是基线

这是本 story 里最容易把人带沟里的一条。把两个数并排放：

| 数 | 出处 | 含义 |
|---|---|---|
| **2.45:1** | `shop_tokens.dart:205` | 改三段**之前**，白色「Shop」压在 banner 亮部的实测值。**低于 3:1 下限** = 当时的 bug |
| **9.09:1** | commit `cd291b5d` 标题 | 改三段**之后**，在**那一张实测图**上测到的值 |
| **3.4:1** | `shop_tokens.dart:211` | 同一套色值在**最坏情形**（近白运营图，L≈.9）下的推算下限 |
| **4.5:1** | `shop_tokens.dart:234` | 胶囊内白字要过 AA 的门槛，`imageCapsuleScrim` 的 .88 就是按它倒推的 |
| **1.78 / 2.27** | `shop_tokens.dart:235-236` | 胶囊改之前，白字 / 购物车图标的实测值（双双不达标，即 2026-09-03 产品反馈的「有毛玻璃框但白字看不清」） |

epics UX-S3 写的是「基线 9.09:1，每张图都要满足」。按字面执行**不可能**：9.09 是好情形下的测值，3.4 是同一套色值下的坏情形下限，**同一份代码同时产出这两个数**。要求每张图都 ≥9.09 等于要求遮罩在近白图上也压到近全黑。

本 story 的处理是 AC8：**先澄清口径、写进注释，再按可达门槛逐张验。** 推荐门槛直接用代码里已经在用的两条（标题/大字 ≥3:1，胶囊文字/图标 ≥4.5:1 AA）——它们有出处、可达、且正是当初定色值时用的标准。

**UX-S3 的实质要求（「不能只验第一张」）本 story 完整保留**，那一条是对的：多图之后第 2、3 张同样会成为顶栏文字的背景，而运营上传的图内容不可控。

### 注释翻案为什么是硬 AC

现状一列的 7 处注释不是随口写的，它们全都带着**理由**（「宁可以后加列，也不要现在放一个恒为空的 link」「让它返回列表再由调用方取 first，等于把这条口径散到每个调用点上」）。这种注释的杀伤力在于：它说服力很强，而且**读的人不会去查它是不是还成立**。

如果本 story 只加了列、改了代码，却把 `ShopBannerView.java:6-7` 那句「🔴 不含任何跳转字段：本版本 banner 纯展示、不可点」留在原地，下一个维护者打开这个文件会看到**代码和注释互相矛盾**，然后按注释办事。

本仓已经在别处踩过同类坑（`ShopBanner.java:16-17` 引用的 `ShopBannerRepository#pickActive` 这个方法名，在树里根本不存在 —— 注释已经过期一次了，没人发现）。

所以 AC3 给了一条可机械验证的判据：**grep 四个关键词，除新迁移中显式说明「已推翻」的那一处外零命中。**

### 站内跳转为什么白名单在 App 侧

后台不知道用户装的是哪个版本。同一个 path，1.3.0 有、1.2.0 没有。在后台校验只能校验「后台知道的那份路由表」，而那份表和用户手机上的对不上。

正确的分工是：
- **后台**校验「格式对不对」（是不是 `/` 开头的 path、长度是否超 300）；
- **App** 校验「我这个版本认不认这个 path」，不认就走 AC7 的兜底回首页。

这正是 `deep_link_routes.dart` 的既有设计（类 doc :3：「后端只下发 `type + deepLinkToken`，**映射规则在客户端**」；:4：「未知 type 兜底落通知中心，不崩溃」）。照抄它，不要新发明。

### `go` 与 `push` 那个坑

`deep_link_routes.dart:14-23` 记的是一次 release 白屏事故：

> 分支根只能 `go`（切分支），绝不能 `push`：push 会在同一匹配链里二次构建 StatefulShellRoute → GlobalKey 撞车 → release 白屏，且此后该分支 `goBranch` 持续抛异常、**Tab 永久点不进去**（bug 20260729-纪念日通知白屏）。
> …今后新增任何通向 `/shop` 的跳转，一律用 `go`。

banner 跳转是典型的「新增通向 Tab 根的跳转」。且这类 bug **debug 下可能不复现、release 下才炸** —— 所以它必须写成 AC（AC5），不能靠实施者记得。

现成判据 `isShellTabRoot(location)`:27 直接用，别自己再列一遍那四个字符串。
⚠️ 另有一条隐蔽陷阱：`toko_page_test.dart` 的源码护栏**全量扫 `lib/`、按原始文本匹配、不剔注释**（`:22-23` 自承），所以连注释里都不能写出那个被禁的字面量。

### 开放项（实施时确认，本 story 不擅自决定）

1. **站外域名白名单的下发方式。** AC6 要求它是配置项。但 App 侧要不要也拿到这份白名单（做第二道校验），涉及是否新增一个下发端点或挂在既有配置端点上。**实施时确认**；在定下来之前，App 侧可以先只做 scheme 校验（只接 https）+ 依赖后台已拒绝非法域名。
2. **单数端点什么时候退役。** AC2 保留了 `/api/v1/shop/banner`。退役时机取决于最低支持版本，与 K5（商品列表分页）是同一个判断。**本 story 不退役**，在 Completion Notes 记一条待办。
3. **`target_value` 要不要允许带查询参数。** varchar(300) 足够放带参数的 URL，但埋点纪律要求不上报原文（AC7）、审计纪律要求不写原文（AC10）。若产品需要按 banner 维度看点击转化，需要的是**一个 banner 标识**进埋点，不是把 URL 塞进去。**实施时确认**是否需要在 AC7 的埋点里补一个 banner id/序号（非敏感的枚举/数值）。
4. **轮播的无障碍与省流。** 自动轮播对「减少动态效果」系统设置的尊重、以及弱网下多图预取的流量代价，OD-6 拍板时一并考虑。**本 story 只留策略点，不实现。**

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `db/migration/V20260827_1500__init_shop_banner.sql` | 8 列（:16-26）+ 部分索引 :31-33 + 6 条注释 :35-40；头注释 :1-14 写死旧口径 | 🔴 **文件本身零改动**（已被应用）；口径与表注释由**新迁移**覆盖 | 全部（checksum） |
| `shop/domain/ShopBanner.java` | javadoc :13-27 写死旧口径；`apply`:84-89（javadoc :77-83 警告参数传反不报错） | + `targetType`/`targetValue`；改写 javadoc（AC3③） | `active` 默认 false 的安全默认（:46-51, :73）、`@PrePersist/@PreUpdate`:99-108 |
| `shop/repository/ShopBannerRepository.java` | `findFirst...`:20（javadoc :11-19 写死「返回单个而不是列表」）· `findAll...`:23 | + 返列表查询；改写 :11-20 javadoc（AC3④） | `findFirst...`:20 本体（单数端点仍要用）· `findAll...`:23 |
| `shop/dto/ShopBannerView.java` | record :16 三分量；javadoc :6-7 写死「不含任何跳转字段」 | + 两分量；改写 :6-7（AC3⑤） | `imageUrl` 为 CDN 全 URL 的口径（:14）、只给原始宽高不给比例（:10-11） |
| `shop/web/ShopBannerController.java` | `/api/v1/shop/banner`:22 单对象；两条 204 :51-53/:56-58；游客放行 :16-17 | **零改动**；新增复数端点 | 🔴 全部（老版本兼容，见 Dev Notes） |
| `admin/shop/web/AdminShopBannerController.java` | javadoc :36-38 + 行内 :69-70 写死旧口径；`liveId`:71-72 / `live`:85 / `hasLive`:89；5 个写端点 PRG | 改写注释（AC3⑥）+ 改「当前生效」语义 + 表单新字段 | `VIEW_AUTH`:43-45 / `EDIT_AUTH`:46-47（复用商品码，**不新增**）、本地 catch（:111-113 的理由）、`folder="shop-banner"`:101 |
| `admin/shop/dto/ShopBannerForm.java` | 4 字段 :14-17 | + `targetType`/`targetValue` | imageKey 承载 **objectKey 非 URL**（:6） |
| `admin/audit/service/AuditActions.java` | `SHOP_BANNER_*` 5 码 :150/:152/:154/:156/:158 | **零改动** | 全部（不新增动作码） |
| `petgo_app/.../domain/shop_banner.dart` | doc :1-5 写死旧口径；`kShopBannerFallbackAspect=3.0`:13；`aspect`:33-40 **不 clamp**（:29-32） | + 两字段 + `fromJson`:42-46；改写 :3-5（AC3⑦） | 🔴 不 clamp 的比例口径、3.0 兜底 |
| `petgo_app/.../data/shop_repository.dart` | `fetchBanner()`:58（`dio.get<Map>`:60）；`shopBannerProvider`:103（**不 autoDispose**，:101-102） | + 复数取数与 provider | 「失败一律当作没有、返 null/空、不抛错」(:56-58)、不用 autoDispose |
| `petgo_app/.../presentation/toko_page_v2.dart` | `banner` :95；tone :102；capsule :111；appBar 分支 :143/:165-176；`_BannerAppBar`:435-543（遮罩 :509-537，stops :531，`_scrimOpacity`:456-461） | 单图 → 多图；遮罩压在当前图之上；挂点击 | 🔴 `_scrimOpacity` 与 stops 算法、`imageHeight = width/aspect`:468、`errorBuilder`:505、无 banner 时的白色顶栏空态 |
| `petgo_app/lib/core/theme/shop_tokens.dart` | 三段遮罩 :215/:218/:220；胶囊 :240；对比度注释 :198-214 / :231-239 | 🔴 **只改注释里的基线口径说明**（AC8），色值零改动 | 全部色值 |
| `petgo_app/lib/core/router/deep_link_routes.dart` | `shellTabRoots`:24 · `isShellTabRoot`:27 · `notificationsCenter`:11 · `go`/`push` 纪律 :14-23 | **零改动**（只复用） | 全部 |

### References

- [Source: `_bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 8-6`（AC 原文 7 条）· `:136 UX-S3`（每张图都验）· `#开放项 OD-6`]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S11`（两列定义、部分索引与注释连改、站内 path 白名单 / 站外域名白名单配置项、失效回首页静默上报、每张图都验）]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#0 F-7`（C5 实为三处，App mock 已随 `8e85b40d` 删除）· `#F-2`（Flyway E7 与已应用迁移不可改）· `#4 迁移清单 M7`（🔴 改既有索引与注释）· `#3 后台增量`（banner 页：多图、跳转配置）]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md:202 SHOP-FR-22`（四条既有约束必须继承）· `:300 ACT-D`（banner 轮播翻案须留痕）]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/prd-draft-original-2026-09-14.md:352 FR-147`（「基线 `cd291b5d` 刚把白色「Shop」提到 9.09:1」的原始表述）]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#B.5 Story 10.3`（B17 Banner 套模板 B、新建/编辑收进抽屉、端点不变）· `#B.6`（审计规范）· `#B.7`（三语规范）· `#D.10`（`folder="shop-banner"` 在 `AdminShopBannerController:101`）]
- [Source: commit `cd291b5d`（`fix(shop): banner 遮罩改三段——罩住标题那一带，白色「Shop」由 2.45:1 提到 9.09:1`）]
- [Source: 根 `CLAUDE.md` §「实现一个 story 的纪律」5、6（Flyway 时间戳制、已应用迁移不可改、`mvn -B clean package`）]

---

## 验收与交付

| 层 | 怎么验 | 在哪跑 |
|---|---|---|
| **L0** | `mvn -B clean package`；`check-flyway-versions.sh origin/main` 绿；🔴 **grep 四个旧口径关键词零命中**（AC3）；新建的 `ShopBannerView` 契约测试用集合相等断言；域名白名单 5 类单测绿；`flutter analyze` 零 issue；`flutter test` 全绿（含 `toko_page_test.dart` 源码护栏）；`V20260827_1500__init_shop_banner.sql` 在 diff 中**零改动** | 云端 / 本地 |
| **L1** | `ddl-auto=validate` 起得来；集成测试 0/1/3 张的数组长度与顺序；未上架不出现；CDN base 缺失的行被过滤；老端点 `/api/v1/shop/banner` 响应形状与 204 语义**逐字节不变** | 本地（Docker postgres+redis） |
| **L2** | Android 模拟器连 staging：3 张轮播可见且顺序对；`NONE` 点了无反应无上报 / `INTERNAL` 有效跳对 / `EXTERNAL` 拉起浏览器 / 失效目标回首页且埋点一条；🔴 **逐张滚动顶栏，每张图（含一张近白图）的标题与胶囊文字按 AC8 门槛取样核对**；单张 banner 的表现与翻案前一致 | 本地 |

**交付物**：1 支 Flyway 迁移（两列 + CHECK + 回填 + `COMMENT ON TABLE` 覆盖）+ `BannerTargetType` 枚举 + `ShopBanner` / `ShopBannerRepository` / `ShopBannerView` / `ShopBannerController`（新增复数端点）+ 域名白名单配置与校验器 + `ShopBannerForm` + 后台模板与「当前生效」语义 + 三语 key + App 模型/仓库/provider/轮播/遮罩挂载/点击与白名单/失效兜底与埋点 + **7 处口径注释改写** + **ACT-D 决策日志留痕** + 后端第一条 banner 契约测试 + 集成与 Flutter 测试。
**不交付**：遮罩色值改动、`V20260827_1500` 迁移文件的任何改动、单数端点的退役、新权限码、新 `AuditActions` 动作码、轮播四参数的定值（OD-6）、比例 clamp、banner 的 A/B 或定时上下架。

## Definition of Done

- [ ] AC1~AC13 全部满足（L1/L2 未跑的写明「待本地验收」）
- [ ] 🔴 `V20260827_1500__init_shop_banner.sql` 在本次 diff 中**零改动**；新迁移用时间戳号，`check-flyway-versions.sh origin/main` 通过
- [ ] 🔴 grep「同一时间只展示一张」「纯展示」「不可点」「不做轮播」，除新迁移中显式说明「已推翻」的那一处外**零命中**；7 处注释逐处已改写为现状（不是删空）
- [ ] `ShopBanner.java:16-17` 那个不存在的 `pickActive` 引用已修正为真实方法名
- [ ] ACT-D 留痕已写入 `v1.4.0/decision-log.md`（或 `CROSS-STORY-DECISIONS.md` + 日志指针），编号用该文件的 `S-n` 体系
- [ ] 🔴 `GET /api/v1/shop/banner` 的响应形状与两条 204 语义**逐字节未变**；新增的是复数端点 `/api/v1/shop/banners`，且已在 `SecurityConfig` 对游客放行
- [ ] C5 三处同改完成（后端 record + App DTO + **新建**契约 test）；契约 test 用**集合相等**断言键集合
- [ ] 站内白名单在 App 侧、是显式常量集合；shell Tab 分支根用 `go` 不用 `push`（复用 `isShellTabRoot`），且代码与注释中都没有源码护栏禁掉的字面量
- [ ] 站外白名单是**配置项**、默认空集合（fail-closed）、只接 https、**比对主机名**；5 类单测绿
- [ ] `NONE` 完全不可点；失效目标回首页 + 静默上报，埋点属性**不含 `targetValue`**、不含个人信息，按钮 id 已进 `_allowedButtonIds` 与 `button_ids.dart`
- [ ] 🔴 AC8 的基线口径已澄清并写进 `shop_tokens.dart` 注释；遮罩色值（:215/:218/:220/:240）与 `_scrimOpacity`/stops 算法**零改动**；遮罩在轮播时压在当前显示的图之上
- [ ] 轮播四参数（张数上限/停留时长/是否自动/高度策略）全部从一处读取、各注「OD-6 待定」；单图路径表现与翻案前等价
- [ ] 未新增权限码、未新增 `AuditActions` 常量；审计 detail 不含 `target_value` 原文
- [ ] 新增 message key 三包齐备（zh_CN / en / id），集合相等
- [ ] 后台列表页「当前生效」语义已改为「N 张轮播 + 顺序」，不再宣称「只有第一条生效」
- [ ] `mvn -B clean package`（**带 `clean`**）+ `flutter analyze` + `flutter test` 全通过
- [ ] Completion Notes 记明：前后端**必须同批部署**；单数端点退役待办；四条开放项的结论或「待确认」
