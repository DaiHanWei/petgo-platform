---
baseline_commit: d1e8f5d3
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 4
story: 4-2
ad: [AD-S6]
decisions: [SD-6, C5]
fr: [SHOP-FR-04]
---

# Story 4-2: 购物车勾选（App）

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2）。
> 本 story **只有 App 端**，后端能力全部由 4-1 提供。

## 🔴 前置：依赖 4-1 先上线，不得抢跑

**4-1（购物车部分结算 · 后端）必须先合入并上线，本 story 才能开工。** `sprint-status-v1.3.0-shop-v2.yaml:37` 把这条写成强顺序，理由是代码自己已经写过一遍 ——
`petgo_app/lib/features/shop/presentation/cart_page_v2.dart:10-22` 逐字如下：

```
/// ## 🔴 设计稿的「每行勾选框」**刻意不实现**
///
/// 设计稿给每行画了勾选框，底部总计写「已勾选的可购商品件数」。
/// 但下单接口是 `placeOrder(addressToken)` —— **整车下单，没有行选择的概念**。
///
/// 三条路都不能走：
/// - 画勾选框但不影响下单 → 用户勾掉一行仍然会被买走，这是**能造成资损的谎**；
/// - 勾选框全选中且禁用 → 一个点不动的控件，比没有更让人困惑；
/// - 结算前把未勾选的行删掉 → 用破坏性操作模拟一个查询语义，取消结算就丢数据。
///
/// 故本版式**不画勾选框**，底部总计 = 全部有效行。
/// 补齐需要后端支持「行选择」（购物车行加 selected 位，或下单接受 skuToken 列表），
/// 属接口能力缺口，不是版式取舍。
```

**「画一个不影响下单的勾选框＝能造成资损的谎」** —— 本 story 落地时，必须先确认线上（或联调目标环境）后端已具备 `selected` 能力；未具备就画勾选框，等于亲手实现这段注释里点名禁止的第一条路。
**同时**：本 story 完成后，把上面这段注释**替换**为「已由 4-1/4-2 补齐」的新说明，不要留着一段与代码相反的自记笔记。

## Story

As a 用户，
I want 在购物车里勾选要买的商品并看到实时小计，
so that 我下单前就知道这一单要付多少、不用为了买一件先删掉另一件。

---

## Context

### 现状

| 文件 | 现状 |
|---|---|
| `petgo_app/lib/features/shop/presentation/cart_page_v2.dart` | `:10-22` 上述自记注释；`:23-28` 另记「凑单条（免运进度）也不画，因为购物车接口无免运门槛字段」、批量管理态 `Ubah` 不画。当前底栏合计 = 全部有效行 |
| `petgo_app/lib/features/shop/domain/shop_cart.dart` | `CartInvalidReason:15`（`delisted('DELISTED'):17` / `outOfStock('OUT_OF_STOCK'):20` / `unavailable(''):23`，`fromApi:30` 未知非空值降级为 `unavailable`、**永不降级成有效**）· `CartLine:40` 字段 `:54-67`（`skuToken, productToken, productName, specName, price, qty, mainImageUrl, availableStock, invalidReason`）· getter `isValid:69` / `lineTotal:71` / `canIncrease:77` · `fromJson:79` · `CartView:98` 字段 `:107-117`（`lines, invalidLines, subtotal, itemCount`）· `empty:122` · `fromJson:125` |
| `petgo_app/lib/features/shop/data/cart_repository.dart` | `CartRepository:18`（`view():23` · `add:34` · `setQty:49` · `remove:57` · `clearInvalid:62`）· `CartController extends AsyncNotifier<CartView>:93` |
| 测试替身 | `petgo_app/test/shop/cart_page_v2_test.dart:420` 的 `_FakeCartController`，经 `:33` 的 `cartProvider.overrideWith(...)` 注入。**本仓已无 `mock_backend.dart`**，这个替身就是 C5 条文里「App mock」的等价落点 |
| 文案 | `petgo_app/lib/l10n/app_en.arb` + `app_id.arb`。⚠️ **App 侧 ARB 只有 en / id 两语**（三语是后端 `messages_*.properties` 的事）。UX-S2 说的「三语」在 App 侧按两语执行 |

### 4-1 提供的后端能力（本 story 的输入）

- `PUT /api/v1/me/cart/items/{skuToken}/selected?selected=true|false`
- `PUT /api/v1/me/cart/selection?selected=true|false`（全选 / 全不选）
- `CartView` 新增 `selectedSubtotal` / `selectedCount`，`CartLine` 新增 `selected`
- `subtotal` / `itemCount` **语义未变**（全部有效行合计 / 件数）
- `GET /checkout` 与 `POST /shop-orders` 只结算选中且有效的行；一行都没选 → 422

