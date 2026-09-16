---
baseline_commit: 5f5982cb
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 6
story: 6-3
ad: [AD-S1, AD-S1-E]
decisions: [SD-12, SD-15, C5, C.2]
fr: [SHOP-FR-19, SHOP-NFR-04]
---

# Story 6-3: App 品类下发与停用适配

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
> 🔴 **前置：admin 主题 Epic 10 完成**（SD-16，第二批整组前置）+ **Story 6-1 完成**（`GET /api/v1/shop/categories` 端点与停用语义已在后端就位）。
> 本 story **纯 App（Flutter）**，不改后端一行。
> 🔴 **本 story 是对「App 不渲染后端显示串、按 code 本地化」模型的有意例外（AD-S1-E）。例外边界见下面专门一节，不得扩散。**

## Story

As a 用户，
I want 新上的品类在 App 里能看到、名字是我看得懂的语言，
so that 我能找到想买的东西 —— 而不是等一次发版才看到店里新开的货架。

---

## Context

### 🔴 例外边界（本 story 最重要的一节，实现前先读）

项目基线（`CROSS-STORY-DECISIONS` C.2 + 用户记忆 `petgo-i18n-model-and-debt`）：**App 绝不渲染后端下发的显示串，一律按 code + typeSlug 在 ARB 里本地化。** 通知文案「三处同改」（`ModerationNotifyListener` / `messages_*.properties` / App ARB）就是这条模型的成文代价。

**AD-S1-E 给品类名开了一个例外**，理由只有一条：**运营新建的品类不可能预先存在于 ARB 里**。ARB 是编译进包的资源，而品类可配置的全部意义就是「不用发版」。

例外的边界必须写死在代码注释里：

| | 允许 | 不允许 |
|---|---|---|
| **字段** | 只有品类的 `name`（`GET /shop/categories` 返回的那一个字符串） | 商品名、品牌名、规格名、失效原因、错误提示、通知文案、状态标签 —— 这些**照旧走 ARB** |
| **语言选择** | 由**后端**按 `Accept-Language` 决定下发 `name_id` 还是 `name_en`，App 拿到什么显示什么 | App 自己拿双语对象挑一个（那等于把 i18n 逻辑复制到客户端） |
| **兜底** | 端点不可达时回退 ARB 里现有的四个品类名 | 端点不可达时显示空白或 code |
| **扩散** | — | 🔴 **任何人想把这条例外用在第二个字段上，都必须回到架构评审，不能援引本 story 作先例** |

这段话要以注释形式落在 App 的品类模型类头部，措辞明确写「**例外仅限品类名**」。

### 现状一：品类是 Dart 编译期枚举，注释里明写「不可配置」

`petgo_app/lib/features/shop/domain/shop_product.dart:10-32`：

```dart
/// 四个固定品类（对应后端 `ProductCategory`）。
///
/// 🔴 **固定四值，不做「全部品类」之外的动态扩展**——品类是 FR-93 的信息架构，
/// 不是运营可配项。新增品类须走 PRD 变更，不是加一行枚举。
enum ShopCategory {
  makanan('MAKANAN'),          // :15
  obatVitamin('OBAT_VITAMIN'), // :16
  camilan('CAMILAN'),          // :17
  perawatan('PERAWATAN');      // :18

  const ShopCategory(this.api);
  final String api;            // :25 后端枚举字面量

  static ShopCategory? fromApi(String? raw) {   // :28
    if (raw == null) return null;
    for (final c in ShopCategory.values) {
      if (c.api == raw) return c;
    }
    return null;                                // :29-31 🔴 未知 code → null，不崩
  }
}
```

`:11-13` 那句注释就是本 story 要推翻的前提，**必须改写**。

### 现状二：显示名是 ARB 里的四个硬编码 key

`toko_page_v2.dart:273-278`：

```dart
String _categoryLabel(AppLocalizations l10n, ShopCategory c) => switch (c) {
      ShopCategory.makanan => l10n.tokoCategoryMakanan,
      ShopCategory.obatVitamin => l10n.tokoCategoryObatVitamin,
      ShopCategory.camilan => l10n.tokoCategoryCamilan,
      ShopCategory.perawatan => l10n.tokoCategoryPerawatan,
    };
```

**穷尽式 switch，无 `default`** —— 枚举一旦不再是编译期固定集合，这行必然改。

ARB 只有两份（**没有中文包**）：

| key | `app_id.arb` | `app_en.arb` |
|---|---|---|
| `tokoCategoryMakanan` | `:1813` `Makanan` | `:2567` `Food` |
| `tokoCategoryObatVitamin` | `:1814` `Obat & Vitamin` | `:2569` `Medicine & Vitamins` |
| `tokoCategoryCamilan` | `:1815` `Camilan` | `:2571` `Snacks` |
| `tokoCategoryPerawatan` | `:1816` `Perawatan` | `:2573` `Grooming` |
| `tokoCategoryAll` | `:1824` `Semua` | `:2589` `All` |
| `tokoCategoryLabel` | `:1811` `Kategori` | `:2563` `Category` | ⚠️ Dart 代码里**零引用**（与 `tokoAllFeaturedLabel` 一样是孤儿 key） |

