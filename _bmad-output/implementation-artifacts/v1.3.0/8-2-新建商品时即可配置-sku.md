---
baseline_commit: 5f5982cb
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 8
story: 8-2
ad: [F-8, AD-S13]
decisions: [AB-19A, 纪律 3B]
fr: [SHOP-FR-11]
---

# Story 8-2: 新建商品时即可配置 SKU

Status: ready-for-dev

> 自包含 story，可本地或云端执行（云端只跑到 L0）。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
>
> 🔴 **前置：admin 主题 Epic 10 完成**（shop-v2 第二批，SD-16）。Epic 10 的 Story 10.3 明写「**商品主体与 SKU 两个 `<form>` 现状保留**」—— 本 story 要在**新建态**打破这个「两个表单」的前提，务必先读 T0。
>
> 🔴 **本 story 要改的，正是当年出过「静默覆盖 SKU」事故的那个端点。** 事故经过、当时的修法、以及为什么修法「不能只靠模板碰巧有那一行」，全写在下面的 Context 里。**动手前必须读完那一节。**
>
> 本 story **零数据库改动**（编号字段由 6-4 提供，见「与 6-4 的依赖」）、**零新增端点**。

## Story

As a 运营，
I want 新建商品时就能配规格，
so that 不用先保存一次、再重新打开页面才能加第一个规格。

---

## Context

### 🔴 事故：`{id}` 路径变量把商品 id 绑进了 `ShopSkuForm.id`

**事故形态**（2026-08-18 补 L1 集成测试时抓到，全仓当时只此一处）：

Spring 的 `ExtendedServletRequestDataBinder` 会把 **URI 模板变量一并绑进 `@ModelAttribute` 对象**（仅当同名请求参数缺席时）。当时 SKU 端点写作 `POST /admin/shop/products/{id}/skus` + `@ModelAttribute ShopSkuForm form`，而 `ShopSkuForm` 恰好有一个 `id` 字段（`petgo-backend/src/main/java/com/tailtopia/admin/shop/dto/ShopSkuForm.java:18`）。于是：

1. **商品 id** 被绑进了 `ShopSkuForm.id`；
2. `AdminShopProductService.upsertSku` 用 `form.getId() == null` 判新建/更新（今天在 `:96`），于是**「新建规格」永远走成「更新规格」分支**；
3. 轻则恒报「规格不存在」（`:101-102`，运营一个规格都建不出来）；
4. 🔴 **重则静默覆盖**：`shop_products` 与 `shop_skus` 两张表都是 BIGSERIAL，新店里 id 撞号很常见；一旦撞上、且那个 SKU 恰好属于本商品（`:103-106` 的归属校验就过了），就会**把一个既有规格改掉**而不是新建一个 —— 页面上还会提示「已保存」。

**为什么线上没炸**：模板里恰好渲染了一个空的隐藏域
`petgo-backend/src/main/resources/templates/admin/shop-product-form.html:254` —— `<input type="hidden" name="id"/>`。
浏览器会提交 `id=`（**空串，不是缺席**），请求参数在场 ⇒ Spring 跳过 URI 变量绑定。
👉 **正确性被寄托在「模板碰巧有那一行」上**：删掉那行、或换个调用方（curl / 脚本 / htmx 局部提交 / 新建态那个还不存在的隐藏域）就立刻复现。

**修复现状 —— 已修，路径变量已改名**：
`petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopProductController.java:253-263`，注释原文（逐字）：

```
253:    // 🔴 路径变量叫 {productId} 而不是 {id} —— 这不是命名洁癖，是**必须的**：
254:    //    Spring 的 ExtendedServletRequestDataBinder 会把 URI 模板变量一并绑进 @ModelAttribute
255:    //    （仅当同名请求参数缺席时）。若这里叫 {id}，商品 id 就会被绑进 ShopSkuForm.id，
256:    //    于是「新建规格」永远走成「更新规格」分支：
257:    //      · 找不到该 id 的 SKU → 恒报「规格不存在」，运营一个规格都建不出来；
258:    //      · 🔴 更糟：两张表都是 BIGSERIAL，新店里 shop_skus.id 与 shop_products.id 撞号很常见，
259:    //        一旦撞上且那个 SKU 恰属本商品，就会【静默覆盖既有规格】而不是新建。
260:    //    页面上之所以没出事，只是因为模板恰好渲染了一个空的 <input name="id"/>（请求参数在场
261:    //    → URI 变量被跳过）。**把正确性寄托在「模板碰巧有那一行」上不成立**：
262:    //    删掉那行、或换个调用方（curl / 脚本）就复现。2026-08-18 补 L1 时抓到。
263:    @PostMapping("/admin/shop/products/{productId}/skus")
```

