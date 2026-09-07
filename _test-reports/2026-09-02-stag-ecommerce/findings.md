# stag 电商测试发现（2026-09-02，S23 / 1.2.0-stag+12 / api-stag）

## D-1【P1】Toko 顶栏文字与图标白底白字，完全不可见
- 位置：Toko 首页顶栏
- 现象：控件树里三个元素都在——标题 `Shop` [48,133][202,208]、PawCoin 余额 `999`
  [702,104][876,236]、购物车 `Keranjang 1` [900,104][1032,236]，但截图中该区域
  像素恒为 RGB(255,255,255)。只有紫色填充的爪子圆底与购物车角标可见。
- 影响：用户看不到标题、看不到金币余额、**看不到购物车入口**（只能靠盲点角标位置）。
- 疑似来源：9ea9f3ca「电商 UI 默认切 v2 + 主题色改为 TailTopia 品牌紫」——
  顶栏前景色改白，背景仍为白。
- 复现：打开 App → Shop tab。100% 复现。
- ✅ **修复方案（2026-09-02 产品拍板，待切回 dev 后实施）**：
  顶栏图标与数字的颜色**随是否有 banner 而定** ——
  - **无顶部 banner**（当前 staging 即此情形，/api/v1/shop/banner 返回 204）
    → 图标与数字用**主体色**（TailTopia 品牌紫），在白底上可见；
  - **有 banner**（图片作背景）→ 图标与数字用**白色**，压在图上可见。
  即前景色不能写死，需按背景态切换。
- 🔑 **后台 banner 页自己写明了这个空态是设计内的**：
  「当前没有已上架的 banner —— App 的 Toko 顶部会显示白色顶栏
  （这是设计内的空态，不是故障）。」
  ⇒ 白色顶栏本身是**预期行为**，缺陷在于**前景色没有跟着空态适配** ——
  白底上仍用白色图标/文字。这正好印证产品定的修复方向。

## D-2【P1】10 张商品图 OSS 对象级 ACL 缺失，App 内显示为空占位
- 范围（93 个商品全量扫描）：83 张 200 正常，**10 张 403**。
  - `/demo/*` 8 张（Royal Canin Adult Dog / Whiskas / Pro Plan Puppy / Drontal /
    Vitamin Bulu & Kulit / Dentastix / Shampoo Anti Kutu / Sisir Grooming）
  - `/tst` 1 张（手工建的 test 商品）
  - `/seed/tailtopia/r-hcs-sbc-001.jpg` 1 张（SEBACARE OBAT TETES FLU）
- 错误：`AccessDenied — You have no right to access this object because of bucket acl.`
- 🔴 **不是 bucket 整体没开公共读，是对象级 ACL 漏设**：最后那张与另外 90 张
  同在 `/seed/tailtopia/` 下，其余全部 200，只有它 403 ⇒ 上传链路会**偶发**漏设 ACL，
  不是一次性历史遗留，会复发。
- 为什么首屏看着"全废"：「Picked for test」3 个 + 网格前 2 个恰好全在这 10 个里。
- 关联：与 fdaa3d70「素材公开读 ACL」同类。


## D-3【P2】英文基线 ARB 里混入印尼语值
- 现象：设备 locale=zh-Hans-CN → App 回退 **英文**（app.dart:241），但商品详情页
  显示 "Stok habis" / "Detail Produk" / 购物车标题 "Keranjang"。
- 根因：`petgo_app/lib/l10n/app_en.arb` 里这三个键的值本身就是印尼语——
  | 键 | en 现值 | 应为 |
  |---|---|---|
  | tokoOutOfStock (app_en.arb:2587) | "Stok habis" | Out of stock |
  | tokoDetailSectionTitle (app_en.arb:2595) | "Detail Produk" | Product Details |
  | cartTitle | "Keranjang" | Cart |
- 佐证：同页 tokoLastPrice/tokoSeeAlternatives 正常（en="last price"/"See alternatives"，
  id="harga terakhir"/"Lihat Alternatif"），说明确实走的英文包。
- 关联：与 a6e1d599「补齐英文基线键」同类，那次修后台，这次在 App ARB。

## D-4【P0】结算页「Total due」未扣 PawCoin，与实际应付差 999
- 页面：Checkout（2 件商品，Rp 120.000 + Rp 185.000）
- 明细区（正确）：
  Items subtotal 305.000 / Shipping +15.000 / Free shipping discount −15.000 / PawCoin −999
  ⇒ 应付 **304.001**
- 支付拆分区（正确）：PawCoin −Rp 999 + QRIS **Rp 304.001**
- 🔴 底部合计（错误）：**Total due = Rp 305.000**
- 差额恰为 999 = PawCoin 抵扣额 ⇒ Total due 漏减 PawCoin 那一行。
- 影响：用户看到的"要付多少"与真正扣款额不一致。金额类展示错误，
  且方向是**显示得比实收多**，容易引发客诉与对账争议。
- 复现：购物车任意商品 → Checkout，账户有 PawCoin 余额即可。

## 观察（非缺陷，待产品确认）
- PawCoin 余额以 `Rp 999` 形式展示（带 Rp 前缀）。PawCoin 是封闭币，
  与印尼盾同符号可能造成"这是钱"的误读。若 1 coin = 1 Rp 是刻意设计则忽略。

### D-4 补充：订单详情页同样复现，且同屏自相矛盾
- 下单后「Order detail」页：明细区 Items 305.000 / Shipping 15.000 /
  Free shipping −15.000 / **Total due Rp 305.000**，
  而正下方按钮写 **「Pay now Rp 304.001」** —— 同一屏两个不同的"应付"。
- 该页明细区**连 PawCoin 那一行都没有**（结算页至少还列了 −999），
  用户无从解释这 999 的差额从哪来。


---

# 后台 + 端到端验证（第三阶段）

## ✅ V-1 支付模拟器闭环，全流程通过（本次合并的重点验证项）
App 下单 → Pay now 生成 PENDING → 后台支付记录页出现该行、操作列渲染出
「成功 / 失败 / 过时」→ 点「成功」→ 提示条 `已模拟回调：… → PAID`
→ 状态变「已支付」、操作列回到 `—` → **App 自动跳转「My orders」，
订单 TOKO-20260902-000004 变为 Awaiting shipment**。
⇒ 合并后的 AdminPaymentController（模拟器 + 筛选 + 导出三者共存）工作正常，
  simulatorEnabled 在 stag 确为 true，模板条件 `simulatorEnabled and PENDING` 生效。

## ✅ V-2 支付记录状态筛选 + 汇总卡口径
- 筛 PENDING：下拉正确回显、汇总卡随之归零、空态文案「暂无符合筛选条件的支付记录」
  （即我合并时定的 admin.payments.empty）。
- 模拟成功后汇总卡：现金收入 IDR 463,001 · PawCoin 抵扣 IDR 999
  ⇒ 混合支付按「现金段 304,001 / 金币段 999」正确拆分，summarize() 口径无误。

## 🔴 D-4 定性收紧：同页两态自相矛盾，确认是遗漏而非设计
已支付态详情页合计行显示 **`Paid Rp 304.001 + Rp 999 PawCoin`** —— 正确且明确列出金币段。
待支付态同一位置显示 **`Total due Rp 305.000`** —— 既未扣减也无 PawCoin 行。
⇒ 同一页面两个状态对同一笔钱给出两种口径，待支付态属遗漏。

## D-3 追加实例：订单列表页 Tab
`app_en.arb` 中：
  "orderFilterKonsultasi": "Konsultasi"   ← 英文包里的印尼语
  "orderTypeEcommerce":    "Belanja"      ← 英文包里的印尼语
英文 locale 下 Tab 显示为 `All / Konsultasi / PawCoin / Belanja`，四个里两个是印尼语。