> `petgo_app/lib/l10n/` 下只有 `app_en.arb` / `app_id.arb` 两个 arb（外加三个生成产物 `app_localizations*.dart`）。**「三语」指的是后台 Thymeleaf 的 `messages_{zh_CN,en,id}.properties`，与 App 无关。**

### 现状三：🔴 **横向滑动其实已经实现了** —— SD-15 这一条在 App 侧是既成事实

规划文档写「首页分类入口**品类超过 4 个时改为横向滑动**」，但代码核实结果是：**分类入口从来就不是固定 4 格，它已经是横滑 chip 行。**

`toko_page_v2.dart` 的 `_FilterBar`（`:292-368`，`build` 在 `:318-361`）：

```
SizedBox(height: 28 + 8*2)              // :312-313 _rowHeight=28, _vPad=8；:315 preferredSize
 └ Row
    ├ 搜索放大镜图标                      // onSearch → context.push('/shop/search')
    └ Expanded
       └ SingleChildScrollView(          // :340-342
             scrollDirection: Axis.horizontal,
             physics: ClampingScrollPhysics())
          └ Row
             ├ _chip(allLabel, selected == null, …)            // :345
             └ for (final c in ShopCategory.values) …          // :346-350
```

而且注释 `:264-272` 与 `:337-339` **已经写明了设计意图**：「横滑不换行，与品类数解耦，**运营加类目也不涨行数**」。

👉 **结论修正**：SD-15 的「超过 4 个改横向滑动」在 App 侧**不需要新做**。本 story 在这一点上的真实工作是：
1. 把 `for (final c in ShopCategory.values)` 换成「遍历后端下发的列表」；
2. **验证**品类为 5 / 8 / 12 个时行高不变、能滑到底、不溢出（这是 AC 而不是 task）；
3. 把这条「已实现」的事实写进 Completion Notes，免得后续 story 又去「实现」一遍。

两个落位分支都要走到：

| 分支 | 行号 | 形态 |
|---|---|---|
| 无 banner | `:143-153` | `ShopAppBar(bottom: filterBar)` |
| 有 banner | `:168-182` | `_BannerAppBar` + `SliverPersistentHeader(pinned: true, delegate: _StickyFilterBar(child: filterBar))`；`_StickyFilterBar` 定义 `:378-412`，`minExtent == maxExtent == child.preferredSize.height + kShopGutter`，`shouldRebuild` 比引用（`:410-412`） |

🔴 `_StickyFilterBar.shouldRebuild` **比的是 child 引用**（`:410-412`）。品类列表从异步 provider 来之后，「加载中 → 有数据」两态的 `filterBar` 必须是**不同的 widget 实例**，否则吸顶头不重建。**实施时确认**。

### 现状四：品类 code 还有四条别的消费路径

| # | 位置 | 用途 | 改造影响 |
|---|---|---|---|
| ① | `core/router/app_router.dart:161` | `TokoPageV2(initialCategory: state.uri.queryParameters['category'])`；白名单说明 `:532-533`「非法值由 `ShopCategory.fromApi` 落回 null」 | 深链带的是 **code 字符串**；改造后仍按 code 匹配下发列表 |
| ② | `features/shop/presentation/product_detail_page_v2.dart:507-532` | 售罄底栏：`:508` 取 `d.category`；`:517-519` 品类为 null 时**只显示整宽置灰按钮**；`:529` `context.go('/shop?category=${category.api}')` | `category.api` 要改成 code 字符串 |
| ③ | `features/profile/presentation/health_list_page.dart:374-401` | FR-110 健康记录 → 品类跳转；`:375` `TriageCategoryJump.categoryFor(item.type)`；`:401` `context.go('/shop?category=$category')`；埋点 `triage_category_jump_tapped`（`:398`） | 已经是裸 code 字符串，**无需改** |
| ④ | `shared/boundary/triage_category_jump.dart:18-52` | `:21` `_obatVitamin = 'OBAT_VITAMIN'`；`:28-31` switch | 🔴 **零改动**（后端同名守卫 `shared/boundary/TriageCategoryJump.java:19` 与 `ConsultShopBoundaryTest` 钉着它，6-1 已确认后端侧也不动） |

> ⚠️ ③ 的跳转目标 `OBAT_VITAMIN` **理论上可被运营停用**。停用后跳过去会得到 422（6-1 AC5：停用品类作为筛选参数返 422）。**这是个真实的边界态，AC4 要覆盖。**

搜索页 `shop_search_page.dart:102/108` 恒传 `category: null`（`:15` 注释：日后要加页内品类筛选不用改接口）—— 本 story **不做**搜索页品类筛选。

### 现状五：购物车失效行已有兜底，但文案是通用的

`features/shop/domain/shop_cart.dart:10-38`：