方法签名 `:265-267`：`upsertSku(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long productId, @ModelAttribute ShopSkuForm form, RedirectAttributes ra)`。

回归测试也在：`petgo-backend/src/test/java/com/tailtopia/admin/shop/web/AdminShopProductEndpointIntegrationTest.java:256-276`，`@DisplayName("🔴 浏览器形态（空的 id 隐藏域）也必须是【新建】而不是更新 —— 两次提交出两个规格")`，显式 `.param("id", "")` 连提两次、断言 `count == 2`。

### 🔴 同一个陷阱在本 story 里还有第二个入口

商品更新端点 **今天仍然叫 `{id}`**：

```
240:    @PostMapping("/admin/shop/products/{id}")
241:    @PreAuthorize(EDIT_AUTH)
242:    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
243:            @ModelAttribute("form") ShopProductForm form, RedirectAttributes ra) {
```

它今天**安全**，唯一的理由是 `ShopProductForm`（`petgo-backend/src/main/java/com/tailtopia/admin/shop/dto/ShopProductForm.java:22-51`）**没有名为 `id` 的字段**。
👉 而本 story 恰恰要往 `ShopProductForm` 上加字段（SKU 行）。**只要有人图省事加一个 `id` 或 `skuId`，同一个事故立刻在商品表单上重演** —— 而这次没有「模板碰巧有那一行」兜着。这是本 story 的头号风险，也是 AC3 的由来。

### 现状：SKU 区只在编辑态渲染

`shop-product-form.html`：

- `:221` — `<div class="s-section" th:if="${productId} != null">`（逐字确认：**这就是把 SKU 区锁在编辑态的那一行**，注释在 `:220`「SKU 维护（AB-10B），仅编辑态展示」）
- `:224-248` — 已有规格只读表（规格名 / 售价 / 净含量 / 退货规则 / 可售库存 / 进货价[权限门控]）
- `:250` — **第二个 `<form>`**：`th:action="@{'/admin/shop/products/' + ${productId} + '/skus'}"`
- `:254` — `<input type="hidden" name="id"/>`（事故里那个「碰巧存在」的隐藏域）
- `:255-283` — 规格名 / 售价 / 净含量 / 退货规则 / 进货价（`th:if="${canEditCost}"`，`:280`）
- `:286` — 保存规格按钮

而商品主 `<form>` 在 `:40-218`（`th:action` 在 `:41` 按 `${productId} == null` 分叉）。**两个 `<form>` 是并列的，不是嵌套的** —— HTML 不允许表单嵌套，这是本 story 最主要的结构约束。

新建态路径：`AdminShopProductController.createForm`（`:128-138`）只放 `form` / `productId=null` / `existingImages=List.of()` / 枚举。

👉 **今天运营的实际操作是**：填完 20+ 个字段 → 保存 → 跳到 `redirect:/admin/shop/products/{id}`（`:233`）→ 页面重新加载 → 才看得见 SKU 区 → 一个一个加规格。

### 现状：新建失败会把**整屏已填内容**全丢掉

```
226:    @PostMapping("/admin/shop/products")
...
234:        } catch (AppException e) {
235:            ra.addFlashAttribute("error", msg.resolve(e));
236:            return "redirect:/admin/shop/products/new";
237:        }
```
重定向到 `createForm`（`:130-131`），后者无条件 `model.addAttribute("form", new ShopProductForm())` —— **一个空表单**。
服务端校验点密集（`AdminShopProductService.validate:143-165`：商品名必填 + ≤60、品牌、品类、**主图**、物种、详情、保质期、退货规则、图集 ≤8，再加 `validateFeedingGuide:174-193` 的正整数 / 下限<上限 / 区间不重叠），每一条都会把运营刚填的一屏字段清空。
👉 **AC4「新建失败时已填规格不丢失」必须连带把整个表单的回填一起做掉** —— 只保住规格行、丢掉商品字段是荒谬的。

### 现状：SKU 创建会连带建库存行