---

## Acceptance Criteria

**AC1 · 每行勾选框 + 顶部全选，默认全选** `[L0]`
**Given** 购物车有若干有效行
**When** 打开购物车页
**Then** 每个有效行左侧有勾选框，页面顶部（或底栏左侧）有「全选」控件 `[L0]`
**And** 首次进入时全部有效行为选中态 —— 由后端 `selected` 字段驱动（新加购的行后端默认 `true`），**前端不自己造默认值** `[L0]`
**And** 勾选状态跨页面返回、跨冷启动保持（因为它落在服务端）`[L2]`

**AC2 · 底栏按选中行实时更新** `[L0]`
**Given** 3 个有效行全部选中
**When** 取消勾选其中 1 行
**Then** 底栏小计由 `selectedSubtotal` 驱动、件数由 `selectedCount` 驱动，立即变化 `[L0]`
**And** 免运提示（若本版渲染）同样按选中集判断 `[L0]`
**And** 🔴 前端**不自行把选中行的 `price*qty` 加起来** —— 金额只认后端下发的 `selectedSubtotal`，防止前后端两套算法漂移导致「页面显示的钱和实际扣的钱不一致」`[L0]`

**AC3 · 失效行不可勾选、不计入小计** `[L0]`
**Given** 某行处于失效态（`invalidReason != null`：下架 / 售罄 / 后续 Epic 6 的停用品类）
**When** 渲染该行
**Then** 其勾选框为禁用态（不可点），视觉上与有效行可区分 `[L0]`
**And** 该行永不计入底栏小计与件数 `[L0]`
**And** 判定条件写成 `line.isValid`（即 `invalidReason == null`），**不是**枚举白名单 —— Epic 6 新增失效原因时 `CartInvalidReason.fromApi:30` 会降级成 `unavailable`，用 `isValid` 才能自动覆盖 `[L0]`

**AC4 · 全不选时结算按钮禁用并提示** `[L0]`
**Given** 用户取消了全部勾选
**When** 看底栏
**Then** 结算按钮为禁用态，并有一句提示（新 ARB key，en + id 两语）`[L0]`
**And** 禁用态下点击不发起任何网络请求 —— 不允许「点了才由后端 422 告诉你」`[L0]`

**AC5 · 取消勾选不删除商品** `[L0/L2]`
**Given** 用户取消勾选某行
**When** 该行状态更新
**Then** 只调选择端点，**绝不调 `DELETE /items/{skuToken}`**；商品仍在列表里，数量不变 `[L0]`
**And** 下单成功后，未选中的商品**仍留在购物车**（由 4-1 的清车范围保证）`[L2]`

**AC6 · 契约与 L0 全绿** `[L0]`
**Then** `flutter analyze` 零 issue `[L0]`
**And** `petgo_app/test/shop/cart_page_v2_test.dart` 扩充后全绿，覆盖：默认全选渲染、取消一行后小计变化、失效行勾选框禁用、全不选后结算按钮禁用 `[L0]`
**And** C5 的 App 两处（data DTO + 测试替身）已同步，`flutter test` 全绿 `[L0]`

**AC7 · 模拟器验收** `[L2]`
**Given** 连 staging 后端、真账号、购物车里 3 件商品
**When** 只勾选 1 件并下单
**Then** 勾选变化时底栏金额同步；结算页金额 = 底栏金额；下单成功后购物车剩另外 2 件 `[L2]`
**And** 取消勾选再重进购物车，勾选状态保持 `[L2]`

---

## Tasks / Subtasks

- [ ] **T0 · 开工前确认（阻塞项）**
  - [ ] 确认 4-1 已合入且目标联调环境已部署（`GET /api/v1/me/cart` 返回体里能看到 `selected` / `selectedSubtotal` / `selectedCount`）
  - [ ] 🔴 未确认前**不得**提交任何画勾选框的代码

- [ ] **T1 · data DTO 对齐（C5 ③）**
  - [ ] `petgo_app/lib/features/shop/domain/shop_cart.dart`：
        `CartLine` 加 `final bool selected;`（`fromJson:79` 读 `json['selected'] as bool? ?? true` —— 🔴 **缺省 `true`**，老后端或字段缺失时表现为全选，与后端默认一致）
        `CartView` 加 `final int selectedSubtotal;` / `final int selectedCount;`（`fromJson:125` 同样给缺省：`selectedSubtotal` 缺省取 `subtotal`、`selectedCount` 缺省取 `itemCount`）
  - [ ] `CartView.empty:122` 同步补两个 0
  - [ ] 不要给 `CartLine` 加 `entrySource` / `triggerType` —— 后端虽然下发，但客户端刻意不持有（见 `CartView` 后端 javadoc）