```dart
enum CartInvalidReason {
  delisted('DELISTED'),
  outOfStock('OUT_OF_STOCK'),
  unavailable('');                      // 认不出的原因

  static CartInvalidReason? fromApi(String? raw) {   // :28
    if (raw == null || raw.isEmpty) return null;
    for (final r in values) { if (r.api == raw) return r; }
    return unavailable;                 // :35 🔴 绝不当成有效
  }
}
```

渲染：`cart_page_v2.dart` 的 `_InvalidLine`（`:414-507`）—— 图上蒙层 + 短原因（`:433-440`）· 信息区 `Opacity(.75)`（`:451-465`）· 两个出口「找相似」（`:470-478`，`context.go('/shop')`，注释说明无同类目替代品端点）与「移除」（`:480-488`）· 原因文案 switch `:503-507`：

```dart
String _reasonShort(AppLocalizations l10n, CartInvalidReason? reason) => switch (reason) {
      CartInvalidReason.delisted => l10n.cartReasonDelisted,
      CartInvalidReason.outOfStock => l10n.cartReasonOutOfStock,
      _ => l10n.cartReasonUnavailable,
    };
```

✅ **老版本 App 已天然兼容 6-1 新增的 `CATEGORY_DISABLED`**：`fromApi` 认不出就落 `unavailable`，`_reasonShort` 的 `_ =>` 分支渲染通用文案。本 story 做的是**给它一句准确的文案**，不是「修一个崩溃」。

结算侧：`checkout_preview.dart:20/37/61` 的 `CheckoutLine.invalidReason` 是**裸 `String?`**（未枚举化）；`:171-203` 的 `UnavailableLine` 有 `:194` `bool get isDelisted => reason == 'DELISTED';`；`checkout_page_v2.dart:633-634` 与对话框 `:647-674`（`:668` 按 `isDelisted` 二选一文案）。**加第三种原因要动 `:668` 这个二选一。**

### 现状六：已有的「不带参数全局 FutureProvider」范式可直接抄

`features/shop/data/shop_repository.dart:103-105` 的 `shopBannerProvider` —— 🔴 **刻意不用 `autoDispose`**（`:99-102` 注释：变动极少 + Tab 高频进出，不缓存会闪）。**品类列表与 banner 的性质完全一样**，直接复刻这个范式。

同文件其余：`:29-47` 商品列表（`:39` 透传 `category` 查询参）· `:83` `ShopProductsQuery` record · `:92-98` family provider · `:76` repoProvider。
另一个范式（带三态的页面本地 State）：`pawcoin/presentation/recharge_page.dart:33/:67-70/:182-184/:207` 的充值档位 —— **不采用**，品类需要跨页共享。

端点常量：`core/network/api_paths.dart:274` `shopProducts = '$base/shop/products'`（`:273` 注释「可选 query: `category`」，已对游客放行）。

---

## Acceptance Criteria

**AC1 · 品类从后端取，名字用后端下发的那一个** `[L0/L2]`
**Given** App 今天用编译期枚举 + ARB 四个 key
**When** 接入 `GET /api/v1/shop/categories`
**Then** 新增 `shopCategoriesProvider`（全局 `FutureProvider`，🔴 **不用 `autoDispose`**，范式 `shop_repository.dart:103-105` 的 `shopBannerProvider`）`[L0]`
**And** 分类 chip 行遍历后端返回的列表，chip 文本 = 后端下发的 `name`，**App 不做任何语言选择**（后端按 `Accept-Language` 已选好）`[L0]`
**And** `dio` 已有的 `Accept-Language` 注入生效（**实施时确认**拦截器里是否已带该头；没带就补，补的位置是网络层拦截器而不是这一个调用点）`[L1]`
**And** 🔴 **端点不可达 / 超时 / 返回空数组时，回退到 ARB 里现有的四个品类名**（`tokoCategoryMakanan` / `ObatVitamin` / `Camilan` / `Perawatan` + code 顺序 MAKANAN/OBAT_VITAMIN/CAMILAN/PERAWATAN），分类行**不空、不转圈、不报错** `[L0]`
**And** 「全部」chip（`tokoCategoryAll`）**继续走 ARB**，恒排第一 —— 它不是一个品类，是「无筛选」`[L0]`
**And** 排序按后端下发的顺序原样渲染（后端已按 `sort_weight DESC` 排好），🔴 **App 不再排一次** `[L0]`