## ✅ V-3 用途 / 时间段筛选 + 翻页保留筛选
- 用途=商城订单：3 条，用途集合纯净，汇总 现金 304,001 + PawCoin 999。
- 用途 + 状态 组合：收窄到 1 条 ⇒ **与关系，非互斥**（符合设计）。
- 时间段起止同一天（from=to=2026-09-02）：返回 1 条 ⇒ 代码注释里
  「止含当天，服务层取次日 00:00 为上界」生效，未踩「同一天查不到」的坑。
- 翻页链接 `?page=1&userId=&purpose=&status=PAID&from=&to=` **完整携带筛选条件**
  ⇒ 模板注释警告的「点下一页悄悄回到全量」未发生。直接导航第 2 页：
  20 行、状态集合纯「已支付」、筛选回显完整。

## ✅ V-4 Excel 导出，内容与屏幕逐条一致
- HTTP 200、`application/vnd.openxmlformats-...sheet`、
  `attachment; filename="payment-records.xlsx"`、文件头 50 4b 03 04。
- purpose=SHOP_ORDER 时导出 3 行数据（+1 表头），支付号与屏幕上 3 条完全对应
  （-000120 PAID / -000118 EXPIRED / -000116 EXPIRED）。
- 表头 8 列，正确不含「操作」列；金额为数值型可直接求和。
- 观察（非缺陷）：purpose/status 导出的是原始枚举（SHOP_ORDER / PAID）而非中文标签，
  利于机器处理，但运营直接看文件时是英文枚举。

## ❌ D-5 撤销——是我的自动化假象，非后台缺陷
原记录「新建商品保存失败」不成立。后续用翻页做了对照实验：
- 点「下一页」链接 → URL 不变、停在第 1 页（与商品表单点保存后停在 /new 同一症状）
- 直接 URL 导航同一目标 → 正常工作（第 2/3 页，数据正确）
⇒ ego-browser 的 click 在该后台站点上未能激活链接/按钮，只触发当前页重载。
  商品保存失败是同一原因，**不是后台缺陷**。
  （早先那次 `fetch` POST 报 Failed to fetch，很可能是上一次 requestSubmit 已发起
   导航、把 fetch 请求取消了；同一页面的 GET fetch 导出接口工作完全正常。）
⚠️ 仍建议人工手动新建一个商品确认一次——本次未能用自动化证实该功能可用。

## D-6【P2】订单详情页顶部状态标签停在「On the way」，与实际 Delivered 不符
- 复现：后台把包裹标记送达 → 订单转 DELIVERED → App 订单详情页。
- 现象：页面顶部大字紫色标签显示 **「On the way」**，而同页下方
  Delivery history 最新一条是 **Delivered（2 Sep 10:59，实心点=当前态）**，
  订单列表卡片也显示 Delivered，后台订单状态为 DELIVERED。
- ⇒ 顶部状态标签未随订单状态更新，是全页最显眼的位置，用户第一眼读到的是错的。
- 截图：shots/24-order-detail-delivered.png

## D-7【P2】后台订单履约页时间显示为裸 UTC ISO，未按 WIB 格式化
- 现象：/admin/shop/orders 列表「下单时间」与详情页「下单/发货/签收时间」
  显示形如 `2026-09-02T03:03:24.172768Z`（UTC，含微秒）。
- 对照：支付记录页同一时刻显示 `2026-09-02 10:39:52 WIB`，格式规范且标注时区；
  App 侧显示 `2 Sep 10:59`，转换正确。
- ⇒ 运营在 WIB（UTC+7）工作，看到 03:03 会误读为凌晨三点，实际是上午 10:03，
  差 7 小时。履约排期、时效纠纷判定都依赖这个时间。
- 影响面：订单履约列表、订单详情共 4 个时间字段。

---

# 改进需求（2026-09-02 产品提出，**待切回 dev 统一实施，不在 stag 改**）

## R-1【新功能】商品详情主图需支持点击放大看全貌
- 现状：详情页图区固定高 266，`ShopImage(fillWidth: true)`；`ShopImage` 的
  `fit` 默认 **BoxFit.cover（裁切）**，且源码注释写明「默认 cover 不能改 ——
  十余处调用方依赖它」。⇒ 商品图被裁，看不到全貌。
- 缺的是：图区**没有任何点击放大入口**（详情页有 6 处 GestureDetector/onTap，
  但都不在图区上；也没有 InteractiveViewer）。
- 建议实现：点击图区 → 全屏查看器，`BoxFit.contain` 展示全貌 + 支持双指缩放。
  ⚠️ **不要改 ShopImage 的默认 fit** —— 那会波及购物车行、订单行等十余处列表缩略图，
  注释已明确警告。新增全屏查看器即可，列表处仍用 cover。

## R-2【补数据 + 后台上传 UI】商品多图
- 🔴 **App 端已经实现了，不用开发**：
  `product_detail_page_v2.dart` 的 `_gallery()` 已把
  `[mainImageUrl, ...galleryUrls]` 喂给 **PageView.builder** ⇒ 左右滑动已具备；
  且 `if (pages.length > 1)` 才渲染 `1/N` 页码指示器
  ⇒ **只有一张时天然不可滑、不显示指示器**，正是需求描述的行为。
- 🔴 **后端模型也已具备**：`ShopProduct.galleryKeys`、
  DTO `galleryKeys`/`galleryUrls`，详情接口已返回 `galleryUrls`。
- ⚠️ **真正缺的只有两件**：
  1. **没有数据** —— 线上所有商品 `galleryUrls` 均为 `[]`，所以看不到滑动效果。
  2. **后台没有多图上传 UI** —— 商品表单里只有一个 `galleryKeysRaw` **textarea**
     （手填 OSS key 列表），旁边的文件选择器只绑定 `mainImageKey` 单图。
     需要改成多文件上传控件，上传后自动写入 galleryKeys。
- ⇒ 工作量集中在**后台上传控件**，App 侧只需回归验证。

## 🔑 R-2 补充：后台已有可复用的多图上传组件（banner 页）
Toko 顶部 Banner 页的上传控件说明写着：
「可多选、可直接 Ctrl+V 粘贴。缩略图可拖拽排序，第一张即主图。」
⇒ **多选上传 + 粘贴 + 拖拽排序 + 首图为主图** 这套交互后台已经实现了一遍。
R-2 要给商品表单加多图上传时，**直接复用 banner 页这个组件**即可，
不需要从零做。商品表单现在那个 `galleryKeysRaw` 手填 textarea 应被它替换。

## 📎 Banner 机制备注（本次了解到，非缺陷）
- 表单字段：`imageKey` / `imageW` / `imageH`（均 hidden，由上传控件回填）+ `sortWeight`。
- 「同一时间只展示一张：App 取『已上架 + 权重最高』的那条。本页可配多条，
  但只有标着『生效中』的那张用户看得到。**本版本 banner 纯展示、不可点。**」

## D-8【P0】后台 banner 与商品主图上传完全不可用，且失败无任何提示
**运营侧表现**：点「选择文件」选好图后**毫无反应** —— 不报错、不显示缩略图、
隐藏字段 imageKey 始终为空，保存后自然也没有图。运营会以为是自己操作错了。

**故障链（已逐环实测，与图片体积/格式无关，88 字节 1x1 PNG 同样失败）**
1. `shop-banners.html` / `shop-product-form.html` 的 `<head>` **缺 CSRF meta**
2. → `admin.js` 的 `send()`：
   `if (token && header) { headers[header.content] = token.content }`
   两者均为 null ⇒ 请求**不带 CSRF 头**
3. → Spring Security 拒绝 ⇒ **HTTP 403**，`Content-Type: text/html`（403 友好提示页）
4. → `r.json()` 解析 HTML **抛异常** ⇒ 落入 `.catch()`
5. → catch 调 `reject()`，而 `reject()` 首行：
   `var box = root.querySelector('[data-batch-errors]'); if (!box) { return; }`
   这两个页面**没有该元素** ⇒ **静默 return，界面零反馈**