- [ ] **T2 · repository 两个方法**
  - [ ] `petgo_app/lib/features/shop/data/cart_repository.dart`：`CartRepository` 加 `Future<CartView> setSelected(String skuToken, bool selected)` 与 `Future<CartView> setAllSelected(bool selected)`，`dio.put` + `queryParameters`（照 `setQty:49` 的写法，它就是 `PUT` + query param）
  - [ ] `ApiPaths` 里补两个路径常量（照 `petgo_app/lib/core/network/api_paths.dart` 既有购物车常量风格）
  - [ ] `CartController:93` 加对应方法，返回值直接 `state = AsyncData(view)`

- [ ] **T3 · 页面改造**（AC1~AC5）
  - [ ] `cart_page_v2.dart`：每个有效行加 `Checkbox`（或项目既有的勾选组件，先在 `petgo_app/lib/features/shop/presentation/widgets/` 与 `shared/widgets/` 里找现成件再造新的）
  - [ ] 顶部/底栏加「全选」控件：选中态 = 全部**有效**行都 selected；点击时调 `setAllSelected`
  - [ ] 🔴 **全选控件的选中态判定要排除失效行** —— 车里有 1 个永远选不上的失效行时，全选框必须能显示成「已全选」，否则它永远点不亮
  - [ ] 底栏金额/件数改读 `selectedSubtotal` / `selectedCount`
  - [ ] 失效行的勾选框 `onChanged: null`（禁用），不是隐藏 —— 位置对齐才不会让列表左边缘参差
  - [ ] 结算按钮：`selectedCount == 0` → `onPressed: null` + 提示文案
  - [ ] 🔴 **替换 `:10-22` 的自记注释**，改写成「行选择已由 4-1（后端 `shop_cart_items.selected`）+ 4-2 补齐」；`:23-28` 关于凑单条与批量管理态的说明**保留不动**（那两项本 story 不做）
  - [ ] 🔴 乐观更新要谨慎：勾选是**服务端状态**，建议直接用端点返回的 `CartView` 覆盖 state；若做乐观更新，失败必须回滚并提示，不能留下「看着勾上了其实没勾上」

- [ ] **T4 · 文案**（AC4）
  - [ ] `petgo_app/lib/l10n/app_en.arb` + `app_id.arb` 各加：全选标签、全不选时的结算禁用提示（key 沿用 `cart*` 前缀，如 `cartSelectAll` / `cartSelectNoneHint`），每个 key 配 `@` description
  - [ ] 印尼语参考既有 `cartTitle` / `cartInvalidSection` 的措辞风格
  - [ ] 🔴 不硬编码任何印尼语字符串进 dart（UX-S2）
  - [ ] 跑一次 `flutter gen-l10n`（或项目既有生成方式），确认 `app_localizations_*.dart` 已更新

- [ ] **T5 · 测试**（AC6）
  - [ ] `petgo_app/test/shop/cart_page_v2_test.dart`：更新 `:420` 的 `_FakeCartController`（**C5 ④**）以支持新字段与两个新方法
  - [ ] 新增 widget 测试：
        ① 三行全选 → 底栏显示 `selectedSubtotal`
        ② 点掉一行 → 底栏数字变化且 `setSelected` 被调用一次、`remove` **零次**（AC5 的可测化）
        ③ 失效行的 Checkbox `onChanged == null`
        ④ 全不选 → 结算按钮 `onPressed == null`
        ⑤ 全选框在「车里有失效行」时仍能呈现已全选
  - [ ] 检查其它引用 `CartView` / `CartLine` 构造器的测试是否编译不过：`petgo_app/test/shop/cart_guest_add_test.dart`、`cart_attribution_test.dart`、`checkout_page_v2_test.dart`
  - [ ] `flutter analyze` + `flutter test` 全绿

- [ ] **T6 · 模拟器验收**（AC7）
  - [ ] 装 **Android 模拟器**（用户口径：「模拟器」= Android）跑 stag 包，按 AC7 走一遍并截图
  - [ ] 云端 session 做不到这一步，Completion Notes 标注「L2 待本地验收」

---

## Dev Notes

### 🔴 为什么金额只认 `selectedSubtotal`，不在前端加