**AC2 · 品类数量与行高解耦（SD-15 —— 🔴 已实现，本条是验证）** `[L0/L2]`
**Given** `_FilterBar`（`toko_page_v2.dart:292-368`）已经是 `SingleChildScrollView(scrollDirection: Axis.horizontal)`（`:340-342`），注释 `:264-272` / `:337-339` 明写「与品类数解耦，运营加类目也不涨行数」
**Then** 本 story **不重做布局**，只把 `for (final c in ShopCategory.values)`（`:346-350`）换成遍历下发列表 `[L0]`
**And** widget 测试断言：品类为 **5 个 / 8 个 / 12 个**时，`_FilterBar` 的高度恒为 `_rowHeight + _vPad*2`（`:312-313`，28 + 16 = 44），**不换行、不溢出**（无 `RenderFlex overflowed` 报错）`[L0]`
**And** 两个落位分支都验：无 banner（`:143-153`）与有 banner 的吸顶态（`:168-182` + `_StickyFilterBar:378-412`）`[L0]`
**And** 🔴 `_StickyFilterBar.shouldRebuild`（`:410-412`）比的是 child 引用 —— 品类从「加载中兜底四项」切到「后端 8 项」时吸顶头必须重建。**用一条 widget 测试钉住这个切换**（先返兜底、再返 8 项，断言 chip 数从 4 变 8）`[L0]`
**And** L2 模拟器：后台新增第 5 个品类后，首页分类行可横向滑到它 `[L2]`
**And** Completion Notes 写明「横滑在 6-3 之前就已实现，本 story 只做数据源替换与验证」—— 防止后续 story 重复实现 `[L0]`

**AC3 · 停用品类的商品与购物车行** `[L0/L2]`
**Given** 运营停用某品类（6-1 已保证后端过滤 + 购物车行下发 `CATEGORY_DISABLED`）
**Then** 该品类不在分类 chip 行出现（后端不下发，App 不需额外过滤）`[L0]`
**And** 该品类商品不在 Toko 列表、搜索结果、推荐区、复购卡出现（后端已过滤，App **不做二次过滤**）`[L2]`
**And** 🔴 购物车：`CartInvalidReason` 新增 `categoryDisabled('CATEGORY_DISABLED')`（`shop_cart.dart:10-38`），`_reasonShort`（`cart_page_v2.dart:503-507`）新增一路，文案说清「这类商品平台已下架」而不是笼统的「暂时无法购买」`[L0]`
**And** 该行**不可勾选结算**（勾选能力来自 4-2；本 story 只保证它落在 `invalidLines` 组里，与 delisted / outOfStock 同处理）`[L2]`
**And** 「找相似」出口（`cart_page_v2.dart:470-478`）对这一路**仍跳 `/shop`**（不带 category）—— 品类都停了，带 category 跳过去是 422 `[L0]`
**And** 结算页：`checkout_page_v2.dart:668` 的 `isDelisted` **二选一改三选一**；`checkout_preview.dart:194` 附近加 `bool get isCategoryDisabled => reason == 'CATEGORY_DISABLED';`（🔴 **保留 `isDelisted` 不改语义**，只加一个）`[L0]`
**And** 新增三条 ARB key（`cartReasonCategoryDisabled` 等）**两份 arb 都写**（`app_en.arb` + `app_id.arb`）`[L0]`

**AC4 · 未知 / 已停用 code 的四条边界态** `[L0/L2]`
**Given** code 可能来自深链、来自健康记录跳转、来自商品 DTO
**Then** ① 深链 `/shop?category=<未知或已停用 code>`（`app_router.dart:161`）：匹配不到下发列表 ⇒ 落到「全部」态，**不崩、不空白**；白名单说明 `:532-533` 要随之改写 `[L0]`
**And** ② 商品 DTO 的 `category` 是未知 code（老数据 / 灰度期）：解析为 `null`，商品照常展示；详情页售罄底栏走 `product_detail_page_v2.dart:517-519` 的既有 null 分支（**只显示整宽置灰按钮**）`[L0]`
**And** ③ 🔴 健康记录 → 品类跳转（`health_list_page.dart:374-401`）目标 `OBAT_VITAMIN` **被运营停用**时：跳过去后端返 422，App 必须**降级成「全部」列表或给一句提示**，🔴 **不得把 422 的 ProblemDetail 直接弹给用户**。`shared/boundary/triage_category_jump.dart` **零改动** `[L0]`
**And** ④ `GET /shop/categories` 返回的品类里有一个 code，本地 ARB 兜底里没有 —— 正常，chip 显示后端下发的 `name`，**不查 ARB** `[L0]`

**AC5 · 🔴 老版本兼容三条（SHOP-NFR-04）** `[L0/L2]`
**Given** 线上已装老版本的用户不会立刻升级
**Then** ① **新品类商品在老版本「全部」列表中照常出现** —— 老版本不传 `category` 参数时后端返全量（6-1 未改这条）；老版本 `ShopCategory.fromApi` 遇未知 code 返 `null`（`shop_product.dart:29`），等同「无品类」，只影响详情页售罄底栏的「看看别的」按钮，而那里 `:517-519` 已有 null 分支 `[L2]`
**And** ② **老版本遇到未知 `category` code 解析为 null、不崩溃** —— 这是既有行为（`shop_product.dart:28-31`、`shop_product_detail.dart:164`），本 story **不得破坏它**：新模型的解析必须同样「认不出 → null」，绝不 `throw`、绝不 `assert` `[L0]`
**And** ③ **改名对老版本无效，仍显示 ARB 旧名** —— 老版本不调新端点。🔴 **这是已接受行为**（SHOP-FR-19 明写），不是 bug，不要为它做任何兼容 `[L0]`
**And** 三条各写一句进代码注释或 Completion Notes，避免将来有人把 ③ 当 bug 报 `[L0]`