**对照实验（决定性）**——同一份 JS、同一会话、同一张图：
| 页面 | CSRF meta | HTTP | Content-Type | 结果 |
|---|---|---|---|---|
| seed-post | ✅ 有（第 11-12 行） | **200** | application/json | ✅ 成功返回 OSS URL |
| shop-banners | ❌ 无 | 403 | text/html | ❌ 静默失败 |
| shop-product-form | ❌ 无 | 403 | text/html | ❌ 静默失败 |
唯一变量就是 CSRF meta ⇒ 因果确立。

**影响**：后台**无法上传任何 banner，也无法给商品传主图**。这解释了为什么线上
banner 一直是 0 条、为什么商品图只能靠 `mainImageKey` 手填 OSS key。

**修复（三处，切回 dev 后做）**
1. 🔴 **根因**：给两个模板的 `<head>` 补上 seed-post.html 第 11-12 行那两行：
   `<meta name="_csrf" th:content="${_csrf?.token}"/>`
   `<meta name="_csrf_header" th:content="${_csrf?.headerName}"/>`
   ⇒ **更好的做法是挪进 `layout.html` 统一注入**（现在 layout 的 head 里没有，
   各业务页各写各的，这次就是漏了两页；一处修复全覆盖，且以后新增页不会再漏）。
2. 🟡 **可见性**：`shawn/toko-onto-dev` 的 5c378590…`5e0dc7e8` 已把 `r.json()` 改为
   先取文本再试解析 + 新增 `showError()` 写入 `[data-seed-thumbs]`（该元素两页都有）。
   合过去后同样的失败会显示「上传失败（HTTP 403）」。
   ⚠️ **它只让错误可见，不修根因** —— 第 1 条仍必须做。
3. 🟡 **隐患**：`reject()` 的 `if (!box) { return; }` 会吞掉任何缺 `[data-batch-errors]`
   页面的全部错误。应回退到 `[data-seed-thumbs]` 或至少 `console.error`，不要静默。

**测试残留**：对照实验在 staging 传了一张 1x1 PNG：
`stag/public/seed-post/5c26e03f-9ca7-4bd5-9dab-43fb3ed1fc10.png`（88 字节），可清理。

## R-3【改进】购物车删除加 undo（2026-09-02 产品拍板，待切回 dev 实施）
- 现状：点垃圾桶**立即删除**，无二次确认、无撤销入口。
- 风险来源不是删除本身，而是**按钮位复用**：同一坐标 [678,478][810,610]
  在数量≥2 时是「−」、数量=1 时变成垃圾桶。用户连点减号收数量时，
  最后一下会落在已变成垃圾桶的同一位置 ⇒ 误删。
- 方案：删除后弹 **undo 提示条**（SnackBar，建议 4–5 秒），点「撤销」恢复该行
  及其数量。**不要用二次确认弹窗** —— 会拖慢正常的收数量操作，
  而误删的代价用 undo 覆盖即可。

## R-4【改进】Toko 分类标签：吸顶 + 强化选中态 + 显式「全部」（2026-09-02 拍板，待 dev 实施）
**问题本质不是"不够醒目"，是"会滚走"。** 当前纵向布局（1080×2340）：
```
y 104-236    顶栏
y 278-338    "Picked for test" 标题
y 368-983    精选区 3 个商品（占 615px）
y 1052-1136  分类标签      ← 夹在屏幕 45% 处，跟内容一起滚
y 1166+      商品网格（无限流）
```
往下翻两屏后筛选控件完全离开视野，换品类得一路滚回顶部。

**① 挪进 `ShopAppBar.bottom` 吸顶**
与 PRD 决策 C-18 给搜索框定的落位一致（原文：「落位在 Toko 顶栏之下
（ShopAppBar.bottom 槽，随顶栏常驻、不随内容滚走）」）。分类应享受同等待遇 ——
它是页面级导航，不是流中的一个区块。
⚠️ **与搜索框争用同一个槽**：C-18 的搜索框也要放这里。需决定是
**上下叠两行**（顶栏增高约 170px，吃首屏）还是同一行左搜右分类。
倾向叠两行 —— 搜索与分类是两种不同的找货方式，挤一行都不好用。

**② 选中态改实心填充**
现状选中仅表现为标签宽度 274→282（8px 边框），几乎不可辨。
改为：未选中=浅灰底+深色字；选中=**品牌紫实心底+白字**。
与 D-1 的修复色板（主体色）统一。

**③ 加显式「Semua（全部）」标签**
现在取消筛选只能「再点一次已选中的标签」——隐藏交互，用户不会主动试。
加一个默认选中的「全部」放最左，直观且可发现。

## D-3 升级：全量比对后确认 38 个键，非零星问题
用 `app_en.arb` 与 `app_id.arb` 逐键比对（1652 个可比键），76 个值完全相同，
排除品牌/专有名词（PawCoin/QRIS/GoPay/Email/Online/人名等）后，
**38 个键的英文值实为印尼语**。分布：toko 21 · cart 8 · order 5 · checkout 2 ·
address 3 · 其他 1。
⇒ 这不是零星漏译，是**英文基线整体未完成**。电商域（toko+cart+checkout+order = 36 个）
是重灾区，与本次测试反复撞见的现象吻合。
📎 逐条对照与建议译文见 `i18n-en-baseline-fix.md`（可直接照做）。
⚠️ 其中两类需产品先决策：
  - 品类名（Makanan/Camilan/Obat & Vitamin/Perawatan）英文界面是否翻译；
  - 印尼行政区划（Provinsi/Kota/Kecamatan）建议保留原词加注解，
    直译成 Province/City/District 会与表单实际层级对不上。

## D-9【P1】后端向 C 端返回硬编码中文，App 直接显示给印尼用户
**发现场景**：退货申请页，不可退商品行内的禁用说明显示为
`开封后不支持退货（若是破损/临期/错发，请选「质量问题」）` —— 中文。
App 无中文包，这句不经 i18n，是后端字面量原样透传。

**直接来源**
- `shop/returns/service/ReturnQueryService.java:90`
  `blocked = "开封后不支持退货（若是破损/临期/错发，请选「质量问题」）";`  ← 正常路径可见
- `shop/returns/service/ReturnRequestService.java:281`
  `throw AppException.conflict("该商品开封后不支持退货：" + line.getProductName());`  ← 提交时抛

**扫描结果：shop 域 C 端共 58 处硬编码中文**（管理端另有 18 处，后台本就是中文界面，可接受）
| 文件 | 处数 |
|---|---|
| ReturnRequest.java | 9 |
| ReturnRequestService.java | 8 |
| MeReturnController.java | 7 |
| RefundSplit.java | 7 |
| MeShopReviewController.java | 4 |
| CartService.java / ShopReviewService.java / MeCheckoutController.java / ReturnQueryService.java | 各 3 |
| MeCartController.java / MeShippingAddressController.java / MeRepurchaseController.java / ShopOrderPaymentService.java | 各 2 |
| ShippingAddressService.java / CheckoutService.java / ShopProductController.java | 各 1 |

🔴 **退货域独占 34/58**，是重灾区 —— 与本次实测撞见的位置吻合。

**影响**：印尼用户在退货、购物车、结算、评价、地址等场景一旦触发校验失败或
业务冲突，看到的是中文。多数是错误分支（平时不可见），但
`ReturnQueryService:90` 那条在**退货申请页正常渲染**，必现。

**修复方向**：这些应走后端既有的 i18n（`messages.properties` + `Messages` bean，
`GlobalExceptionHandler` 已在用）。建议分两批：
1. **先修正常路径可见的**（ReturnQueryService 的 blocked 文案）——必现，优先级最高；
2. 再批量迁移 AppException 的错误文案到 messages key。
⚠️ 后台管理端那 18 处**不要动** —— 后台界面是中文，改成 key 反而增加维护成本。

