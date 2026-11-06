# Story 8.3: 购物车（App，C1–C8）

Status: review

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9）> PRD `电商/1-4-2电商prd.md`（§3.0 旅程 A、§3.1「免运进度提示」、§3.7「购物车」、§3.10「购物车运费、询价与保价」「其余细节」、§6 C 组）> `architecture-v1.4.2-shop-delta.md`（D-9、P-1、P-4、P-10、§1.2 第 11 条）> UI 定稿 `电商/ui-1-4-2-shop-integrated-v1.html` C1–C8 每屏说明；云端执行以 `cloud-rules-shop.md` 为准。
> 前置：**3.1**（`GET /me/cart` 的 `estimate`、失效行 `selected` 恒 false、勾选失效行 422）、**7.1**（结算页 `entry: cart` 埋点、`tokoSavedAmount`、免运进度 key）、**8.1**（`ShopRecoGrid`、`tokoMightLike`）、**8.2**（`AppPrefs.shopDeliveryArea`）。
> 范围层：**只 App**。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。

## Story

As a 买家,
I want 购物车里就能看到运费、优惠后的预估应付,
so that 决定买多少时心里有数。

## 接口契约（计划名，开工前核对 3.1 实际提交）

> **T0 核对结果（dev 2026-11-05，以后端实际代码为准）**：`CartEstimate` 实际字段 `lowestFee: Long? / goodsAmount / discountCouponAmount / shippingDiscount / payable / saved / freeShippingProgress{threshold, remaining}? / reason`；`reason` 实际取值 `null | NO_DESTINATION | NOT_SERVICEABLE | QUOTE_FAILED | NO_SELECTION`（下文 `NO_ADDRESS` 即 `NO_DESTINATION`；`NO_SELECTION` = 一件没选，金额全 0、不询价）。`MeCartController` **7 个出口（GET + 6 个变更）都装配 `estimate`、都接受可选 `regionKecamatanId`** → App 每个请求都带本机地区，变更后不需要补 `refresh()`（待确认 1 已消解）。`estimate` 为 NON_NULL（null 不下发）。

- `GET /api/v1/me/cart?regionKecamatanId=`（App 在本地记过送达地区时带上；服务端规则：有默认地址用默认地址 → 否则用该地区 → 都没有不含运费）。响应在现 `CartView`（`lines / invalidLines / subtotal / itemCount / selectedSubtotal / selectedCount`）上新增 `estimate {lowestFee: int?, payable: int, saved: int, freeShippingProgress: {threshold, remaining}?, reason: NO_ADDRESS|QUOTE_FAILED|NOT_SERVICEABLE|null}`：`payable` = 所选商品 + 本单最低运费 − 默认最优券 − 满额免运；`lowestFee == null` 时 `payable` 不含运费。
- 失效行 `selected` 一律下发 false；`PATCH` 勾选失效行 → 422。
- 加购 / 改数量 / 删除 / 勾选等变更接口返回同一 `CartView`（应带同样的 `estimate`；若 3.1 只在 GET 上带，变更后补一次 `refresh()`，见待确认 1）。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端联调 · `[L2]` Android 模拟器视觉（本地）。

### AC1 — 页面替换与结构 `[L0 widget]` `[L2]`（P-4、UI C1）
1. 新建 `A/features/shop/presentation/cart_page_v3.dart`（`CartPageV3()`），删除 `cart_page_v2.dart`；`app_router.dart:697-698` 改指新页。
2. **原有模块全部保留**（`cart_page_v2.dart`）：标题「Keranjang (n)」（n = 行数，含失效，:179-185）、每行勾选 / 图 / 名 / 规格 / 单价 / 步进器（数量 1 时「−」变删除，`ShopStepper.onRemove`）/ 买到上限时「Sisa stok N」（`cartMaxStock`，:441-460）、底栏全选 + 合计 + Checkout；删除撤销（`_offerUndo` / `_undoRemove` 挂页面 State，:113-176 的规则与注释原样迁移）；游客态（:283-288）、空态（:290-296）、加载失败（:95-101）。
3. 布局重做：每行一块白底 → **同一张白卡片内分隔**；图 64 → **72**；商品名字号 11.5 → **13**；全局视觉规范（`AppTypography` / `AppSpacing` / `AppRounded`，卡片圆角 16、页边 16），本页不再用 `ShopText` 小字号。
4. 进入上报 `toko_cart_page_viewed`（沿用 :78）。