**AC6 · 契约同改（C5 · K1）** `[L0]`
**Given** C5 要求后端 record + App data DTO + 契约 test 三处同步（**不是四处** —— App mock 已随 `8e85b40d` 删除）
**Then** 新增 App data 模型 `ShopCategoryItem`（`code` / `name` / `sortWeight`），字段集与后端 `ShopCategoryView` 对齐 `[L0]`
**And** `shop_product.dart` 的 `category` 与 `shop_product_detail.dart:134` 的 `category` **由 `ShopCategory?` 枚举改为 `String?` 裸 code**（解析处 `shop_product.dart:86` / `shop_product_detail.dart:164` 同步）`[L0]`
**And** 🔴 **wire 格式不变**：后端 `category` 一直是 `"MAKANAN"` 这样的字符串（6-1 已用 Jackson 断言证明改 `String` 后线格式不变），所以**这是 App 侧的纯类型变更，不是契约变更** —— 说明写进 Dev Agent Record `[L0]`
**And** 测试夹具同改：`test/shop/toko_page_v2_test.dart:74,:332` · `test/shop/shop_search_page_test.dart:49` · `test/shop/product_detail_page_v2_test.dart:70` `[L0]`

**AC7 · 枚举退役、注释改写** `[L0]`
**Given** `shop_product.dart:10-32` 的 `enum ShopCategory` 及其 `:11-13` 那句「🔴 固定四值……新增品类须走 PRD 变更，不是加一行枚举」
**Then** 枚举退役（删除，或降级为**仅供兜底用的四个常量 code**，**实施时确认**哪种对调用点冲击小）`[L0]`
**And** 🔴 `:11-13` 的注释**必须改写** —— 它现在说的是本 story 推翻的前提。新注释写清：品类已是后端可配置数据（SHOP-FR-19 / AD-S1），App 只保留四个存量 code 作端点不可达时的兜底，**不得再当作完整集合** `[L0]`
**And** 🔴 新模型类头部加例外声明注释：「**本类的 `name` 是 App 中唯一直接渲染后端下发显示串的字段**（AD-S1-E）。例外仅限品类名，理由是运营新建的品类不可能预先存在于 ARB。**任何其它字段都不得援引本例外**。」`[L0]`
**And** `toko_page_v2.dart:273-278` 的 `_categoryLabel` switch **删除**（不再需要 code → ARB 的映射）`[L0]`
**And** ARB 里四个 `tokoCategory*` key **保留**（兜底要用），但加 `@` 描述说明「仅作端点不可达时的兜底，正常路径用后端下发名」`[L0]`
**And** 孤儿 key `tokoCategoryLabel`（`app_id.arb:1811` / `app_en.arb:2563`，Dart 零引用）**本 story 不清理**（超出范围，记进 Completion Notes）`[L0]`

**AC8 · L0 全绿** `[L0]`
**Then** `flutter analyze` 零 issue `[L0]`
**And** `flutter test` 全绿，含新增用例：品类列表渲染 · 兜底路径 · 5/8/12 个品类的行高 · 吸顶头重建 · 购物车新失效原因 · 深链未知 code · 商品 DTO 未知 code `[L0]`
**And** 🔴 **变异验证**：把 `shopCategoriesProvider` 的兜底分支去掉 → 兜底用例必须变红；把 `CartInvalidReason.fromApi` 的 `return unavailable`（`shop_cart.dart:35`）改成 `return null` → 「未知原因不当成有效」的用例必须变红。结果写进 Completion Notes `[L0]`

**AC9 · L2 模拟器验收（Android）** `[L2]`
**Given** 连 staging 的真实后端
**Then** ① 后台新增第 5 个品类 → App 首页分类行出现它、可横向滑到、点进去有商品 `[L2]`
**And** ② 后台改某品类显示名 → App **重启后**显示新名（provider 非 autoDispose，热路径不会立刻刷；**这是可接受的**，在 Completion Notes 记录刷新时机）`[L2]`
**And** ③ 后台停用某品类 → 该品类 chip 消失、该品类商品从 Toko 列表消失、购物车里该品类的行变失效态且文案是新的那句 `[L2]`
**And** ④ 切 App 语言（id ↔ en）→ 品类名跟着变（证明 `Accept-Language` 链路通）`[L2]`
**And** ⑤ 断网 → 分类行显示 ARB 兜底四项，不空白不转圈 `[L2]`
**And** 🔴 「模拟器」一律指 **Android 模拟器**（adb + flutter），不用 iOS `[L2]`

---

## Tasks / Subtasks