## R-5 撤回
原拟建议「不可退商品行内标注禁用原因」——**实际已实现**（行置灰 + 行内说明），
只是那句说明是中文（见 D-9）。撤回该建议。

## D-10【P0】退货凭证照片功能是桩实现，点「+」不调相册、直接塞假 key
**用户可见表现**：退货申请页点照片区「+」，**不弹相册也不拍照**，
计数直接从 0/5 跳到 1/5、2/5，缩略图是占位图。

**根因**（`return_request_page_v2.dart:291-293`）
```dart
onTap: () => setState(() =>
    _evidence.add('return-evidence-${_evidence.length + 1}')),
```
点击只是往 `_evidence` 追加字面量 `return-evidence-1/2/…`，
**没有 ImagePicker、不选图、不拍照、不上传**。随后第 428 行：
```dart
evidenceKeys: List.of(_evidence),   // 假字符串直接提交
```

**后端不设防**（`ReturnRequestService.java:84-90`）
只校验两件事：① 数量 ≤ MAX_EVIDENCE ② `QUALITY_ISSUE` 类型时非空。
**不校验 key 是否指向真实对象存储对象** ⇒ 假 key 被 `joinKeys()` 原样入库，
后台退货审核时无图可看，而页面文案写着「拍到封口和保质期标签——这是质检要看的」。

🔴 **影响**：退货凭证链路端到端不可用。运营拿不到任何证据就得做退款决策；
「开封判例」这类依赖凭证的功能也失去输入。

**✅ 好消息：能力是现成的，不用从零做**
App 内已有真实图片上传实现，直接复用即可：
- `petgo_app/lib/features/media/domain/media_upload_use_case.dart`
- `petgo_app/lib/features/consult/presentation/im_chat_placeholder.dart`（image_picker 用法参考）

**修复要点**
1. 把 `+` 的 onTap 换成 image_picker（相机/相册二选一 sheet）→ 走 media_upload_use_case
   上传 → 拿真实 objectKey 填进 `_evidence`；
2. 上传中/失败要有态（否则会重演 D-8「静默失败」那类问题）；
3. 后端补一道 key 合法性校验（至少校验前缀与归属），别再来者不拒。

**⚠️ 顺带发现前后端校验口径不一致**
App 对**所有**退货原因都要求「Required · min. 2 photos」；
后端只在 `type == QUALITY_ISSUE` 时要求凭证非空。
本次测试选的「Changed my mind」后端并不要求凭证，App 却拦着不让提交。
需产品统一口径：到底哪些原因必须传凭证。

## D-11【P1】退款页无条件承诺「额外补余额」，与实际金额不符
**现象**：退款方式页的「不可提现说明」块末尾恒显示
`Because this one is on us, we are adding extra balance.`
（这单算我们的，我们额外补余额）。
但本次实测：原因选「Changed my mind（买家自身原因）」，
合计 `Total refunded (incl. goodwill) Rp 120.000` = 商品原价，**补偿为 0**。

**根因**（`refund_method_page_v2.dart:299`）
```dart
body: '${l10n.refundNotCashBody} ${l10n.refundMethodPawcoinWhy}',
```
`refundMethodPawcoinWhy` 被**无条件拼接**；而真正的补偿金额行是有条件的
（同文件 257 行 `if (p.compensationPremium > 0)`）⇒ 文案与数字脱钩。

**双重问题**
1. `compensationPremium == 0` 时仍承诺「补余额」——用户会去客服问补偿在哪；
2. 「this one is on us（算我们的）」隐含**卖家责任**，
   但买家自身原因（改变主意）的退货同样显示，口径错误。

**修复**：把该句改为条件渲染 —— 仅当 `compensationPremium > 0` 时拼接；
且措辞按责任方区分（卖家责任才说「on us」）。
顺带：合计行的 `(incl. goodwill)` 在补偿为 0 时也不该出现。

## 观察（低优先级）：退货原因回显被合并
用户选的是「Changed my mind (seal intact)」，
下一页摘要显示为「Changed my mind / wrong variant」——
两个在申请页分列的原因（第 1 项 Wrong variant was sent、第 4 项 Changed my mind）
在此被合并成一条。疑似前端 4 个选项映射到后端较少的 ReturnType 枚举，
回显时用了枚举标签。用户会疑惑「我没选错发变体」。
建议回显用户实际选择的那一项文案。

## D-13【P1】后台退货详情页把凭证图渲染成纯文本，运营根本看不到图
**现象**：退货审核详情页「凭证图」一行显示为
`return-evidence-1,return-evidence-2` —— 逗号分隔的 key 字符串，
页面上**零个 `<img>` 元素**。

**根因**（`templates/admin/shop-return-detail.html:34`）
```html
<tr><th …>凭证图</th><td th:text="${r.evidenceKeys}">-</td></tr>
```
直接 `th:text` 输出 key 列表，从未实现图片渲染。

🔴 **与 D-10 是两个独立缺口，必须一起修**：
即使 App 端修好、真的上传了照片，运营在这一页看到的**仍然只是一串 OSS key**。
质检要看封口和保质期标签，而审核界面从来就没有展示图片的能力。

**修复**：把 key 列表拆开，逐个拼成 OSS 可访问 URL 渲染为缩略图 + 点击放大。
⚠️ 注意 D-2 的教训：OSS 对象需公共读 ACL，否则渲染出来仍是破图。

## D-14【P1】后台退货详情页不显示现金去向与打款账户，财务无法执行打款
**现象**：详情页渲染的字段只有 publicToken / status / returnType / fullReturn /
outboundFeeRefundable / reasonNote / evidenceKeys / returnShipBearer /
returnShipBackTrackingNo / rejectReason / rejectDisposal。
**没有 `cashDestination`、`payoutChannel`、`payoutAccount`、`payoutAccountHolder`。**
（模板中 `payout` 仅出现在权限名 `refund.payout`，不是字段渲染。）

**实体里字段是有的**（`ReturnRequest.java:132-152`）：
cashDestination / payoutChannel / payoutAccount / payoutAccountHolder / payoutChannelFee。

🔴 **运营后果**：
1. 审核人看不出用户选的是「退回银行/电子钱包」还是「转 PawCoin」——
   而这决定了是否需要人工打款；
2. `RefundExecutionService` 的注释明确写着
   「TO_BANK 分支：真钱打款由财务按 payoutChannel / payoutAccount **线下执行并回填**」，
   但财务在这页**拿不到账号和户名**，无从执行；
3. 「执行退款」按钮在 REFUNDING 状态出现，却不展示任何打款去向信息。

**修复**：详情页补上这四个字段；TO_BANK 时突出显示账户信息与渠道费。

## D-11 补充证据：后台试算证实两项溢价均为 0
后台「退款试算」区显示：平台责任补偿溢价 **0**、转 PawCoin 激励溢价 **0**、
总退回（含补偿）120000 = 商品原价。
⇒ App 端「Because this one is on us, we are adding extra balance」与
「Convert to PawCoin · Lands instantly, **with a bonus**」两处承诺**均未兑现**。
按代码设计：补偿溢价仅 QUALITY_ISSUE 时给（本单 NON_QUALITY_ISSUE，0 正确）；
激励溢价仅 cashDestination==TO_PAWCOIN 时给——本次选了转 PawCoin 却仍为 0，
需进一步确认是费率配置为 0，还是选择未正确落库（受 D-14 阻碍，
后台看不到 cashDestination，无法从界面确认）。

## D-15【P1】后台退货质检区不可用：两表单混排、字段无标签、枚举裸露
**现象**：INSPECTING 状态下页面出现 4 个输入框、2 个按钮，**看不出哪个框属于哪个操作**。
实测由产品直接反馈「没看懂」。

**实际结构**（查后端签名才能确定，界面上无从判断）
- 表单一 `/inspect-pass`（质检通过并入库）：`note`(选填) `photoKeys`(选填)
- 表单二 `/inspect-fail`（质检不通过）：`note`(**必填**) `photoKeys`(选填)
  `disposal`(**必填**) `shipBackTrackingNo`(选填)