`AdminShopProductService.upsertSku`（`:90-119`）：
- `validateSku(form)`（`:92` → `:208-216`：规格名必填、价格非负、净含量为正）
- `form.getId() == null` → `ShopSku.create(tokens.generate(), productId, specName.trim(), price, netWeightG, returnPolicy)`（`:97-98`）
- `created` ⇒ `inventory.ensureRow(sku.getId())`（`:113`）—— 🔴 注释 `:112` 明写：**缺这行会让下单锁定静默判成售罄**
- 审计 `AuditActions.SHOP_SKU_UPSERTED`（`:115-117`，常量在 `AuditActions.java:128`）
- 进货价走**单独方法** `updateCostPrice`（`:127-138`），由 Controller 在校验 `shop.cost_edit` 后才调（`:270-275`）；类注释 `:27-29` 说明这是**结构性门控**：「忘记判权限」不再是可能发生的失误

### 与 6-4 的依赖（编号自动生成）

Epic 6 的 **Story 6-4「SPU / SKU 编号」** 负责：`shop_products.spu_no` varchar(16) UNIQUE NOT NULL、`shop_skus.sku_no` varchar(24) UNIQUE NOT NULL，规则 `SPU-%06d` / `<spu_no>-%02d`，专用序列（**不用主键 id**），实体 `updatable=false`，同迁移内按 id 升序回填存量。

👉 本 story 的 **AC5「一次提交创建商品 + 3 个规格，编号自动生成」直接依赖 6-4**。执行顺序见 `sprint-status-v1.3.0-shop-v2.yaml`：Epic 6 在 Epic 8 之前。若开工时 6-4 尚未落地，见 AC5 的降级条款。

---

## Acceptance Criteria

**AC1 · SKU 区放开到新建态，一次保存带多个规格** `[L0]`
**Given** 打开 `/admin/shop/products/new`
**When** 填完商品字段并在规格区填 3 行（规格名 + 售价，净含量与退货规则可空）
**Then** 点一次「保存」，商品与 3 个规格**在同一个事务里**一起落库 `[L1]`
**And** 每个新建 SKU 都建了库存行且 `actual = 0`（复用 `AdminShopProductService:113` 的 `inventory.ensureRow`，🔴 **不得跳过** —— 缺行会让下单锁定静默判成售罄）`[L1]`
**And** 任一规格行校验失败（规格名空 / 价格为负 / 净含量 ≤0，判据沿用 `validateSku:208-216`）⇒ **商品也不落库**，全有或全无 `[L1]`
**And** 编辑态的 SKU 区与那个独立 `<form>`（模板 `:250-289`）**行为逐字不变** `[L1]`
**And** `shop-product-form.html:221` 的 `th:if="${productId} != null"` 被改成「编辑态渲染 A 形态、新建态渲染 B 形态」，**不是简单删掉条件**（两态的提交路径完全不同）`[L0]`

**AC2 · 不做多维规格** `[L0]`
**Then** 规格名仍是**单一自由文本**（`ShopSku.specName`，`petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopSku.java:41`，len 40）`[L0]`
**And** diff 中**没有**任何「规格维度 / 属性 / 选项」相关的新表、新列、新枚举、新 DTO `[L0]`
**And** 不引入「选了颜色×尺码自动生成 N 个 SKU」这类笛卡尔积生成逻辑 `[L0]`

**AC3 · 🔴 硬 AC：路径变量名不得与 `@ModelAttribute` 表单字段同名** `[L0]`
**Then** `POST /admin/shop/products/{productId}/skus`（`AdminShopProductController:263`）的路径变量**仍叫 `productId`**，`:253-262` 那段注释**一字不删** `[L0]`
**And** 🔴 商品更新端点 `POST /admin/shop/products/{id}`（`:240`）的路径变量**改名为 `{productId}`** —— 它绑定 `ShopProductForm`，而本 story 正在往这个表单类上加字段；今天不撞只是因为 `ShopProductForm` 恰好没有 `id` 字段，这是运气不是设计 `[L0]`
**And** **URL 字符串不变**（`/admin/shop/products/{productId}` 与 `/admin/shop/products/{id}` 是同一条 URL 模式），因此**不构成端点变更**（AB-19A 不受影响）；模板 `:41` 的 `th:action` 拼接方式也不受影响 `[L0]`
**And** 🔴 **新增的 SKU 行字段一律不得叫 `id`**，建议 `skuRowSpecName[i]` / `skuRowPrice[i]` 这类带前缀的名字；若实施者选择在 `ShopProductForm` 上挂一个 SKU 行子对象列表，子对象里**也不得有 `id`** `[L0]`
**And** 🔴 **在代码注释中标注原因**：在被改名的 `{productId}`（`:240`）处补一段注释，指向 `:253-262` 那段已有说明，写明「本端点绑定 `ShopProductForm`；一旦该表单类出现与路径变量同名的字段，商品 id 会被静默绑进去 —— 见下方 `{productId}/skus` 的完整事故说明」`[L0]`
**And** 补一条 L1 回归：用 **curl 形态**（**完全不传 `id` 参数**，而不是传空串）POST 到 `/admin/shop/products/{productId}/skus` 两次 → 产出 **2 个** SKU
  —— 🔴 既有测试 `AdminShopProductEndpointIntegrationTest:256-276` 传的是 `.param("id", "")`（浏览器形态），**恰好绕开了真正的 URI 变量绑定路径**，覆盖不到本条 `[L1]`