- [ ] **T1 · 数据层**（AC1/AC6/AC7）
  - [ ] `core/network/api_paths.dart`：紧跟 `:274` 的 `shopProducts` 加 `shopCategories = '$base/shop/categories'`，注释写「游客可取（后端 permitAll）」
  - [ ] 新建 `features/shop/domain/shop_category.dart`：`class ShopCategoryItem { final String code; final String name; final int sortWeight; }` + `fromJson`
  - [ ] 🔴 类头部写例外声明注释（AC7 第三条的原文）
  - [ ] `features/shop/data/shop_repository.dart`：加 `Future<List<ShopCategoryItem>> fetchCategories()`；加 `shopCategoriesProvider`（**不加 `autoDispose`**），位置紧邻 `:103-105` 的 `shopBannerProvider`，**照抄它 `:99-102` 的注释理由**
  - [ ] 兜底：provider 内部 catch → 返回四项常量兜底（code + ARB key 的对应关系放一个私有常量表）。🔴 **兜底在 provider 层做，不在 widget 层做** —— 否则每个消费方都要写一遍
  - [ ] 确认 `dio` 拦截器已注入 `Accept-Language`（**实施时确认**；没有就在网络层补，不在调用点补）

- [ ] **T2 · 枚举退役与 DTO 改类型**（AC6/AC7）
  - [ ] `features/shop/domain/shop_product.dart`：`:51` `final ShopCategory? category` → `final String? category`；`:86` 解析改 `json['category'] as String?`；`:40` 构造参数随之
  - [ ] `features/shop/domain/shop_product_detail.dart:123/:134/:164` 同上
  - [ ] `enum ShopCategory`（`:10-32`）退役；🔴 `:11-13` 注释改写
  - [ ] 🔴 **解析绝不 throw、绝不 assert** —— 未知 code 就是一个字符串，照常带着走（AC5 ②）

- [ ] **T3 · Toko 页**（AC1/AC2/AC4）
  - [ ] `toko_page_v2.dart:273-278` 删 `_categoryLabel`
  - [ ] `:61` `ShopCategory? _selected` → `String? _selectedCode`；`:66` `_query`；`:71` / `:82-83` 的 `ShopCategory.fromApi(widget.initialCategory)` → 直接用 `widget.initialCategory`（裸 code），**匹配不到下发列表时置 null**（AC4 ①）
  - [ ] `:118-125` 的 `_FilterBar` 入参：`labelOf` 回调改成直接吃 `ShopCategoryItem.name`；新增 `categories` 入参
  - [ ] `:345-351` 的 `for (final c in ShopCategory.values)` → `for (final c in categories)`；`_chip(c.name, selected == c.code, () => onSelect(c.code))`
  - [ ] 🔴 **不改 `_FilterBar` 的布局代码**（`:312-315`、`:337-342`、`_chip:363-368`）—— 横滑已经在了
  - [ ] 🔴 `_StickyFilterBar.shouldRebuild`（`:410-412`）：确认「兜底 4 项 → 后端 8 项」切换时 child 是新实例（**实施时确认**；若不是，改成比 `preferredSize` + 品类 code 列表）
  - [ ] `:191-215` 的 masonry 与 `:211-212` 的 `entrySource`（`TOKO_ALL_FEATURED` / `TOKO_CATEGORY`）**不动**
  - [ ] `:155-159` 下拉刷新：追加 `ref.invalidate(shopCategoriesProvider)`（让用户有一个手动刷新品类的路径）

- [ ] **T4 · 其它 code 消费点**（AC4）
  - [ ] `core/router/app_router.dart:161` 保持传 `state.uri.queryParameters['category']`（裸 code）；🔴 `:532-533` 的白名单说明改写（不再是「由 `ShopCategory.fromApi` 落回 null」，改成「匹配不到下发列表则落到全部态」）
  - [ ] `features/shop/presentation/product_detail_page_v2.dart:529`：`category.api` → 裸 code；`:517-519` 的 null 分支**保留不动**
  - [ ] `features/profile/presentation/health_list_page.dart:401`：跳转不变；🔴 目标品类被停用时的降级（AC4 ③）—— 落点是 Toko 页收到 422 后的处理，**不是**在 health 页判断
  - [ ] 🔴 `shared/boundary/triage_category_jump.dart` **零改动**
  - [ ] `shop_search_page.dart:102/108` **零改动**（本 story 不做搜索页品类筛选）

- [ ] **T5 · 购物车与结算失效态**（AC3）
  - [ ] `features/shop/domain/shop_cart.dart:10-38`：`CartInvalidReason` 加 `categoryDisabled('CATEGORY_DISABLED')`，**加在 `unavailable` 之前**（`unavailable` 是兜底档，必须留在最后）
  - [ ] 🔴 `fromApi`（`:28-37`）的 `return unavailable`（`:35`）**不动** —— 它是「认不出绝不当成有效」的兜底，新加一个已知值不改变这条
  - [ ] `features/shop/presentation/cart_page_v2.dart:503-507` 的 `_reasonShort` 加一路
  - [ ] 「找相似」（`:470-478`）对这一路仍 `context.go('/shop')`，**不带 category**
  - [ ] `features/shop/domain/checkout_preview.dart:194` 附近加 `isCategoryDisabled`；🔴 **`isDelisted` 语义不改**
  - [ ] `features/shop/presentation/checkout_page_v2.dart:668` 的二选一改三选一
  - [ ] ARB：`app_en.arb` + `app_id.arb` **两份都加** `cartReasonCategoryDisabled` 与结算页对应文案（🔴 **只有两份 arb，没有中文包**）