### AC2 — C1 有默认地址：送达行、免运进度、预估应付 `[L0 widget]` `[L2]`（FR-133.8、FR-127.3、FR-172「购物车运费」、UI C1）
1. 列表顶部送达行：`location_on`「Dikirim ke Kec. {kecamatan}」+ `›`；`kecamatan` 取默认地址（`addressListProvider` 中 `isDefault`），无默认地址时取 `AppPrefs.shopDeliveryArea.kecamatan`。点击：有地址 → `context.push('/me/addresses')`，无地址 → `context.push('/me/addresses/new')`；返回后 `ref.invalidate(addressListProvider)` + `cartProvider.refresh()`（按新的默认地址重估）。
2. 底栏上方免运进度行（`local_shipping`）：`threshold > 0 && remaining > 0` →「Belanja Rp {remaining} lagi untuk potongan ongkir」；`remaining == 0` →「Dapat potongan ongkir」；`threshold` 为 0 / null 或没有勾选任何商品（C4）→ 不画。
3. 底栏：左全选（`_SelectAllToggle` 沿用，全选判定排除失效行，`CartView.allValidSelected`）；中「Estimasi total」+ 预估应付 `estimate.payable`（紫色、等宽数字）+ 其下绿色「Hemat Rp {saved}」（`saved == 0` 不画）；右「Checkout ({selectedCount})」（`cartCheckout` 复用 + 件数，写法同标题拼接，:182）。
4. 所有数值取服务端 `estimate`（P-1）：**App 不做「所选商品 + 运费 − 券」的任何计算**；示例（UI C1）商品 349.000 + 最低运费 10.000 − 满减 20.000 − 免运券 10.000 = 329.000、Hemat 30.000、差 171.000 享运费减免——测试用这组数作为假数据，断言原样显示。

### AC3 — C2 无地址或询价失败 `[L0 widget]`（FR-172「其余细节」、UI C2）
1. `estimate.lowestFee == null`（无默认地址且本地没有地区 / 询价失败 / 不可配送）→ 送达行「Pilih alamat untuk hitung ongkir」+ `›`（点击同 AC2.1）；底栏左侧标签「Belum termasuk ongkir」，金额 `estimate.payable`（不含运费），「Hemat」照常（满减部分）。
2. 免运进度仍按 `estimate.freeShippingProgress` 显示（与运费无关）。

### AC4 — C3 失效商品 `[L0 widget]`（FR-133.8、§1.2 第 11 条、UI C3）
1. 失效分组「Tidak Tersedia ({n})」（`cartInvalidSection`）+「Hapus semua」（`cartClearInvalid`，二次确认文案照线上 `cartClearInvalidConfirm*`，:210-255），埋点 `toko_cart_clear_invalid_tapped` 沿用。
2. 失效行：失效原因改为**图片底部一颗深色小标签**（`Stok habis` / `Sudah ditarik` / `Tidak tersedia`，`cartReason*` 复用，:676-683），不再整行文字压在图中间；价格变灰；「Cari mirip」→ `context.go('/shop')`、「Hapus」（沿用 :629-645）。
3. **改线上**：失效行勾选框**不可勾**（恒未勾、点击无反应、不发请求），失效行不参与结算（取代 :551-577「前端如实反映 + 可点」的做法，PRD 11-05 拍板）；后端同步把失效行 `selected` 下发为 false（3.1）。
4. 只剩失效行时不算空：显示失效分组、**无底栏**（沿用）。

### AC5 — C4 未勾选、C5 删除撤销 `[L0 widget]`（UI C4 / C5）
1. **C4**：一件没勾 → 底栏提示「Pilih produk dulu」（**改 `cartSelectNoneHint` 原值**）、金额「Rp 0」变灰、「Checkout」禁用（灰底，不带件数）、不显示免运进度与「Hemat」；点击不发任何请求。
2. **C5**：步进器在 1 时点删除 → toast 5 秒「“{name}” dihapus」+「Urungkan」（`cartRemovedUndo` / `cartUndo` 复用），不弹确认；撤销按删除时的数量加回（沿用）。删除后标题、底栏、免运差额按服务端新 `estimate` 同步（UI C5：285.000 + 运费 10.000 − 免运券 10.000 = 285.000，满减门槛 300.000 不再满足，差 215.000，「Checkout (1)」——测试用这组假数据）。