**AC4 · 新建失败时已填内容不丢失（含规格）** `[L0]`
**Given** 新建态填了商品字段 + 3 行规格，其中保质期说明留空（会被 `validate:157` 拒）
**When** 提交
**Then** 回到新建表单页，**商品字段与 3 行规格全部原样回填**，运营只需补那一个字段 `[L1]`
**And** 错误提示仍是既有的 `error` flash + `msg.resolve(e)`（Controller `:235`），🔴 **不得**改成向页面吐 RFC 9457 裸 JSON（`:222-224` 的既有纪律）`[L0]`
**And** 实现方式：`create` 的 catch 分支追加 `ra.addFlashAttribute("form", form)`；`createForm`（`:130-131`）改为**仅当 model 中尚无 `form` 时**才 `new ShopProductForm()` —— 🔴 flash 属性会被自动并入重定向目标的 model，无条件 `addAttribute` 会把它覆盖掉 `[L0]`
**And** 回填的 `form` 里**不得含进货价**（`costPrice` 属 `shop.cost_edit` 门控；若新建态的规格行带进货价，无权限时该值在服务端就被丢弃，回填也不得把它带回页面）`[L0]`
**And** 单测覆盖「校验失败 → flash 里有 `form` 且规格行数量与内容正确」`[L0]`

**AC5 · 一次提交创建商品 + 3 个规格，编号自动生成** `[L1]`
**Given** Story 6-4 已落地（`shop_products.spu_no` / `shop_skus.sku_no` + 专用序列）
**When** 在新建态一次提交 1 个商品 + 3 个规格
**Then** 商品获得 `SPU-%06d` 形态的唯一编号，3 个规格获得 `<spu_no>-01/-02/-03` `[L1]`
**And** 3 个规格的 `sku_no` 序号**与页面上的行顺序一致**（第 1 行 → `-01`）`[L1]`
**And** ⚠️ **降级条款**：若开工时 6-4 尚未合入，本 AC 降级为「3 个规格建出且 `public_token` 各不相同（22 位 Base62，`ShopTokenGenerator:29`）」，并在 Completion Notes 写明「编号验收待 6-4 合入后补」—— 🔴 **不得**为了让本 AC 绿而在本 story 里自己加编号列 / 自己造序列（那会与 6-4 的迁移撞号、撞规则）`[L0]`

**AC6 · 写操作三件套与端点纪律** `[L0]`
**Then** `@PreAuthorize`：新建态的规格随商品创建端点 `POST /admin/shop/products`（`:227`，`EDIT_AUTH`）一并提交，不新增端点；diff 中**不得出现新的 `@PostMapping`**（AB-19A，本 story 不在 AD-S13 的例外清单里）`[L0]`
**And** 审计：新建商品记 `AuditActions.SHOP_PRODUCT_CREATED`（`AdminShopProductService:61-62`）；🔴 **随商品一次建出的每个 SKU 各记一条 `SHOP_SKU_UPSERTED`**（沿用 `:115-117` 的写法），不要因为「同一次提交」就合并成一条 —— 审计是逐对象的 `[L0]`
**And** 审计 detail **不含进货价数值**（沿用 `:121-126` 的既有纪律：审计页可见范围与进货价权限不同）`[L0]`
**And** 三语 key：新增文案写进 **4 个** bundle（`messages.properties` 英文基线 + `_zh_CN` + `_en` + `_id`），`AdminMessagesParityTest`（`petgo-backend/src/test/java/com/tailtopia/shared/i18n/AdminMessagesParityTest.java:29`）绿；模板零硬编码中文 `[L0]`

**AC7 · L0 全绿与回归** `[L0]`
**Then** `mvn -B clean package` 通过 `[L0]`
**And** 既有 `AdminShopProductEndpointIntegrationTest`（`:37`）与 `AdminShopProductServiceTest` 全绿，特别是 `:256-276` 那条浏览器形态回归 `[L1]`
**And** 本次 diff 中**没有新增 Flyway 迁移文件** `[L0]`