- [ ] **T6 · 测试**（AC8）
  - [ ] 改造既有夹具：`test/shop/toko_page_v2_test.dart:74,:332` · `test/shop/shop_search_page_test.dart:49` · `test/shop/product_detail_page_v2_test.dart:70`
  - [ ] 新增：品类列表渲染（chip 文本 == 下发 `name`）· 端点失败走 ARB 兜底四项 · **5/8/12 个品类的行高恒为 44 且无 overflow** · 吸顶头在「兜底 4 → 后端 8」时重建 · 购物车 `CATEGORY_DISABLED` 渲染新文案 · 深链未知 code 落全部态 · 商品 DTO 未知 code 不崩
  - [ ] 🔴 两次变异验证（AC8），结果写进 Completion Notes

- [ ] **T7 · 云端执行须知**
  - [ ] 云端只跑 L0：`flutter analyze` + `flutter test`
  - [ ] L2（模拟器 + staging 真后端）留本地，Completion Notes 标注「待本地验收」并列出 AC9 的五条
  - [ ] 本 story **不改后端一行**，无需 `mvn`

---

## Dev Notes

### 兜底表放哪

```dart
// features/shop/data/shop_repository.dart 或 domain/shop_category.dart
//
// 🔴 端点不可达时的兜底。这四个 code 是 6-1 迁移灌进 shop_categories 的存量值，
//    顺序与 sort_weight 降序一致。
//    ⚠️ 这不是「品类的完整集合」—— 运营随时可能有第五个。它只是断网时让分类行不空白。
const _fallbackCategoryCodes = ['MAKANAN', 'OBAT_VITAMIN', 'CAMILAN', 'PERAWATAN'];

String _fallbackName(AppLocalizations l10n, String code) => switch (code) {
      'MAKANAN' => l10n.tokoCategoryMakanan,
      'OBAT_VITAMIN' => l10n.tokoCategoryObatVitamin,
      'CAMILAN' => l10n.tokoCategoryCamilan,
      'PERAWATAN' => l10n.tokoCategoryPerawatan,
      _ => code,      // 🔴 不可能走到，但不 throw
    };
```

⚠️ 这个 switch 需要 `AppLocalizations`，而 provider 层拿不到 `BuildContext`。两种解法：① provider 返回 `code` 列表，widget 层套 `_fallbackName`；② provider 返回一个 `isFallback` 标志，widget 层据此决定用 ARB 还是用 `name`。**实施时确认**，选完在 Completion Notes 记一句理由 —— 这是本 story 唯一一处结构上可以走两条路的地方。

### 刷新时机（AC9 ② 的口径）

`shopCategoriesProvider` **不带 `autoDispose`**，所以：

| 动作 | 品类列表会不会更新 |
|---|---|
| 冷启动 | ✅ 重新取 |
| Toko 页下拉刷新 | ✅（T3 已加 `ref.invalidate`） |
| 切 Tab 回 Toko | ❌ 用缓存（**这是刻意的** —— `shopBannerProvider:99-102` 的同款理由：变动极少 + Tab 高频进出，不缓存会闪） |
| 后台改名后用户不重启 | ❌ 下次冷启动或下拉刷新才看到 |

**这是可接受的**：品类变更是低频运营动作，不是实时数据。把它写进 Completion Notes，免得 L2 验收时被当成 bug。

### C5 三处同改（K1）

| # | C5 要求 | 本 story 的落点 |
|---|---|---|
| ① | 后端 record | ✅ **6-1 已做**（`ShopCategoryView`） |
| ② | 后端契约 test | ✅ **6-1 已做**（`ShopCategoryViewContractTest`） |
| ③ | **App data DTO** | 🔴 **本 story**：`features/shop/domain/shop_category.dart` 新建 + `shop_product.dart` / `shop_product_detail.dart` 的 `category` 改 `String?` |
| ~~④~~ | ~~App mock~~ | ⚠️ 不存在（`8e85b40d` 已整体删除 mock 子系统）。等价落点是上面 AC6 列的四个测试夹具 |

### 与 4-2（购物车勾选）的交叉

4-2 给购物车加勾选能力。本 story 新增的 `CATEGORY_DISABLED` 行**落在 `invalidLines` 组**，与 `delisted` / `outOfStock` 同处理 —— 4-2 的勾选逻辑只作用于有效行，所以两者天然不冲突。
🔴 但两条 story 都改 `cart_page_v2.dart`，**合并时注意 `_InvalidLine`（`:414-507`）附近的冲突**。