**四个具体问题**
1. **所有字段无 label** —— 4 个输入框光秃秃，靠猜。
2. **两表单视觉不分区** —— 挤成一片，用户不知道填哪个、按哪个按钮。
3. **`disposal` 下拉直接显示英文枚举** `RETURN_TO_USER` / `WRITE_OFF`，无可读文案。
   🔴 这两项代价差别很大（源码 `RejectDisposal.java`）：
   - `RETURN_TO_USER` = 退回用户，**回寄运费由平台承担**（平台判定驳回，不应再让用户付）
   - `WRITE_OFF` = 报损核销，货不退还
   选错直接影响成本归属，却只给运营两个英文常量。
4. **`photoKeys` 是手填文本框** —— 要人工填 OSS key，与 D-13 同源，运营无法使用。

**✅ 修复方案（2026-09-02 产品拍板）**
- 拆成两张**视觉分离的卡片**，各自独立标题（「质检通过」/「质检不通过」）；
- 每个字段补 label 与填写说明；
- `disposal` 改中文可读选项：「退回用户（回寄运费由平台承担）」/「报损核销」；
- `photoKeys` 换成图片上传控件（复用 banner 页那个多图组件，见 R-2）。

## D-11 结案：激励溢价为 0 是**配置值**问题，但 App 文案缺陷成立
**查证结论**
- 代码条件（`RefundExecutionService.java:225-227`）：
  ```java
  long incentive = 0L;
  if (r.getCashDestination() == CashDestination.TO_PAWCOIN && config != null) {
      incentive = premium(split.thisCash(), config.getPremiumRate(), 0L);
  }
  ```
  **只要选了转 PawCoin 就该给**，代码里并无「未交付」限制。
- staging 实际配置：**`premiumRate = 0`**（后台 /admin/config → PawCoin 区）
  ⇒ `premium()` 在 `ratePercent <= 0` 时直接返回 0。金额计算**没有 bug**。
- ⚠️ **但 App 文案缺陷依然成立**：选项标签
  `Convert to PawCoin · Lands instantly, with a bonus` 与说明
  `Because this one is on us, we are adding extra balance.` 都是**无条件硬编码**，
  在 premiumRate=0 时仍向用户承诺 bonus。用户是**因为这句话才选的转 PawCoin**，
  而该选择不可逆（PawCoin 不能提现）。
  **修复**：这两句应随 `incentivePremium > 0` / `compensationPremium > 0` 条件渲染。

**⚠️ 附带发现代码与注释不符**（需产品/架构确认哪个是对的）
`PawCoinConfig.java:12` 注释写 `premiumRate` =「仅『未交付+转币』分支用，反套利 C-1」，
但 `RefundExecutionService` 对**任何** TO_PAWCOIN 退款都给。
若注释是设计意图，则已交付退货转币也给溢价会打开套利口子（买→收货→退→转币赚溢价）；
若代码是对的，注释应更正。**当前 premiumRate=0 掩盖了这个分歧，一旦调非 0 就会暴露。**

## D-16【P2】「退款转币固定溢价」是配了不生效的空开关
`premiumFixed`（后台 /admin/config → PawCoin →「退款转币固定溢价（koin，≥0）」）：
- 后台可填、有校验（`AdminConfigService:86` 固定溢价须 ≥ 0）、
  记审计日志（`diff(..., "premium_fixed", ...)`）、能存库（`setPremiumFixed`）；
- **但全仓没有任何计算代码读它** —— `getPremiumFixed()` 仅出现在审计 diff 一行，
  退款/支付链路一概不读。
⇒ 运营改这个值会看到"保存成功 + 审计留痕"，实际永远不生效。
**修复**：要么接进 `premium()` 计算（溢价 = base×rate% + fixed），要么从后台移除该字段。

## D-4 更严重形态：PawCoin 全额抵扣时 Total due 显示全额
2026-09-02 复测（退款到账后 PawCoin 余额 120.000，下单 Pet Bowl foot PJ-59）：
| 行 | 金额 |
|---|---|
| Items subtotal | 52.000 |
| Shipping | +15.000 |
| PawCoin | **−67.000**（全额抵扣） |
| QRIS「Remaining balance paid here」 | **Rp 0** |
| **Total due** | **Rp 67.000** ❌ |
用户**实际应付 0**，页面却写「Total due Rp 67.000」。
⇒ 上一例差 999 尚可辩称舍入，本例**差的是全额**，用户会以为还需再付 67.000 而放弃下单。
**D-4 优先级维持 P0，且此形态应作为回归用例。**

## 观察：结算页退货说明重复渲染
`Produk dapat dikembalikan sesuai syarat retur.`（tokoReturnableBody，属 D-3 的 35 键之一）
在结算页出现**两个元素**（bounds [0,1826][1080,2033] 与 [48,1864][180,1996]），内容相同。
疑似容器与内层文本各渲染一次。

## D-17【P2】新增地址：Label 为必填却无标记，未选时保存静默失败
**复现**：新增收货地址，除 Label（Rumah / Kantor / Lainnya）外**全部填妥**
（收件人、电话、三级行政区划、邮编、街道，且已显示 `We deliver to this area`），
点 Save —— **页面纹丝不动，无 toast、无字段标红、不滚动定位到问题字段**。
选中任一 Label 后再点 Save 立即成功。

**问题**
1. Label 是必填，但**界面无任何必填标记**（无 `*`、无「必填」字样）；
2. 校验失败**零反馈** —— 与 D-8（上传 403 静默）、D-12（退货提交无提示）同一类，
   本轮测试第三次遇到同种模式，说明是**全局性的反馈缺失**，不是个别页面疏忽。

**建议**
- Label 默认选中 `Rumah`（多数为家庭地址），把必填变成"已有合理默认"；
- 或加必填标记；
- **无论如何**：校验失败必须给反馈（toast 或字段标红 + 自动滚动到该字段）。

## ✅ V-5 新增收货地址流程验证通过（除 D-17 外）
| 检查 | 结果 |
|---|---|
| 三级联动 | ✅ DKI Jakarta → Jakarta Pusat → Menteng 逐级正确加载 |
| 服务范围 | ✅ Provinsi 仅 Banten / DKI Jakarta（覆盖范围内），选完即时提示 `We deliver to this area` |
| 手机号格式化 | ✅ 输入 `08123456780` → 存为 `+628123456780`，前导 0 按提示自动转换 |
| 默认地址保护 | ✅ 未开「Make this my main address」时新地址不抢占 Default |
| 操作按钮差异化 | ✅ 仅非默认地址显示 `Set as default` |
📎 印证 R-3 决策：三个行政区划保留印尼原词是对的 —— 它们是**级联下拉**，
选项值为印尼官方地名；标签若译成 Province/City/District，会与列表内容对不上。

## D-18【P2】结算页无法为单次订单选择地址，只能改默认地址
**复现**：结算页 → 点「Change ›」→ 跳到**地址管理页**（Shipping addresses）。
- 点地址卡片**无任何反应**（不选中、不返回结算页）；
- 页面提供的是 `Set as default` / `Edit` / `Delete` —— **管理操作，不是选择操作**；
- 唯一能改变结算页所用地址的办法：把目标地址 **设为默认**。

**后果**：多地址用户每次想换收货地址（如这单寄公司），都必须**永久改掉默认地址**；
下单后若想寄回家，还得再切一次。默认地址被当成"当前选择"来用，语义错位。

**期望**：从结算页进入时列表应是**选择器** —— 点卡片即选中并返回，
仅作用于当前订单，不修改默认地址。（可保留「设为默认」作为附加操作。）