### AC6 — C6 空购物车推荐、C7 加载失败、C8 未登录 `[L0 widget]`（FR-133.8、UI C6–C8）
1. **C6**：沿用 `cartEmptyTitle` / `cartEmptyMessage` / `cartEmptyCta`（「Mulai belanja」→ `context.go('/shop')`）；其下放 8.1 的 `ShopRecoGrid`：有宠物档案 → 「为宠物推荐」（栏头同首页）；无档案 →「Mungkin kamu suka」+ `GET /shop/products/top?limit=6`；卡片同首页、可「＋」直接加购（加购成功后本页从空态切到列表态）。
2. **C7**：`cartLoadFailed` + `commonRetry`（沿用）。
3. **C8**：游客态 `cartGuestTitle` / `cartGuestMessage` / `cartGuestCta`（「Masuk」→ `/login`），不发任何 `/me` 请求（沿用 `CartController.build` 游客返回空车的规则，`cart_repository.dart:120-130`）。

### AC7 — 去结算与估算参数 `[L0]`（FR-133.8、D-9、epics 8.3）
1. 「Checkout」→ `context.push('/shop/checkout')`（CART 模式，带勾选行由服务端按 `selected=true` 且有效决定）；结算页 `initState` 上报 `toko_checkout_page_viewed{entry: "cart"}`（7.1 已实现，本 story 只加测试证明从购物车进入时 `entry=cart`）。
2. `CartRepository.view({int? regionKecamatanId})`：`CartController.build` 读 `shopDeliveryAreaProvider`（8.2 已建，包 `AppPrefs.shopDeliveryArea`）的 `kecamatanId`，有则作为 query 带上；**变更类接口同样带**（后端 7 个出口都接受，T0 核对后改）。
3. `CartView` 加 `CartEstimate? estimate`（`lowestFee`、`payable`、`saved`、`freeShippingProgress`（7.1 的 `FreeShippingProgress`）、`reason`），缺键为 null；`estimate == null`（旧后端）时底栏回落到现有 `selectedSubtotal` 显示，标签用「Belum termasuk ongkir」。

### AC8 — 文案 `[L0]`（PRD §6 C 组、P-10）
按 Dev Notes「文案表」两份 arb 同时加 / 改，`flutter gen-l10n`；复用 key 原文不改；源码不硬编码用户可见字符串。

### AC9 — 回归与还原度 `[L0]` `[L2]`
1. `flutter analyze` 零警告、`flutter test` 全绿；按设计打破的断言迁移改写（见「测试」），不删、不 skip。
2. **还原度 ≥ 95%**（L2）：C1–C8 逐屏对照打分，记入 Completion Notes。

## Tasks / Subtasks