### Project Structure Notes

- 新文件 `features/shop/domain/shop_category.dart`；provider 落既有 `features/shop/data/shop_repository.dart`（不新建 repository 文件 —— 品类是 shop 域的一部分，单开一个文件会让 `shopProductsProvider` 与它分家）。
- 🔴 **不动** `shared/boundary/triage_category_jump.dart`、`shop_search_page.dart`、`_FilterBar` 的布局代码、`product_detail_page_v2.dart:517-519` 的 null 分支。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S1（含 AD-S1-E 例外）/ #5 契约 K1 / #0 前置事实 F-7]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md#SD-15（横滑 / 双语名 / 停用三条）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md#SHOP-FR-19 / SHOP-NFR-04]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 6-3]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#C5 / C.2（App 不渲染后端显示串）]
- [Source: _bmad-output/implementation-artifacts/v1.3.0/6-1-品类表与迁移后端.md（端点契约、停用语义、`CATEGORY_DISABLED` 常量）]

---

## 验收与交付

| 层 | 怎么验 | 在哪跑 |
|---|---|---|
| **L0** | `flutter analyze` 零 issue；`flutter test` 全绿含七组新用例；两次变异验证各自变红 | 云端 / 本地 |
| **L1** | 本 story **无独立 L1**（不改后端）。`Accept-Language` 链路的验证并入 L2 ④ | — |
| **L2** | 🔴 **Android 模拟器** + staging 真后端，走完 AC9 五条：新增第 5 个品类可滑到 · 改名重启后生效 · 停用后 chip/商品/购物车三处表现 · 切语言品类名跟着变 · 断网走 ARB 兜底 | 本地 |

**交付物**：`shop_category.dart` 新模型 + `shopCategoriesProvider`（含兜底）+ `api_paths` 一条常量 + `toko_page_v2.dart` 数据源替换（删 `_categoryLabel`、chip 遍历改下发列表）+ `shop_product.dart` / `shop_product_detail.dart` 的 `category` 改 `String?` + `ShopCategory` 枚举退役与注释改写 + 购物车与结算的第三种失效原因 + 两份 arb 新 key + 四个测试夹具改造 + 七组新用例。

**不交付**：后端任何改动（6-1 / 6-2）· 搜索页品类筛选（`shop_search_page.dart:15` 注释里的「日后」）· `triage_category_jump.dart` 改动 · `_FilterBar` 布局重做（已实现）· 孤儿 key `tokoCategoryLabel` 清理 · 购物车勾选（4-2）。

## Definition of Done

- [ ] AC1~AC9 全部满足（L2 未跑的写明「待本地验收」并列出 AC9 五条）
- [ ] 🔴 新模型类头部有**例外声明注释**，明写「例外仅限品类名，其它字段不得援引」
- [ ] 🔴 `shop_product.dart:11-13` 那句「固定四值……不是运营可配项」已改写（它是本 story 推翻的前提）
- [ ] chip 文本 = 后端下发的 `name`；App **不做**语言选择、**不做**二次排序
- [ ] 「全部」chip 仍走 ARB，恒排第一
- [ ] 端点失败 / 超时 / 空数组 → ARB 兜底四项；兜底逻辑在 **provider 层**而非 widget 层
- [ ] 🔴 **Completion Notes 写明「横滑在本 story 之前就已实现」**（`toko_page_v2.dart:340-342` + 注释 `:264-272`/`:337-339`），本 story 只做数据源替换与验证
- [ ] 5/8/12 个品类的行高恒为 44、无 `RenderFlex overflowed`，有 widget 测试钉住
- [ ] 吸顶头在「兜底 4 → 后端 8」切换时重建，有测试钉住
- [ ] 购物车新增 `categoryDisabled`，加在 `unavailable` **之前**；`fromApi:35` 的兜底 `return unavailable` 未改
- [ ] 结算页 `checkout_page_v2.dart:668` 二选一改三选一；`isDelisted` 语义未改
- [ ] 新 ARB key **两份 arb 都写**（只有 en/id，没有中文包）
- [ ] 老版本兼容三条各有注释或 Completion Notes 记录，尤其 ③「改名对老版本无效是已接受行为」
- [ ] 未知 code 的解析**绝不 throw、绝不 assert**（AC5 ②），有测试钉住
- [ ] 健康记录跳转到已停用品类时不把 422 的 ProblemDetail 弹给用户
- [ ] 🔴 `shared/boundary/triage_category_jump.dart` 在本次 diff 中**零改动**（diff 可自证）
- [ ] `_FilterBar` 的布局代码（`:312-315`/`:337-342`/`:363-368`）未被重做
- [ ] 刷新时机（切 Tab 不刷新是刻意的）写进 Completion Notes
- [ ] 兜底表 `AppLocalizations` 取法的两条路选了哪条 + 理由，写进 Completion Notes
- [ ] 两次变异验证结果写进 Completion Notes
- [ ] `flutter analyze` + `flutter test` 全绿