## ✅ V-6 运费按收货地区重算，与 PawCoin 抵扣联动正确
同一商品（Pet Bowl foot PJ-59，Rp 52.000），仅切换收货地区：
| 收货地区 | Shipping | PawCoin 抵扣 | QRIS |
|---|---|---|---|
| Jakarta Selatan | Rp 15.000 | −67.000 | Rp 0 |
| Jakarta Pusat | **Rp 18.000** | **−70.000** | Rp 0 |
⇒ 运费随地区重算，PawCoin 全额抵扣金额同步跟进，后台「服务范围与运费」配置生效。
⚠️ 但两种地区下「Total due」仍分别显示 67.000 / 70.000（应付实为 0）——**D-4 在两种地区下均复现**。

## D-19【P2】订单取消后 Payment 区错误显示「Paid」，实际已退回
**同一订单详情页，PawCoin 行的状态文案：**
| 时点 | 显示 | 正确性 |
|---|---|---|
| 下单后（待支付） | `Held · returned if you cancel` | ✅ |
| **取消后** | **`Paid`** | ❌ |
**实测资金正确**：取消后 PawCoin 余额恢复为 Rp 120.000（= 下单前），
冻结的 70.000 已完整解冻退回，App 顶栏亦同步显示 `120rb`。
⇒ **只是显示错**，钱没问题。但用户看到「Paid」会以为被扣了款。
**疑因**：复用了「已支付订单」的展示分支（已完成订单详情页同样显示 `Paid`），
取消态没有独立文案，落到默认值。
**建议**：取消态显示 `Refunded` / `Returned to balance`，明确告知钱已回。
⚠️ 同页 `Total due` 在取消后仍显示 Rp 70.000（D-4 在取消态亦未处理，
且此时更无意义——订单已取消，不存在"应付"）。

## ✅ V-7 取消订单：资金与库存处理正确
订单 `gkJR2GbJjMLrJT5YnM9Wxp`（全额 PawCoin 支付，Rp 70.000，QRIS 段 Rp 0）
| 检查 | 结果 |
|---|---|
| 二次确认弹窗 | ✅ 文案明确：「锁定库存将释放，订单不可恢复」 |
| PawCoin 解冻 | ✅ 余额 120.000 → (冻结 70.000) → 取消后回到 **120.000** |
| App 顶栏同步 | ✅ 显示 `120rb` |
| 购物车 | ✅ 下单后已清空 |

## D-4 最极端形态（订单详情·待支付态）
同一屏：明细区 `Total due Rp 70.000`，正下方按钮 **`Pay now Rp 0`**。
按钮自己写着"付 0 元"，上方却称"应付 70.000"。
⇒ 该形态最适合作为 D-4 的**回归验收用例**：PawCoin 余额充足到可全额抵扣时下单。

## D-20【P0】后台商品详情/编辑页几乎全部 500（NPE）
**现象**：打开 `/admin/shop/products/{id}` 返回
`{"status":500,"detail":"服务暂时不可用，请稍后重试"}`。
抽查 8 个商品（84/85/86/64/1/2/3/239）：**7 个 500，仅 id=239 正常**。

**根因**（服务端日志 traceId 702fb6b4-70da-4546-8a30-72966d56047f 堆栈）
```
java.lang.NullPointerException:
  Cannot invoke "java.util.List.iterator()"
  because the return value of "ShopProduct.getGalleryKeys()" is null
  at AdminShopProductController.detail(AdminShopProductController.java:162)
```
第 162 行直接迭代、无 null 判断：
```java
for (String k : p.getGalleryKeys()) {
```
实体 `ShopProduct.galleryKeys` 是 `@JdbcTypeCode(SqlTypes.JSON) List<String>`，
**无默认值**，DB 中该列为 NULL 的行读出即 `null`。
⚠️ 同一方法紧邻的 `mainImageKey` 有防护
（`if (p.getMainImageKey() != null && !p.getMainImageKey().isBlank())`），
到 galleryKeys 就漏了 —— 是遗漏，不是设计。

**为何 id=239 正常**：该商品由本次测试通过后台新建路径创建，
创建时给 galleryKeys 赋了空列表；存量数据（种子导入/早期录入）该列为 NULL。

🔴 **影响**：运营**无法打开任何存量商品的编辑页** ——
改价、改描述、换图、调排序权重全部阻断。电商后台最高频操作之一完全不可用。
（本次是为了造"售罄样本"去改库存时撞见的。）

**修复（建议三条都做）**
1. 止血：第 162 行加 null 防护；
2. 根治：getter 返回 `galleryKeys == null ? List.of() : galleryKeys`，
   或实体字段初始化为 `new ArrayList<>()`；
3. 数据：`UPDATE shop_product SET gallery_keys = '[]' WHERE gallery_keys IS NULL`。
📎 与 **R-2（商品多图）同一字段** —— 实现多图上传时会重写此处，可一并处理。

## ⚠️ 阻塞：售罄样本无法通过后台构造
原计划把某商品库存改为 0 以测试 App 的售罄态与「See alternatives」，但：
- `/admin/shop/inventory` 列表**只有「流水」一个操作**，无改库存入口；
- `/admin/shop/inventory/{id}/movements` 是**只读日志**，无调整表单；
- 商品编辑页因 **D-20** 全部 500，进不去。
⇒ 后台当前**没有任何调整库存的可用路径**。库存只能由采购入库/订单/退货被动变动。
「售罄替代品」测试本轮无法进行，待 D-20 修复后再测。

## D-12【P2】退货提交成功但页面不跳转、无成功提示（补记）
**复现**：退款方式页点「Agree & ship it back」→ 页面停在原处、按钮仍可点、无任何提示。
**实际已成功**：服务端日志 13:29:05 有 `ReturnRequestService` 记录；
App 订单详情底部按钮已变为 `Return in progress`；后台退货列表出现该单。
⇒ 用户在提交那一刻**看不到任何反馈**，会以为失败而重复点击。
**后台侧同样问题**：退货审批四步（批准 / 登记寄回 / 质检通过 / 执行退款）
全部**无提示条**，而控制器里明明写了
`ra.addFlashAttribute("notice", msg.get("admin.flash.return.inspectPassed"))` ——
`shop-return-detail.html` **缺少 banner 渲染块**（支付记录页有，此页没有），
flash 消息设了却没渲染。

---
# 后台走查（第二批，2026-09-02 下午）

## ✅ V-8 异常订单页：空态与说明正确
`/admin/shop/order-exceptions` —— 当前无异常，空态文案「当前没有异常订单。」正常。
页面说明写得到位，两条都指向可执行的下一步：
- 「真正的超卖来源是**盘点、报损与退货入库撤销**，不是并发下单……发现异常时请先回查最近一次盘点或报损记录。」（告诉运营去哪查根因）
- 「**系统不会自动取消任何订单**……自动取消会误杀大客户。」（把不自动化的理由写在界面上）
⚠️ 未覆盖：无异常数据，处置操作未验；造超卖样本需调库存，**被 D-20 阻塞**。

## ✅ V-9 开封判例：录入与搜索正常
`/admin/shop/return-precedents` —— 录入一条测试判例（情形/判定/理由），
三项正确入库并回显，`judgedOpened=false` 渲染为中文「不算开封」。
搜索：`铝箔` ✅命中 · `外箱` ✅命中 · `狗粮` ✅正确返回「暂无判例。」，搜索框回显正确。
页面说明同样点在要害：「判例库是**一致性工具，不是风控工具**……
骗退风控由 90 日 ≤2 次的频次上限承担，**两者不可互相替代——查过判例不等于查过风险**。」
📎 测试残留：staging 留有一条测试判例（「猫粮外箱拆封、内层铝箔袋封口完整未破」），可清理。

## D-7 追加实例：开封判例页时间亦为裸 UTC，且列名标错
判例表时间列显示 `2026-09-02T08:45:46.093096Z`（裸 UTC）——
**D-7 第三处**（履约列表 / 履约详情 / 判例页）。
⚠️ 且该列表头写作「**下单时间**」，但判例记录的是**沉淀时间**，与判例本身无订单关联。
列名标错，应改为「沉淀时间」或「记录时间」。