购物车页在前端把选中行金额加起来，和后端 `CheckoutService` 用选中集算，是两套独立实现。它们只要有一处四舍五入、一处对失效行的判定口径不同，用户看到的数就和实际扣的钱不一样 —— 而这正是 `cart_page_v2.dart:10-22` 那段注释在防的那类资损。**前端只做展示，金额的唯一事实源是服务端下发的字段。**

### 失效判定为什么必须用 `isValid` 而不是枚举

`shop_cart.dart:30` 的 `CartInvalidReason.fromApi` 对未知非空值返回 `unavailable`（刻意「永不降级成有效」）。Epic 6 会在后端加第三种失效原因（停用品类）。届时老客户端拿到未知字符串会落进 `unavailable`，`isValid` 自动为 false —— 但如果本 story 把勾选框禁用条件写成 `reason == delisted || reason == outOfStock`，停用品类的行就会变成「可勾选、可结算、然后被后端 422 打回」。**写 `line.isValid`。**

### C5 四处同改 —— 本 story 负责后两处

| # | C5 要求 | 本仓实际文件 | 谁交付 |
|---|---|---|---|
| ① | 后端 record | `petgo-backend/.../shop/cart/dto/CartView.java` | 4-1 |
| ② | 后端契约 test | `petgo-backend/src/test/java/com/tailtopia/shop/cart/dto/CartViewContractTest.java`（4-1 新建） | 4-1 |
| ③ | App data DTO | `petgo_app/lib/features/shop/domain/shop_cart.dart` | **4-2** |
| ④ | App mock | ⚠️ 本仓已无 `mock_backend.dart`，等价落点是 `petgo_app/test/shop/cart_page_v2_test.dart:420` 的 `_FakeCartController` | **4-2** |

### 本 story 明确不做

- **凑单条 / 免运进度条**（`cart_page_v2.dart:23-28` 记录的另一个接口缺口：`CartView` 没有免运门槛字段，门槛只在 `ShippingQuoteService` 里、需要 `addressToken` 才算得出）。4-1 没有加这个字段，本 story 也不加，注释保留。
- **批量管理态 `Ubah`**（同上，设计稿有、本版不做）。
- **停用品类的失效判定**（Epic 6 第二批）。

### Project Structure Notes

改动集中在 `petgo_app/lib/features/shop/{domain,data,presentation}` 与 `lib/l10n`，不新建 feature 目录。购物车 provider 是既有的 `cartProvider`，不新增用户维度 provider，因此不涉及「换账号缓存重置登记」。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S6]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md SD-6]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md#SHOP-FR-04]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 4-2]
- [Source: _bmad-output/implementation-artifacts/v1.3.0/sprint-status-v1.3.0-shop-v2.yaml:37（4-1 → 4-2 强顺序）]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#C5]
- [Source: petgo_app/lib/features/shop/presentation/cart_page_v2.dart:10-28]

---

## 验收与交付

| 层 | 怎么验 | 在哪跑 |
|---|---|---|
| **L0** | `flutter analyze` 零 issue；`flutter test` 全绿（含 T5 五条新用例）；ARB 两语 key 集合相等 | 云端 / 本地 |
| **L1** | 本 story 无后端改动，无独立 L1；联调时按 AC7 用真后端验证 | — |
| **L2** | Android 模拟器（memory：「模拟器」= Android）连 staging：勾选 → 金额同步 → 只买一件 → 购物车剩两件 → 重进保持勾选态，全程截图 | 本地 |

**交付物**：`shop_cart.dart` / `cart_repository.dart` / `api_paths.dart` / `cart_page_v2.dart` / 两个 ARB + 生成物 / `cart_page_v2_test.dart` 及受影响的其它购物车测试。
**不交付**：任何后端文件。

## Definition of Done

- [ ] 🔴 T0 已确认 4-1 上线；本分支 diff 中没有「勾选框不影响下单」的中间态提交
- [ ] AC1~AC7 全部满足（L2 未跑的写明「待本地验收」并列出具体步骤）
- [ ] 底栏金额与件数只来自 `selectedSubtotal` / `selectedCount`，代码中没有前端求和
- [ ] 失效行判定用 `line.isValid`，不是枚举白名单
- [ ] 取消勾选路径中没有任何 `remove` / `DELETE` 调用（有测试断言）
- [ ] 全不选时结算按钮禁用且不发请求
- [ ] `cart_page_v2.dart:10-22` 的旧注释已替换为现状说明；`:23-28` 的凑单条与 `Ubah` 说明原样保留
- [ ] 新文案全部进 ARB（en + id），dart 里无硬编码印尼语
- [ ] C5 的 ③④ 两处已同改，`flutter test` 全绿
- [ ] `flutter analyze` + `flutter test` 通过