**AC8 · 后台实操验收** `[L2]`
**Given** staging 后台，`shop.product_edit` 权限
**When** 走「新建商品 → 一次填 3 个规格 → 保存」
**Then** 一次跳转即到编辑页，3 个规格都在、库存都是 0、都能正常被下单锁定 `[L2]`
**And** 故意漏填一个必填项提交 → 回到新建页，商品字段与 3 行规格**全都还在** `[L2]`
**And** 编辑态再加第 4 个规格（走原来那个独立 `<form>`）→ 正常新建，**没有覆盖前 3 个中的任何一个** `[L2]`

---

## Tasks / Subtasks

- [ ] **T0 · 先确认 Epic 10 的落地形态与「两个 `<form>`」约束**（前置）
  - [ ] 读当时树里的 `shop-product-form.html`：Epic 10 Story 10.3 若已套模板 D 四分组卡，「规格与价格」是第 4 张卡、**有自己的保存钮**
  - [ ] 🔴 关键冲突点：Epic 10 的验收原文是「商品主体与 SKU 两个 `<form>` 现状保留」，而本 story 在**新建态**必须把规格行放进商品那一个 `<form>`（HTML 禁止表单嵌套，新建态又还没有 productId 可拼 action）
  - [ ] 结论按此执行：**编辑态维持两个 `<form>` 不动**（Epic 10 的约束成立），**新建态是新形态**（那时第二个 `<form>` 本来就不存在）。把这个结论写进模板注释
  - [ ] 若 Epic 10 商城组尚未完成 → 停下，本 story 属第二批，不得抢跑

- [ ] **T1 · `ShopProductForm` 加规格行字段**（AC1/AC2/AC3）
  - [ ] 照抄同文件里喂量三列表的既有范式（`ShopProductForm.java:49-51` 的 `feedWeightMinKg` / `feedWeightMaxKg` / `feedGramsPerDay`，均为 `List<Integer>`，模板用 `th:name="'feedWeightMinKg[' + ${i} + ']'"`，见 `shop-product-form.html:199-206`）
  - [ ] 建议字段：`List<String> skuSpecName` / `List<Long> skuPrice` / `List<Long> skuNetWeightG` / `List<ReturnPolicy> skuReturnPolicy`（+ 可选 `List<Long> skuCostPrice`）
  - [ ] 🔴 **不得**出现名为 `id` 的字段（AC3）；也不要叫 `skuId`（今天不撞，但会诱导后来者）
  - [ ] 加一个 `List<ShopSkuForm> skuRows()` 组装方法（照抄 `feedingGuide():62-77` 的写法：按索引取、整行留空则忽略、`at(list, i):79-81` 的越界保护）
  - [ ] 🔴 **部分填写的行不能静默忽略**：规格名有、价格空 ⇒ 交给 `validateSku` 报错，不要当空行跳过（否则运营会以为存了）

- [ ] **T2 · `AdminShopProductService.create` 接规格行**（AC1/AC6）
  - [ ] `create`（`:51-64`）在 `products.save(p)` 之后、`return` 之前，遍历 `form.skuRows()` 调既有 `upsertSku(p.getId(), row, actorAccountId)`
        —— 🔴 **复用 `upsertSku`，不要复制一份新建逻辑**：`ensureRow`（`:113`）、`validateSku`（`:92`）、审计（`:115-117`）都在里面
  - [ ] `create` 本就 `@Transactional`（`:51`），所以任一行抛 `AppException` ⇒ 整单回滚（AC1 的「全有或全无」自然成立）；**不要给规格行加 `REQUIRES_NEW`**
  - [ ] 进货价：`create` 签名里**不接进货价**（类注释 `:27-29` 的结构性门控）。若新建态要支持填进货价 ⇒ 照 Controller `:269-275` 的做法，在 **Controller 层**取出、判 `shop.cost_edit`、再调 `service.updateCostPrice`
        —— **实施时确认**：是否要在新建态就暴露进货价字段；不做也完全可以（保存后在编辑态补），不做则模板里不渲染该列，本条跳过
  - [ ] 规格行数量上限：与 `AdminShopListingService.skuCap()`（Controller `:121-122` 已在用）对齐 —— **实施时确认**是否要在新建时就挡；至少不能让一次提交绕过上限