## D-12 追加实例：判例录入成功无提示条
录入成功后页面无 `.banner.ok` —— **D-12 第五处**
（App 退货提交 / 后台退货审批四步 / 判例录入）。
⇒ 进一步印证「缺少统一反馈约定」这一横切问题。

## D-15 追加实例：判例录入表单四个字段全无 label
`situation` / `judgedOpened` / `rationale` / `evidenceKeys` 均无 label，
与退货质检区同一问题。
（较好的一点：`judgedOpened` 选项已是中文「不算开封 / 算开封」，
不像质检区的 `disposal` 直接裸露英文枚举。）

## D-13 追加实例：判例的 evidenceKeys 同样是手填文本框
`evidenceKeys : text` —— **第三处**要求运营手工填 OSS key 的地方
（退货详情展示 / 质检录入 / 判例录入）。
判例的价值在于让人看到「这种情形长什么样」，无图基本失去意义。
修法与 D-13 相同：换上传控件 + 渲染缩略图。

## ✅ V-10 销售与毛利：口径与算术全部核对通过
`/admin/shop/margin`，筛选 `from` / `to` / `category`。四个小节：核心 / 运费与手续费 /
售后成本（按退货类型）/ 按 SKU 下钻。
| 指标 | 值 | 核验 |
|---|---|---|
| 销售额 | 305,001 | = 305,000（本次测试单）+ 1（历史 test 单） |
| 成本（进货价） | 182,000 | = 128,000 + 54,000 |
| 毛利 | 123,001 | ✅ 305,001 − 182,000 |
| 毛利率 | 40.33 % | ✅ 123,001 ÷ 305,001 |
| 退款商品额 | 120,000 | ✅ 本次退的 Pet Bowl |
| 净额 | 185,001 | ✅ 305,001 − 120,000 |
🔴 **重点验证项通过**：发货时录入的**承运成本 15,000 已进入毛利口径** ——
「运费收入 15,000 / 承运成本 15,000 / 运费净额(A-19) 0」，收支相抵正确。
SKU 下钻亦对得上（Royal Canin 3kg 185,000/128,000；Pet Bowl 120,000/54,000）。
页面说明到位：「成本按**下单那一刻的进货价快照**算——用现在的进货价算不出当时的毛利。」

## D-21【P2】「售后成本」口径未标注，0 值易被误读
**现象**：售后成本显示 **0**，退货类型表 `NON_QUALITY_ISSUE → 0`。
但本次退货实际产生过成本：登记寄回时录入**回程运费 12,000**。
**推断**：该指标应只统计**平台承担**的售后成本；本单回程运费承担方为 `USER`（买家自担），
故记 0 —— 逻辑大概率正确。
🔴 **问题在于页面上看不出这个口径**：运营看到 0 会理解成"退货没花钱"，
而实际只有平台承担的场景（质量问题退货、驳回后 `RETURN_TO_USER`）才会有数。
**建议**：在「售后成本」旁标注口径，如同「运费净额」已标 A-19 那样。
⚠️ 待确认：该口径是否也应涵盖退款手续费、报损等其他售后支出。

## D-22【P2】库存周转页「当前售罄 SKU 数」恒显示 0，实际有 61
**现象**：`/admin/shop/inventory-turnover` 顶部「当前售罄 SKU 数」= **0**。
**对照同一后台的库存管理页**（`/admin/shop/inventory`，264 行）：
状态分布 `IN_STOCK 113 / LOW_STOCK 90 / OUT_OF_STOCK 61`，
且 61 行「实际库存 = 0」的状态**全部正确标记为 OUT_OF_STOCK**（数据本身没问题）。
⇒ 两页对同一批数据给出的售罄数差 **61**，周转页的统计口径或查询条件有误。
**运营后果**：该数字是判断"要不要紧急补货"的第一眼指标，恒为 0 等于**永远不告警**，
而实际 264 个 SKU 中已有 61 个（23%）售罄。
📎 举例：`Vitamin Bulu & Kulit 60 kapsul`、`Catto Cat Food Adult` 系列多个规格均为 0。

## ✅ V-11 库存周转与滞销：明细数据正确
- 周转表 265 行，字段：商品名 / 规格名 / 实际库存 / 库存金额（进货价）/ 窗口内销量 /
  最近售出 / 建议动作，筛选 `days`（统计窗口天数）。
- 「建议动作」按窗口内销量分档：0 销量 → 「滞销：降价清货 / 停止补货 / 下架」；
  有销量 → 「正常」。本次测试卖出的 Royal Canin 3kg 正确显示「正常」，
  最近售出时间与订单时间一致。
- 页面说明到位：「库存金额按**进货价**算——那是资金占用的直接读数。
  按售价算会把还没赚到的毛利也算成压着的钱。」
⚠️ 已下架的 10 个商品仍出现在周转表中（12 个 SKU 行）——
对资金占用统计而言合理（下架不等于没库存），但建议加「上架状态」列以便区分。

## 修正：售罄样本其实存在（商品级 vs SKU 级）
先前记录「无售罄样本」是**基于商品级**判断：83 个在售商品中，
无一"全部 SKU 都非 IN_STOCK"（多规格商品总有别的规格有货）。
但 **SKU 级有 61 个 OUT_OF_STOCK**。
⇒ 「See alternatives」测试若要进行，需找到**所有 SKU 均售罄的在售商品**，
或在多规格商品上验证"单个规格售罄"的表现（后者也是有效场景）。

## ✅ V-12 复购引擎效果：归因链通、空态诊断是全后台最佳范例
`/admin/shop/repurchase-dashboard`，筛选 `from`/`to`，五个小节：
触发 / 归因（服务端权威口径）/ 归因链核对底账 / 复购率 / 粮量预估准确度。

🔴 **归因链有真实数据且与本次测试操作吻合**：
| 归因来源 | 订单行数 |
|---|---|
| TOKO_CATEGORY | 4 |
| TOKO_ALL_FEATURED | 3 |
| PROFILE_RECO | 1 |
| 合计 | 8 |
本次测试分别从分类页、精选流、档案推荐三个入口下过单，**三个来源都有数** ⇒ 埋点链路通。
「来自档案推荐的订单行 1 (12.50%)」与「来自补货卡 0 (0.00%)」互相自洽。

🏆 **空态设计是本次走查所见最佳**，两处主动澄清"0 不等于坏了"：
- 「触发覆盖率为 0，但已有用户买过粮——这几乎可以肯定是**商品的每日建议喂量还没录入（DEP-6）**，
  而不是复购机制无效。**请先补喂量数据再评估。**」（给出最可能原因 + 下一步动作）
- 「DEWORM / VACCINE 在本版本恒为 0——**这是范围决策，不是数据丢失，也不是埋点坏了**」（FR-108 已挪 1.2.0）
建议把这种"解释 0 值"的写法推广到其他看板（对照 D-21 售后成本 0 值无口径说明）。

⚠️ 样本量说明（非缺陷）：复购率 30/60/90 日均 100%、
FR-109 触发覆盖率分母为 1 —— 均因只有一个测试账号，真实数据下才有意义。
粮量预估样本数 0，与"喂量未录入"诊断自洽。

## ✅ V-13 对账页：勾稽自洽且页面自带校验
`/admin/shop/reconciliation`，筛选 `from`/`to`，四小节：核心 / 运费 / 退款 / 赠币。
| 项 | 值 | 核验 |
|---|---|---|
| 订单数 | 2 | |
| 实付合计 | 320,001 | |
| PawCoin 段 | 16,000 | |
| QRIS 段 | 304,001 | |
| **两段之和 = 实付** | **是** | ✅ 页面自带勾稽校验，运营无需自算 |
退款段与本次操作吻合：已退合计 120,000 / 其中 PawCoin 段 393 /
现金净流入 184,394（= 304,001 − 119,607）✅。
运费段说明到位：「运费是平台向承运商支付的**真实现金支出**。用 PawCoin 覆盖等于
用预收款抵现金成本——不单独拆出来，现金流量表会显示收支平衡，而**实际现金在净流出**。」