- [x] **T0 开工核对**：3.1 `estimate` 字段名、变更接口是否也带 `estimate`、失效行勾选 422；8.1 `ShopRecoGrid` 与 8.2 `shopDeliveryArea` 实际 API；有出入先改本文件
- [x] **T1 数据层**（AC7）：`domain/shop_cart.dart` 加 `CartEstimate`；`cart_repository.dart` `view(regionKecamatanId:)`
- [x] **T2 页面**（AC1–AC6）：`cart_page_v3.dart`；删 v2；路由
- [x] **T3 文案**（AC8）
- [x] **T4 测试** + `flutter analyze && flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（核 `dev_1.4.2` @ 2c507755）

| 位置 | 现状 |
|---|---|
| `A/features/shop/presentation/cart_page_v2.dart`（715 行） | 游客 / 加载 / 失败 / 空 / 列表分派 :81-110；撤销 `_offerUndo` :137、`_undoRemove` :163；标题拼接 :179-185；`_list` :187；失效分组头 `_invalidHeader` :210-255；底栏 `_bottomBar` :258-281（标签 `cartSelectNoneHint` / `cartTotalLabel`、金额 `selectedSubtotal`、Checkout `push('/shop/checkout')` :278）；`_ValidLine` :352（库存标签 :441、删除 :465、勾选 :495、改数量 :506）；`_InvalidLine` :530（失效行勾选「如实反映 + 可点」:551-577、「Cari mirip」`go('/shop')` :635、原因 :676-683）；`_CenteredState` :686 |
| `A/features/shop/data/cart_repository.dart` | `view()` 无参 :23；`CartController.build` 游客返空车 :120-130；`refresh` :132；`cartProvider` :172；`cartItemCountProvider` :176 |
| `A/features/shop/domain/shop_cart.dart` | `CartLine`（`isValid`、`selected`）:40-106；`CartView`（无 `estimate`）:108-183 |
| arb | `cartSelectNoneHint`「Pilih minimal satu produk untuk checkout」（UI 第四步：新底栏放不下，缩为「Pilih produk dulu」）；`cartTotalLabel`「Total ({count} barang)」（新底栏不再用） |

### UI 逐屏要点（C1–C8）

| 屏 | 要点 |
|---|---|
| C1 有默认地址 | 原有模块全部保留（标题含失效行数、勾选 / 图 / 名 / 规格 / 单价 / 步进器、qty 1 时「−」变删除、触顶「Sisa stok N」、底栏全选 + 合计 + Checkout）；新增：顶部「Dikirim ke {Kecamatan}」（询价用默认地址，点开换地址）、底栏免运进度、合计改为预估应付（所选商品 + 最低快递运费 − 最优券 − 满额免运）+「Hemat Rp X」；示例 349.000 + 10.000 − 20.000 − 10.000 = 329.000，Hemat 30.000，门槛 500.000 差 171.000；布局：同一卡片内分隔、图 64→72、名 11.5→13 |
| C2 无地址或询价失败 | 顶部「Pilih alamat untuk hitung ongkir」，合计标「belum termasuk ongkir」，不含运费 |
| C3 含失效商品 | 11-05：失效原因改为图片底部深色小标签；原有失效分组「Tidak Tersedia (n)」+「Hapus semua」（二次确认照线上）、图上原因、价格变灰、「Cari mirip」（去 Toko）/「Hapus」；改线上：失效行勾选框不可勾，后端不再默认勾选失效行 |
| C4 未勾选 | 底栏提示缩为「Pilih produk dulu」、金额变灰、Checkout 禁用（灰底）、免运进度不显示 |
| C5 删除可撤销 | 删除后底栏 / 标题 / 免运差额同步（285.000 + 10.000 − 免运券 10.000 = 285.000，满减门槛不再满足，差 215.000，「Checkout (1)」）；toast 5 秒 +「Urungkan」，不弹确认 |
| C6 空 | 原有空态文案（去 Toko）；11-05 新增下方推荐（与首页为宠物推荐同一模块：浅紫底块、三列、可「＋」）；有档案用为宠物推荐（栏头同首页），无档案「你可能喜欢」取运营权重前 6；只剩失效行不算空：显示失效分组、无底栏 |
| C7 加载失败 | 原有 `cartLoadFailed` + `commonRetry` |
| C8 未登录 | 游客态 `cartGuestTitle` / `cartGuestMessage` / `cartGuestCta`（进登录页） |

### 文案表（PRD §6 C 组；新 key 两份 arb 都加）

| 屏 | key（建议） | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| C1 | `cartShipTo` | Dikirim ke Kec. {x} | Ship to {x} |  |
| C2 | `cartChooseAddressForShipping` | Pilih alamat untuk hitung ongkir | Choose an address to calculate shipping |  |
| C1 | `cartEstimatedTotal` | Estimasi total | Estimated total |  |
| C2 | `cartShippingNotIncluded` | Belum termasuk ongkir | Shipping not included | 底栏标签 |
| C4 | `cartSelectNoneHint`（**改值**） | Pilih produk dulu | Select items first |  |
| 7.1 已建 | `tokoSavedAmount`「Hemat Rp {x}」、`tokoFreeShipRemaining`、`tokoFreeShipReached` | — | — |  |
| 8.1 已建 | `tokoMightLike`「Mungkin kamu suka」（C6 无档案） | — | — |  |
| 复用 | `cartTitle`、`cartCheckout`、`cartSelectAll`、`cartEmptyTitle` / `Message` / `Cta`、`cartLoadFailed`、`commonRetry`、`cartGuestTitle` / `Message` / `Cta`、`cartRemovedUndo`、`cartUndo`、`cartInvalidSection`、`cartClearInvalid`、`cartClearInvalidConfirmTitle` / `Body` / `Yes`、`cartFindSimilar`、`cartRemoveShort`、`cartReasonDelisted` / `OutOfStock` / `Unavailable`、`cartMaxStock`、`cartStockError`、`cartGenericError`、`cartDecrease` / `cartIncrease` / `cartRemoveLine`、`cartSelectLine` | — | — | 原文不改 |

§6 C1 行里的小写「belum termasuk ongkir」（C1 预估应付旁注）在定稿各屏中没有出现位置，本 story 不建该 key；`cartTotalLabel` 新页不再引用，不删（9.4 统一清理）。

### 拍板点（按建议实现，Completion Notes 复述）

1. 不做「点合计上拉看明细」层：UI C1 说明写「结构稿未画明细层，第三步补」，定稿未画、`estimate` 也没有明细字段；合计旁不画 `expand_less`。
2. 送达行点击进地址簿（有地址）/ 新增地址（无地址），不在购物车内做地址抽屉（购物车只按默认地址估算，PRD「购物车询价的收货地用默认地址」）。
3. 「Checkout (n)」的 n = 已勾选件数（`selectedCount`，与 UI C1「(3)」= 1 + 2 件一致）。

### 实现提示

- 失效行勾选框：`ShopCheckbox(value: false, onChanged: null)`（`shop_controls.dart:141` 的不可点态，`shop_widgets_test`「不可点时不回调」已覆盖组件行为）。
- 撤销逻辑原样搬迁（含 :113-136 的长注释），这是 stag 回归 R-3 的修复点。
- 底栏建议新建 `_CartBottomBar`（三段：全选 / 金额区两行 / 按钮），不改 `ShopBottomBarWithTotal`（结算以外的旧页可能仍在用）。
- `CartController.build` 读 prefs 是异步的：`final area = (await AppPrefs.create()).shopDeliveryArea;`，或注入一个 `shopDeliveryAreaProvider`（8.2 若已建则复用）。

### 架构约束

- P-1：预估应付、已省、免运差额全取服务端。
- 游客不发 `/me` 请求（FR-93A）。
- `/shop` 一律 `go`。
- 不对整文件 `dart format`。

### 与前后 story 的边界

- 7.1：结算页 CART 模式与 `entry` 埋点已实现。
- 8.1：`ShopRecoGrid`、商品卡「＋」与规格弹层已实现。
- 8.2：`AppPrefs.shopDeliveryArea` 已实现。
- 9.4：地址簿 `serviceable` 标注；电商旧 key 清理。

### 测试

**L0（云端可跑）**
- `test/shop/cart_page_v3_test.dart`（新，取代 `cart_page_v2_test.dart`，后者随 v2 删除；用例迁移）：
  - 原样迁移：「单店模型：多行只有一个总计」；「失效行渲染在独立分组、合计不含它」「失效行主出口是找相似」「失效行价格转灰」「认不出的失效原因照样算失效」；「库存不明时 + 不可点」「触顶出现剩余库存提示」「未触顶不显示」；「② 点掉一行：setSelected 调一次、remove 零次」；「④ 全不选：结算按钮禁用，且点击不发任何请求」；「⑤ 车里有失效行时全选框仍呈现已全选」；「全选框点击走 setAllSelected」；「标题计数含失效商品」「空车不渲染结算条」「只有失效行时也不渲染结算条」；布局不溢出（411dp、1.3 倍字号）；R-3 撤销五条；「qty=1 时行内出现删除图标」。
  - **按设计改写**：「① 三行全选：底栏显示 selectedSubtotal」→「底栏显示 `estimate.payable`（329.000）+ Hemat 30.000 + Checkout (3)」；「③ 失效行勾选框占位且如实反映服务端的 selected」「③b 取消勾选失效行 → setSelected(false)」「③c 失效行的勾选不进任何合计」「失效行取消勾选后还能再勾回来」→ 改为「失效行勾选框恒未勾、点击不调 `setSelected`、不进合计」（PRD 11-05 推翻「可点」做法）。
  - 新增：C1 送达行文案（默认地址 / 本地地区）与点击目的地；免运进度三态（差额 / 已达 / 门槛 0 不画）；C2 `lowestFee == null` 文案与标签；C4「Pilih produk dulu」、金额灰、无进度、无 Hemat；C5 删除后用服务端新 `estimate`（285.000、差 215.000、Checkout (1)）；C6 有档案推荐 / 无档案「Mungkin kamu suka」+ top 6、推荐卡「＋」加购后切到列表态；C7 / C8 原样；`estimate == null`（旧后端）回落 `selectedSubtotal`；Checkout 跳 `/shop/checkout`。
- `test/shop/cart_repository_estimate_test.dart`（新）：`CartView.fromJson` 读 `estimate` 各字段、缺键为 null；`view(regionKecamatanId: 42)` 带 query、null 时不带。
- `test/shop/checkout_page_v3_test.dart`（补，7.1 文件）：从 `/shop/checkout` 无参数进入上报 `entry: cart`（若 7.1 已覆盖则引用，不重复）。
- `test/shop/shop_widgets_test.dart`、`test/shop/cart_attribution_test.dart`、`test/shop/cart_guest_add_test.dart`：保持绿。
- `test/l10n/microcopy_rules_test.dart`：保持绿。
- 跑：`cd petgo_app && flutter analyze && flutter test`。

**L1（本地 / stag，`BITESHIP_MODE=stub`）**：有默认地址时显示最低运费与预估应付；删除默认地址后切 C2；游客在详情选地区 → 登录 → 无地址时购物车按该地区估算；把某行商品下架 → 进失效组且勾选框不可点；结算只结已勾选有效行。
**L2（本地模拟器）**：C1–C8 逐屏还原度 ≥ 95%，记入 Completion Notes。

> 云端只能跑 L0：Completion Notes 必须写「**L1/L2 待本地验收**」。

### Project Structure Notes

- 新增：`A/features/shop/presentation/cart_page_v3.dart`、`test/shop/cart_page_v3_test.dart`、`test/shop/cart_repository_estimate_test.dart`。
- 删除：`cart_page_v2.dart`、`test/shop/cart_page_v2_test.dart`（用例迁入 v3）。
- 改动：`app_router.dart`、`data/cart_repository.dart`、`domain/shop_cart.dart`、`l10n/app_*.arb`。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-shop.md#Story 8.3]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md#3.0 旅程 A；3.1 免运进度；3.7 购物车；3.10 购物车运费 / 其余细节；§6 C 组]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md#D-9、P-1、§1.2 第 11 条]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html#C1–C8]

## 待确认 / 发现的规划冲突

1. **变更接口是否带 `estimate`**：D-9 / 3.1 只写「`GET /me/cart` 响应新增 `estimate`」。若加购 / 改数量 / 删除 / 勾选的返回体不带，删除后（C5）底栏会短暂显示旧值；本 story 在 `estimate` 缺失时变更后补一次 `refresh()`，并在 Completion Notes 记录实际情况。变更接口也不带 `regionKecamatanId`，若服务端变更响应按「无地区」估算，同样以 `refresh()` 结果为准。
2. **合计上拉明细层**：UI C1 说明提到「点合计上拉看明细（第三步补）」，定稿未画、`estimate` 无明细字段；本 story 不做。
3. **送达行点开「换地址」**：UI 说「点开换地址」，但 PRD 规定购物车只用默认地址估算、结算页才可按单换地址；本 story 点开进地址簿（用户可在那里改默认），不做购物车内的临时地址。

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5（claude-opus-5-5），云端 headless，bmad-dev-story 流程，只到 L0。

### Debug Log References

- 开工前基线：`flutter gen-l10n` + `flutter analyze` → No issues found。
- `flutter analyze` → No issues found（完成后）。
- `flutter test`（全量）→ +4712 ~3（3 个既有 skip），All tests passed。
- 新增：`test/shop/cart_page_v3_test.dart` 48 例、`test/shop/cart_repository_estimate_test.dart` 12 例，全绿。
- 复审修复后：`flutter analyze` → No issues found；`flutter test` 全量 → +4719 ~3，All tests passed。
- `bash scripts/ci/check-flyway-versions.sh origin/main` → OK（本 story 无迁移）。

### Completion Notes List

- **L1/L2 待本地验收**：L1 —— 有默认地址显示最低运费与预估应付；删默认地址切 C2；游客在详情选地区 → 登录 → 无地址时按该地区估算；下架某行 → 进失效组、勾选框不可点；结算只结已勾选有效行；库内历史勾着的失效行 → 结算页 409 失效卡片可「移出购物车」。L2 —— C1–C8 逐屏还原度 ≥ 95% 打分（AC9.2）、送达行 / 底栏三段 / 失效原因小标签视觉。
- T0 名字对齐（已改本文件「接口契约」与 AC7.2）：`reason` 实际为 `NO_DESTINATION`（非 `NO_ADDRESS`），另有 `NO_SELECTION`；`estimate` 另有 `goodsAmount / discountCouponAmount / shippingDiscount`（App 读入、不展示）；后端 7 个出口都带 `estimate` 且都接受 `regionKecamatanId` → App 全部透传，不补 refresh；8.2 已建 `shopDeliveryAreaProvider`，`CartController` 复用（`build` 中 `watch`：在详情页换地区后购物车自动按新地区重估）。
- 数据层：`domain/shop_cart.dart` 新增 `CartEstimate`（缺键 / 无 `payable` → null，旧后端回落）；`CartView.estimate`；`CartRepository` 7 个方法加可选 `regionKecamatanId`（null 不发键）；`CartController._mutate` 统一取本机地区。三个测试替身（`cart_attribution_test` / `cart_guest_add_test` / `product_detail_page_v3_test`）随签名补可选参数，行为不变。
- 页面：新建 `cart_page_v3.dart`（`CartPageV3`），删除 `cart_page_v2.dart` 与 `cart_page_v2_test.dart`（用例迁入 v3 测试），路由 `/shop/cart` 改指新页。撤销 `_offerUndo` / `_undoRemove` 挂页面 State（R-3 注释原样迁移）。有效行同一 `ShopCard` 内 `ShopDivider` 分隔、图 72、名 13；失效分组单独一张卡。
- 送达行：kecamatan 取默认地址 → 否则本机地区；`estimate.lowestFee == null` 且 `reason != NO_SELECTION`、或两处都没有地区 →「Pilih alamat untuk hitung ongkir」。点击有地址 `/me/addresses`、无地址 `/me/addresses/new`，返回后 `invalidate(addressListProvider)` + `cartProvider.refresh()`。只在有有效行时渲染。
- 底栏 `_CartBottomBar`：免运进度行（未勾选不画；门槛 0 / null 不画；remaining 0 →「Dapat potongan ongkir」）+ 全选 / 「Estimasi total」或「Belum termasuk ongkir」+ `estimate.payable`（紫色等宽）+「Hemat」（saved 0 不画）/「Checkout (selectedCount)」。未勾选：「Pilih produk dulu」、金额灰、按钮 disabled 且不带件数、不发请求。`estimate == null` 回落 `selectedSubtotal` +「Belum termasuk ongkir」。App 无任何金额计算（P-1）。
- 失效行（改线上）：勾选框 `ShopCheckbox(value: false, enabled: false, onChanged: null)`，即便服务端旧数据下发 true 也恒未勾；原因改图片底部深色小标签；价格灰；「Cari mirip」go `/shop`、「Hapus」remove（新增失败 toast）。
- 3.1 遗留「库内仍勾着的失效行挡结算、对外 selected=false」：购物车侧引导出口为失效分组「Hapus semua」（二次确认）与行内「Hapus」；另「全不选」作用于全部行（含失效行）也可清掉库内勾选位；结算页 7.1 已有失效卡片 + 「移出购物车」兜底。本页不新增文案。
- 文案：新增 `cartShipTo` / `cartChooseAddressForShipping` / `cartEstimatedTotal` / `cartShippingNotIncluded`（EN/ID，PRD §6 C 组原文），`cartSelectNoneHint` 改值（ID「Pilih produk dulu」/ EN「Select items first」）；复用 `tokoSavedAmount` / `tokoFreeShipRemaining` / `tokoFreeShipReached` / `tokoMightLike` 等。`cartTotalLabel` 新页不再引用，未删（9.4 清理）。
- 拍板点复述：①不做合计上拉明细层、不画 `expand_less`；②送达行进地址簿 / 新增地址，不做购物车内地址抽屉；③「Checkout (n)」n = `selectedCount`。
- AC7.1 `entry=cart`：`checkout_page_v3_test.dart`「无参数 → CART；上报 toko_checkout_page_viewed{entry: cart}」已覆盖（7.1），本 story 不重复，只测 Checkout push `/shop/checkout`（无参数）。
- 埋点：沿用 `toko_cart_page_viewed`、`toko_cart_clear_invalid_tapped`，无新事件。

#### 复审修复（2026-11-05）

1. **失效行「Hapus」防重入**：`_InvalidLine` 改为有状态，await 期间按钮 `loading`、`onTap: null`，双击只发一次 DELETE；失败 toast。测试「复审修复：「Hapus」防重入」。
2. **CartController 乱序覆盖**：build 与全部写操作共用**单调请求序号**（取号在任何 await 之前），只接受最新一次发出的请求的结果：写操作结果过时即丢弃；build 晚到且期间已有更新的写结果先到 → 让位返回该写结果。补充：最新一次写失败、且它之前还有请求的结果被丢弃 / 仍在路上 → `invalidateSelf()` 重拉一次 GET 对齐（只有单个写失败时仍保持原状态、不重拉）。同时覆盖跨控件并发（行勾选 + 全选）。测试组「🔴 CartController 乱序响应不回退」4 例（慢 GET + 快 POST；A 行勾选慢 + 全选快；最新失败触发重拉；单个失败不重拉）。
3. **送达行地址未知态**：`addressListProvider` 加载中文案显示「—」（不先闪本机地区）；加载中 / 出错时点击一律进 `/me/addresses`（不进 new，避免造出第二个地址）。注意 Riverpod 3 失败后自动重试期间是「loading + error」，按「出错」处理（文案回落本机地区 / 选地址）。测试 2 例。

#### 待确认

1. ~~变更接口是否带 `estimate`~~：已核实 7 个出口都带、都接受 `regionKecamatanId`，App 全透传，不补 refresh。
2. 送达行在 `NOT_SERVICEABLE`（有默认地址但不可配送）时按 AC3 显示「Pilih alamat untuk hitung ongkir」；PRD §6 无「该地址不可配送」的购物车文案，若设计想区分需补文案（待设计确认）。
3. 只剩失效行时不渲染送达行（无底栏、无估算意义）；UI 定稿未明确，待设计确认。
4. C-11「待设计确认」：本 story 用到的文案均取自 PRD §6，无 UI 稿独有文案。

### File List

- 新增：`petgo_app/lib/features/shop/presentation/cart_page_v3.dart`
- 新增：`petgo_app/test/shop/cart_page_v3_test.dart`
- 新增：`petgo_app/test/shop/cart_repository_estimate_test.dart`
- 删除：`petgo_app/lib/features/shop/presentation/cart_page_v2.dart`
- 删除：`petgo_app/test/shop/cart_page_v2_test.dart`
- 修改：`petgo_app/lib/core/router/app_router.dart`
- 修改：`petgo_app/lib/features/shop/data/cart_repository.dart`
- 修改：`petgo_app/lib/features/shop/domain/shop_cart.dart`
- 修改：`petgo_app/lib/l10n/app_en.arb`、`petgo_app/lib/l10n/app_id.arb`
- 修改（测试替身签名）：`petgo_app/test/shop/cart_attribution_test.dart`、`petgo_app/test/shop/cart_guest_add_test.dart`、`petgo_app/test/shop/product_detail_page_v3_test.dart`
- 修改：本 story 文件

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev），依据决策日志 S-0 ~ S-9、PRD §6、架构增量、UI 定稿与 `dev_1.4.2` @ 2c507755 代码现状。
- 2026-11-05：dev 完成 L0（Status → review）：购物车 v3（C1–C8）、`CartEstimate`、`regionKecamatanId` 全出口透传、失效行不可勾、文案；T0 名字对齐见 Completion Notes。L1/L2 待本地验收。
- 2026-11-05：复审修复 3 项（失效行删除防重入、CartController 单调请求序号、送达行地址未知态），见 Completion Notes「复审修复」。