- [ ] **T3 · 🔴 路径变量改名 + 注释**（AC3）
  - [ ] `AdminShopProductController:240` → `@PostMapping("/admin/shop/products/{productId}")`，`:242` 的 `@PathVariable long id` → `long productId`，方法体 `:245` / `:250` 同步
  - [ ] 在 `:240` 上方补注释（内容见 AC3），指向 `:253-262` 的完整事故说明
  - [ ] 🔴 **不改 URL 字符串**，`:41` 的模板 `th:action` 与所有既有测试的 URL 不受影响；确认 `AdminShopProductEndpointIntegrationTest` 里没有按变量名断言的地方
  - [ ] `{id}/list`（`:285`）与 `{id}/delist`（`:298`）**可以不改**（它们不绑 `@ModelAttribute`），但若一并改名更一致 —— **实施时确认**，改就一次改齐，别改一半
  - [ ] 模板 `:254` 的 `<input type="hidden" name="id"/>` **保留**，并在旁边补一行注释：「这一行不是安全保证，真正的保证是控制器的 `{productId}` 命名；见 `AdminShopProductController:253-262`」

- [ ] **T4 · 模板：新建态规格区**（AC1/AC4）
  - [ ] `shop-product-form.html:221` 的 `th:if="${productId} != null"` 拆成两支：
        - 编辑态（`productId != null`）：现状 `:222-289` 原样保留（只读表 + 独立 `<form>`）
        - 新建态（`productId == null`）：在**商品主 `<form>` 内部**（`:40-218` 之间，建议放在「每日建议喂量」卡之后、底部固定条 `:213-217` 之前）渲染 N 行规格输入
  - [ ] 行数照喂量表的做法给固定若干行：`th:each="i : ${#numbers.sequence(0, 5)}"`（`:199`），`th:name="'skuSpecName[' + ${i} + ']'"`、`th:value="${i < form.skuSpecName.size()} ? ${form.skuSpecName[i]} : ''"` —— **这个 `th:value` 写法同时把 AC4 的回填做掉了**
  - [ ] 退货规则下拉复用 `${returnPolicies}`（`putEnums:326-332` 已放进 model，两态共用）
  - [ ] 提示文案（「新建时可一次填多个规格，留空行会被忽略；保存后可在本页继续增删」）走 `#{...}`
  - [ ] 🔴 新建态**不渲染** `<input type="hidden" name="id"/>` —— 新建态根本没有 SKU id 这个概念，渲染它只会复活事故的温床

- [ ] **T5 · 表单回填**（AC4）
  - [ ] `AdminShopProductController.create` 的 catch（`:234-237`）补 `ra.addFlashAttribute("form", form)`
  - [ ] `createForm`（`:130-131`）改成：`if (!model.containsAttribute("form")) { model.addAttribute("form", new ShopProductForm()); }`
        🔴 无条件 `addAttribute` 会把 flash 带回来的表单覆盖成空表单，这是整条 AC4 的关键一行
  - [ ] 回填时剔除进货价（AC4 最后一条）
  - [ ] ⚠️ `update` 的 catch（`:247-249`）今天是 `redirect` 回详情页、靠 `detail` 重新从库里读 —— **编辑态不受本条影响，不要一并改**

- [ ] **T6 · 四个 message bundle**（AC6）
  - [ ] 新 key 前缀沿用 `admin.shop.sku.*`（既有键见 `messages_zh_CN.properties:1479-1486`）；建议 `admin.shop.sku.newFormTitle` / `admin.shop.sku.newFormHint`
  - [ ] 4 个文件同批写（`messages.properties` 是英文基线且必须是三语超集，见 `AdminMessagesParityTest:24-25`）
  - [ ] 跑 `AdminMessagesParityTest`

- [ ] **T7 · 测试**（AC3/AC4/AC5/AC7）
  - [ ] **L1（`AdminShopProductEndpointIntegrationTest`）**
        - [ ] 🔴 新增「curl 形态」回归：POST `/admin/shop/products/{productId}/skus` **完全不传 `id` 参数**、连提两次 → `count == 2`（AC3；现有 `:256-276` 传的是空串，覆盖不到 URI 变量绑定这条路）
        - [ ] 新增：一次 POST `/admin/shop/products` 带 3 组规格参数 → `shop_products` +1、`shop_skus` +3、三条库存行 `actual = 0`
        - [ ] 新增：同一请求里第 2 行价格为 `-1` → 400/重定向带 error flash 且 **`shop_products` 与 `shop_skus` 都零新增**（照抄 `:196-213` 那条「一行都不落库」的写法）
        - [ ] 新增：漏填保质期说明 → 重定向回 `/admin/shop/products/new`，flash 里的 `form` 含 3 行规格
  - [ ] **L0（`AdminShopProductServiceTest`）**：`skuRows()` 的空行忽略、部分填写行不被忽略、越界保护
  - [ ] 既有 `:256-276`、`:227-255`（新建 SKU 建库存行）、`:295-343`（进货价门控）全绿

