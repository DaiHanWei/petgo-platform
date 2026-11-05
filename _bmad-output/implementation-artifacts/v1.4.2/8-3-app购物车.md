# Story 8.3: 购物车（App，C1–C8）

Status: ready-for-dev

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
2. `CartRepository.view({int? regionKecamatanId})`：`CartController.build` 读 `AppPrefs.shopDeliveryArea?.kecamatanId`，有则作为 query 带上；变更类接口不带（服务端按同规则，若不一致见待确认 1）。
3. `CartView` 加 `CartEstimate? estimate`（`lowestFee`、`payable`、`saved`、`freeShippingProgress`（7.1 的 `FreeShippingProgress`）、`reason`），缺键为 null；`estimate == null`（旧后端）时底栏回落到现有 `selectedSubtotal` 显示，标签用「Belum termasuk ongkir」。

### AC8 — 文案 `[L0]`（PRD §6 C 组、P-10）
按 Dev Notes「文案表」两份 arb 同时加 / 改，`flutter gen-l10n`；复用 key 原文不改；源码不硬编码用户可见字符串。

### AC9 — 回归与还原度 `[L0]` `[L2]`
1. `flutter analyze` 零警告、`flutter test` 全绿；按设计打破的断言迁移改写（见「测试」），不删、不 skip。
2. **还原度 ≥ 95%**（L2）：C1–C8 逐屏对照打分，记入 Completion Notes。

## Tasks / Subtasks

- [ ] **T0 开工核对**：3.1 `estimate` 字段名、变更接口是否也带 `estimate`、失效行勾选 422；8.1 `ShopRecoGrid` 与 8.2 `shopDeliveryArea` 实际 API；有出入先改本文件
- [ ] **T1 数据层**（AC7）：`domain/shop_cart.dart` 加 `CartEstimate`；`cart_repository.dart` `view(regionKecamatanId:)`
- [ ] **T2 页面**（AC1–AC6）：`cart_page_v3.dart`；删 v2；路由
- [ ] **T3 文案**（AC8）
- [ ] **T4 测试** + `flutter analyze && flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

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

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev），依据决策日志 S-0 ~ S-9、PRD §6、架构增量、UI 定稿与 `dev_1.4.2` @ 2c507755 代码现状。