## ✅ V-14 服务范围与运费：配置与 App 实测完全一致
`/admin/shop/shipping`（注：不是 `/shipping-zones`）。四小节：
可配送区域 / 新增更新区域 / 免运门槛 / 退货收件地址。
以 **Kecamatan 为粒度**，每区一个固定运费；停用而非删除（历史订单运费需可追溯）。
| 区域 | 配置运费 | App 实测 |
|---|---|---|
| Menteng（Jakarta Pusat） | 18,000 | ✅ 一致（V-6） |
| Cilandak（Jakarta Selatan） | 15,000 | ✅ 一致（V-6） |
| Serpong（Banten） | 25,000 | 未测 |
**免运门槛 = 300,000** —— 解释了本次两单的差异：305,000 那单有免运抵扣，
52,000 那单没有。门槛说明清楚：「填 0 表示**不做免运**——不是『0 元即免运』」。

## D-23【P1】退货收件地址未配置，用户拿不到寄件地址
**现象**：`/admin/shop/shipping` →「退货收件地址」三项（receiverName / receiverPhone /
addressText）**全为空**。
**页面自身的约束**：「三项要么都填、要么都留空——只填一半的地址寄不到，
而寄不到的退货会变成『货在路上、钱也没退』的**双输**。」
⇒ 当前"都留空"符合该约束，**不是校验 bug**。
🔴 **但结合本次实测的退货流程**：订单批准后进入 `AWAIT_SHIPBACK`，
App 提示用户自寄退货（「Return shipping is paid by the buyer」），
而**用户拿不到寄件地址** —— 退货流程在这一步实际走不通。
**待确认**：这属于 staging 配置未填，还是上线检查清单遗漏了这一项？
建议把该配置列为**上线前置检查项**，并考虑在未配置时于后台退货审批处给出显式警告
（现在批准退货不会提示"退货地址还没配"）。

## D-24【P1】支付失败后 App 完全无感知，用户对着二维码干等
**复现**：下单（PawCoin 120,000 + QRIS 54,000）→ Pay now 生成支付单 →
后台模拟器点「失败」→ 提示条 `已模拟回调：… → FAILED`，支付记录状态转「支付失败」。
| 位置 | 显示 | 正确性 |
|---|---|---|
| 后台支付记录 | 支付失败 | ✅ |
| 后台订单状态 | PENDING_PAYMENT | ⚠️ 见下 |
| **App 二维码页** | 一直 `Waiting for payment…`（等待 15s 无变化） | ❌ |
| **App 订单详情**（退出重进强制刷新） | `QRIS · waiting` | ❌ |
🔴 **用户会一直盯着二维码等一笔永远不会成功的支付**，直到 60 分钟超时自动取消，
全程无任何"支付失败，请重试"的提示。
🔑 **App 有能力感知支付状态**：测「成功」分支时，模拟成功后 App **自动跳转到订单列表**
⇒ 轮询/推送链路是通的，**只处理了成功、没处理失败**。
**修复**：支付状态轮询需处理 FAILED 分支 —— 关闭二维码页并提示失败原因与重试入口。
📎 订单停留在 `PENDING_PAYMENT` 本身合理（支付失败 ≠ 订单作废，倒计时内可重新发起），
但前提是 App 要告知用户。

## 观察：后台用户页 PawCoin 余额不区分「可用」与「冻结中」
下单后 120,000 全额处于 Held 状态，后台用户详情页仍显示「PawCoin 余额: 120,000」。
两次测试（70,000 单、120,000 单）表现一致，属既有行为而非本次引入。
⚠️ 运营处理客诉时会误判用户仍有 12 万可用。建议拆分显示「可用 / 冻结中」。

## D-24 补充：EXPIRED 分支表现完全相同，确认 App 只处理 PAID
模拟器「过时」分支实测（订单 edMafoxvnWfeMd72luH0xT，支付单 PAYSHOP-20260902-000122）：
支付记录 `待支付 → 已过期` ✅，但 App 二维码页仍 `Waiting for payment…`、
订单详情强制刷新后仍 `QRIS · waiting` ❌。
⇒ **D-24 覆盖 FAILED 与 EXPIRED 两个分支**，App 的支付状态轮询**只处理 PAID**。
🔴 EXPIRED 场景更值得注意：支付通道已判定该单据作废，
**用户扫那个二维码只会付款失败**，而 App 还在让他扫。

## ✅ V-15 支付模拟器三分支全部验证通过（后台侧）
| 分支 | 后台支付记录 | 提示条 | App 响应 |
|---|---|---|---|
| 成功 | 待支付 → 已支付 ✅ | `已模拟回调：… → PAID` | ✅ 自动跳转订单列表 |
| 失败 | 待支付 → 支付失败 ✅ | `已模拟回调：… → FAILED` | ❌ 无感知（D-24） |
| 过时 | 待支付 → 已过期 ✅ | `已模拟回调：… → EXPIRED` | ❌ 无感知（D-24） |
三分支均正确转移状态、操作列正确回落为 `—`、提示条文案含 idempotency 说明
（「若已是终态则无变化」）。**模拟器本身无缺陷，问题全在 App 侧状态处理。**

## ✅ V-16 质检不通过分支 + 质量问题责任判定，全线正确
退货单 `zu1jPVzw6JxBG8obWHFG7h`（订单 oVQe1TcREzPGaQJwGSlM2Y，纯 PawCoin 支付 Rp 15,001）
路径：申请（原因 Damaged）→ 批准 → 登记寄回 → INSPECTING → **质检不通过**（disposal=RETURN_TO_USER）
→ 状态 `REJECTED`（终态，无可用操作）。

🔴 **质量问题与非质量问题的责任判定形成完整对照**：
| 字段 | 上一单 NON_QUALITY_ISSUE | 本单 QUALITY_ISSUE |
|---|---|---|
| 回程运费 | `USER`（买家承担） | **`PLATFORM`（平台承担）** ✅ |
| 去程运费退回 | false | **true / 15,000** ✅ |
| 整单退 | false | true |
| App 端费用提示 | `Return shipping is paid by the buyer` | **`Return shipping is covered by TailTopia`** ✅ |

**退款试算正确**：商品 1 + 去程运费退回 15,000 + 回程运费返还 9,000 = **总退回 24,001** ✅
（回程运费 9,000 即登记寄回时录入的金额，平台责任时返还给用户。）
**驳回信息完整**：驳回原因全文保存；商品处置显示 `RETURN_TO_USER · BACK-JNE-20260902-003`
（处置方式与退回物流单号一并留痕）。

🏆 **一处值得推广的写法**（本次新发现）：补偿溢价行带**触发依据说明** ——
`平台责任补偿溢价 = 0 · 触发依据：退货类型为平台责任`
明确区分了"没触发"与"触发了但费率为 0"。
⇒ 这正是 **D-21**（售后成本 0 值无口径说明）应当采用的写法，
也从界面侧印证了 D-11 中 `compensationPremiumRate = 0` 的结论。

## D-12 第六次复现 + D-13 复现
- App 提交本次退货后页面仍不跳转、无提示；
- 后台「批准 / 登记寄回 / 质检不通过」三步同样无提示条；
- 退货详情「凭证图」仍为 `return-evidence-1,return-evidence-2` 纯文本（D-13）。

## D-11 观察项复现：退货原因回显再次被合并
本次选「Damaged / packaging leaking」，退款方式页摘要显示为
**「Damaged, leaking or near expiry」** —— 把申请页第 2 项（Damaged）与第 3 项
（Expiry date too close）合并。与上一单「Changed my mind」被显示为
「Changed my mind / wrong variant」是同一问题：**前端 4 个选项映射到后端较少的
ReturnType 枚举，回显时用了枚举标签而非用户的实际选择。**