- [ ] **T8 · 云端执行须知**
  - [ ] 云端只跑 L0：`mvn -B clean package` + `AdminMessagesParityTest` + `AdminShopProductServiceTest`
  - [ ] AC1/AC3/AC4/AC5/AC7 的 L1 部分与 AC8 全部留本地；Completion Notes 标注「L1/L2 待本地验收」
  - [ ] 若 6-4 未合入 → 按 AC5 降级条款执行并写明

---

## Dev Notes

### 为什么不给新建态也做一个 `POST …/skus` 端点

最省事的写法是：新建态也渲染那个独立 `<form>`，先 AJAX 建商品拿到 id、再逐个 POST 规格。**不要这么做**，三个理由：

1. **AB-19A 零端点变更**：本 story 不在 AD-S13 登记的例外清单里（例外只有品类管理、导入工作台、批量提交、两个导出）；
2. **半提交态**：商品建出来了、规格建到一半失败，运营手上就多了一个残缺商品，而页面上看不出来；
3. 🔴 **正是它把事故请回来**：新建态的那个隐藏 `<input name="id"/>` 不一定还在（本 story 的 T4 明确要求不渲染它）—— 而 3B 的纪律说得很清楚，**把正确性寄托在「模板碰巧有那一行」上不成立**。

### 「一次事务」的边界

`create`（`:51`）与 `upsertSku`（`:90`）都标了 `@Transactional`，默认传播 `REQUIRED` ⇒ 在 `create` 里调 `upsertSku` 会**加入同一个事务**，任一行抛异常整体回滚。这正是 AC1 要的语义。

⚠️ 别顺手给规格行加 `REQUIRES_NEW`（项目里 `REQUIRES_NEW` 是给事件监听器用的，见通知 `AFTER_COMMIT` 事故的修法）。这里加 `REQUIRES_NEW` 会造成「商品回滚了、规格却留下来」的孤儿行。

### 进货价为什么不能顺手接进 `create`

`AdminShopProductService` 类注释 `:27-29` 原文：「本类的写方法**不接收进货价**，它走单独的 `updateCostPrice`，由控制器在校验 `shop.cost_edit` 后才调用。这样『忘记判权限』就不再是可能发生的失误。」

这是**结构性门控**，不是风格偏好。如果新建态要带进货价，路径只有一条：Controller 层取出 → 判权限 → 建完 SKU 后逐个调 `service.updateCostPrice(skuId, cost, actor)`（照抄 `:269-275`）。

### 命名映射链提醒

`skuSpecName[i]` 这类索引参数名只存在于**后台表单**这一层，不进任何对外 JSON。DB `snake_case` ↔ Java/Dart `camelCase` ↔ JSON `camelCase` 的映射链不受影响。`ShopSku` 实体字段见 `ShopSku.java:32-67`（`id` / `publicToken` / `productId` / `specName` / `price(long)` / `netWeightG` / `costPrice` / `returnPolicy` / 时间戳）—— 🔴 **本 story 一列不加**。

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `admin/shop/web/AdminShopProductController.java` | `createForm:128-138` · `create:226-238`（catch `:234-237` 丢内容）· `update:240-251`（`{id}` + `ShopProductForm`）· `upsertSku:263-281`（`{productId}`，注释 `:253-262`） | `createForm` 条件化 `form`；`create` catch 回填；`{id}`→`{productId}` + 注释 | 🔴 `:253-262` 注释一字不删 · `:263` 的 `{productId}` · 本地 catch AppException（`:222-224`）· 进货价门控 `:269-275` |
| `admin/shop/service/AdminShopProductService.java` | `create:51-64` · `upsertSku:90-119`（`ensureRow:113`、审计 `:115-117`）· `validate:143-165` · `validateSku:208-216` · `updateCostPrice:127-138` | `create` 遍历规格行调 `upsertSku` | 类注释 `:27-29` 的结构性门控 · `ensureRow` · 逐 SKU 审计 · 审计不写进货价数值 |
| `admin/shop/dto/ShopProductForm.java` | `:22-51` 字段，**无 `id` 字段**；`galleryKeys():53` / `feedingGuide():62-77` / `at():79-81` | + 规格行 List 字段 + `skuRows()` | 🔴 **绝不加 `id`** · 喂量三列表的既有范式 |
| `admin/shop/dto/ShopSkuForm.java` | `id:18` / `specName:19` / `price:20` / `netWeightG:21` / `costPrice:22` / `returnPolicy:23` | **零改动** | `id` 字段本身（编辑态靠它区分新建/更新） |
| `templates/admin/shop-product-form.html` | 主 `<form>` `:40-218` · SKU 区 `:221`（`th:if productId != null`）· 独立 `<form>` `:250` · 隐藏 id `:254` · 喂量索引行 `:199-206` | `:221` 拆两态；新建态规格行进主 `<form>` | 编辑态 `:222-289` 逐字不变 · `:254` 保留并补注释 · 主图不写 `required`（`:157-159`） |
| `test/.../AdminShopProductEndpointIntegrationTest.java` | `:256-276` 浏览器形态（空串 id）回归 | + curl 形态回归 + 3 条新建态用例 | `:256-276` 原样保留 |
| `i18n/messages{,_zh_CN,_en,_id}.properties` | `admin.shop.sku.*:1479-1486`（zh 行号） | +2 个 key × 4 包 | 四包键集相等 + 基线是超集（`AdminMessagesParityTest:24-25`） |

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 8-2]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 6-4（编号规则与依赖）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#SHOP-FR-11]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/HANDOFF.md#三 纪律 3B（`@ModelAttribute` + `{id}` 路径变量会互撞，2026-08-18 新增）:111-119]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/HANDOFF.md#三 纪律 3（新 POST 端点必须本地 catch AppException）:107-109]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#0 前置事实 F-8]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#3 后台增量（商品表单 = 模板 C，新建态 SKU 区）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#B.3（AB-19A 零端点变更原文与例外清单）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#B.5（Story 10.3：商品表单两个 `<form>` 现状保留）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#D.2（`ShopSku` 字段与 `ShopTokenGenerator`）]

---

## 验收与交付

| 层 | 怎么验 | 在哪跑 |
|---|---|---|
| **L0** | `mvn -B clean package`；`AdminShopProductServiceTest` + `AdminMessagesParityTest` 绿；diff 中无新 `@PostMapping`、无新迁移、无多维规格痕迹 | 云端 / 本地 |
| **L1** | `AdminShopProductEndpointIntegrationTest` 全绿，含 4 条新用例（curl 形态不撞号 / 一次建 3 规格 / 一行失败整单回滚 / 失败回填带规格） | 本地（Docker postgres + redis） |
| **L2** | staging 后台：新建一次带 3 规格成功；漏填必填项后内容全在；编辑态再加第 4 个规格不覆盖前三个。截图留档 | 本地 |

**交付物**：`ShopProductForm`（规格行字段 + `skuRows()`）+ `AdminShopProductService.create` + `AdminShopProductController`（`createForm` / `create` catch / `{id}`→`{productId}` + 注释）+ `shop-product-form.html` + 4 个 message bundle + 5 条测试用例。
**不交付**：迁移、编号字段（属 6-4）、多维规格、新端点、`ShopSkuForm` 改动、编辑态行为改动。

## Definition of Done

- [ ] AC1~AC8 全部满足（L1/L2 未跑的写明「待本地验收」）
- [ ] 🔴 `AdminShopProductController:253-262` 的事故注释**一字未删**，`{productId}/skus` 命名未变
- [ ] 🔴 `POST /admin/shop/products/{id}` 已改名为 `{productId}`，并在该处补了指向事故说明的注释；URL 字符串未变
- [ ] 🔴 `ShopProductForm` 中**没有任何名为 `id` 的字段**；新增的规格行字段名带 `sku` 前缀
- [ ] 新增「curl 形态（完全不传 `id` 参数）」L1 回归已通过；既有 `:256-276` 浏览器形态回归仍绿
- [ ] 新建态一次提交的多个规格走的是**既有 `upsertSku`**，每个都调了 `inventory.ensureRow`、各记一条 `SHOP_SKU_UPSERTED`
- [ ] 任一规格行校验失败时，`shop_products` 与 `shop_skus` 都零新增（同一事务，无 `REQUIRES_NEW`）
- [ ] `createForm` 已改为「model 中无 `form` 时才 new」，失败回填经实测生效
- [ ] 回填内容不含进货价
- [ ] diff 中没有多维规格相关的表 / 列 / 枚举 / 笛卡尔积生成逻辑（AC2）
- [ ] diff 中**没有新增 Flyway 迁移文件**、没有新增编号列（编号归 6-4）
- [ ] 新增 message key 写进 **4 个** bundle，`AdminMessagesParityTest` 绿
- [ ] Completion Notes 记录：6-4 是否已合入（AC5 是否降级）、进货价是否在新建态暴露、`{id}/list` `{id}/delist` 是否一并改名、L1/L2 验收状态
