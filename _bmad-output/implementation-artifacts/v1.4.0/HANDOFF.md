---
title: "V1.4.0 电商工作线 · 会话交接"
type: handoff
updated: 2026-08-18
branch: shawn/oneline-ecommerce
head: 77f5518b
---

# V1.4.0 电商工作线 · 会话交接

> **给下一个会话的第一句话：** 57 条 story 的**代码全部写完了**（55 条 review + 2 条按纪律主动留白）。
> 剩下的**不是写代码，是人工验收**：全部 App story 的 L2 视觉、PostHog 实测、两份签字、一条共享表认领。
> **接手只需读两份：** 本文件 → `sprint-status-v1.4.0.yaml`（进度、Flyway 号段、action_items）。

---

## 一 · 进度（55 / 57 review · 2 条主动留白）

| Epic | 进度 | 说明 |
|---|---|---|
| **1 商品上架与浏览** | **8/8 ✅** | 后端 + 后台 + App 三段齐全，含全链路 L1 |
| **2 收货地址与配送范围** | **5/5 ✅** | 含全链路联调 + PII 护栏。⚠️ 2.2 原缺 AB-11C 后台页，已由 Epic 5 补 `shop-shipping.html` |
| **3 完成一次购买** | **10/10 ✅** | 含全链路 L1（加购→结算→混合支付→到账→订单可见）与埋点收口 |
| **4 履约物流与收货** | **6/6 ✅** | SPEC-2 三条出口齐全（后台标记 / 用户在已发货态确认 / 7 日自动） |
| **5 退货与退款** | **9/10** · 1 留白 | 🔴 AD-2 整数累计法；`5-9 退货进度页` 待 UX-DR5 |
| **6 复购引擎** | **7/7 ✅** | 版本核心。⚠️ 6-1 触碰共享表 `pet_profiles`，**认领待补** |
| **7 商品评价** | **3/4** · 1 留白 | `7-2 评价页` 待 UX-DR4 |
| **8 经营数据与对账** | **4/4 ✅** | 权限位 49 → 53 |
| **9 边界守护与效果度量** | **3/3 ✅** | FR-110 能力缺席守卫已入 CI（两端各一步命名 Guard） |

**测试基线（2026-08-19）：后端 `mvn -B test` **2017 通过 / 0 失败 / 6 跳过**（BUILD SUCCESS）；
前端 `flutter test` **1127 通过**、`flutter analyze` 零问题。**

> ⚠️ **本机跑 L1 前先看一眼 Flyway**：本地 `petgo` 库曾应用过 `V86__add_column_comments.sql`
> （来自 `a2cab7f1`，当前分支与 main 都没有该文件），Flyway 校验会以
> `Detected applied migration not resolved locally: 86` **拒绝启动上下文** ——
> 症状是整类测试 17 条全 Error 且报错内容与真正原因毫无关系。
> 2026-08-19 已按 repair 的等效做法删掉该 history 行（V86 只是 `COMMENT ON`，幂等，
> 那个 commit 若合回来重跑也安全）。留档：scratchpad 的 `v86-history-row.tsv`。

> ✅ **NOTIFY-CURSOR-TIE 已修**（2026-08-18，经用户要求跨模块动手）：
> 通知中心游标改 `(created_at, id)` 复合、编码按**微秒**、V125 补配套索引。
> 🔴 `nextCursor` 的 wire 格式因此变了（→ `"<epochMicros>_<id>"`），对客户端是**不透明串**，
> 服务端保留一轮过渡兼容。详见 sprint-status 的 `action_items`。
>
> ✅ **同族的另外两处也一起修了**（同日，经用户要求）：
> `ORDER-CENTER-CURSOR-TIE`（订单中心，跨 4 源归并 → 全序改
> `(createdAt, sourceRank, id)`）与 `PAWCOIN-LEDGER-CURSOR-TIE`（钱包流水 ——
> **同族里最容易撞上的一处**：一次结算在同一事务里写多条，时间戳一模一样）。
>
> 🔴 **这三处的 `nextCursor` wire 格式都变了**（→ base64url 复合键）。对客户端都是
> **不透明串**，Flutter 侧只原样回传（已逐个核对）；服务端各留一轮过渡兼容。
> 新代码写分页请直接用 `shared/paging/KeysetCursor`，别再手写 epochMillis 游标。

## 一 B · 🔴 交付前必须由人完成的事（代码这边做不了）

| # | 事项 | 卡在哪 |
|---|---|---|
| 1 | **全部 App story 的 L2 视觉验收** | 云端 headless 跑不了模拟器，必须本地 |
| 2 | **PostHog 后台实测收到全部事件**（Story 9.3 L2） | 需真机 + 真实 key |
| 3 | 🔴 **V121 `pet_profiles` 加列的共享表认领** | **不在产品临时授权范围内**，须补签 |
| 4 | **HEX-SIGNOFF 未发出** | 见同目录 `HEX-SIGNOFF.md` |
| 5 | ~~Story 1.3 遗留的 3 个 L1~~ | ✅ **2026-08-18 已补齐**（8 条 L1），并抓到 SKU 新建端点的真缺陷 |
| 6 | ⚠️ **S-12 赠币核销近似值须财务确认** | 不够用就得回头改钱包（全套代价最高的返工） |
| 7 | 两条留白 story（5.9 / 7.2）等 UX-DR5 / UX-DR4 出稿 | 后端接口都已就位，出稿即可开工 |
| 8 | ⚠️ **基线埋点缺口无法弥补** | 见 `EPIC-9-DELIVERY-NOTE.md` §4 —— 别让它变成第二次 L-6 |

> 🔴 **跑全量时务必显式 grep `BUILD` 行**。我曾用 `tail -2` 抓输出，`BUILD FAILURE` 被淹掉，
> 结果在红着的构建上提交（`f470572c`）。正确写法：
> `mvn -B test 2>&1 | grep -E "^\[INFO\] Tests run: [0-9]+, Failures|^\[INFO\] BUILD|^\[ERROR\] BUILD" | tail -2`

---

## 二 · 环境（照做即可，全部已验证）

```bash
# L1 需要真实 pg + redis。Docker Desktop 早就装了，只是不自启。
open -a Docker      # 约 60~90s
docker run -d --name petgo-pg -e POSTGRES_DB=petgo -e POSTGRES_USER=petgo \
  -e POSTGRES_PASSWORD=petgo -e TZ=UTC -e PGTZ=UTC -p 5432:5432 postgres:17-alpine
docker run -d --name petgo-redis -p 6379:6379 redis:7-alpine

cd petgo-backend && mvn -B test          # 全量约 35s
cd petgo_app && flutter analyze && flutter test
```

- `java -version` 显示 17 但 **Maven 跑在 JDK 25** 上，产物是 Java 21 字节码。**不需要装 JDK。**
- 🔴 **回归必须跑全量**，不得用 `-Dtest='Shop*...'` 窄过滤器 —— 跨模块护栏不在本模块前缀里，Story 1.3 的 3 个红灯就是这么漏的。
- ⚠️ 起 Docker Desktop 会顺带拉起另外两套无关 stack（Dify + logistic），`logistic-app` 占 8080。

---

## 三 · 🔴 五条硬纪律（本工作线用血换来的）

### 1. 写完护栏必须做变异验证
**已出过三次假绿**，形状不同但同一病根：**护栏的判定方式与它声称看守的对象对不上**。
- `AdminPermissionWiringTest` 只认 `hasAuthority` 字面量，认不出编程式门控
- `damageSqlGuardsAvailableNotActual` 用整文件 `contains`，被同形状的 `lock()` 替它满足
- `newPrimitivesClearPersistenceContext` 用「出现次数 ≥ N」，被合规项替违规项凑数

**规矩三条**：断言字符串前先确认它在文件里唯一 · 不用计数式断言 · 写完就删掉被守护的东西看它变不变红。

### 2. 加权限码要同步改 4 处
`AdminPermissions` 常量 → `GROUPS` 两个 `List.of` → **`perm.<code>` i18n 三份** → `AdminPermissionsTest.listStableSize` 数字（当前 **53** —— Epic 4/8 各加两个）。
🔴 第 3 项**没有任何护栏看守**，Story 1.3 就漏在这里。

### 3. 新 POST 端点必须本地 `catch AppException`
`GlobalExceptionHandler` 是 `@RestControllerAdvice`，不 catch 就给运营吐 RFC 9457 裸 JSON。
仓库 17 个既有 admin 控制器全部本地 catch → `error` flash + redirect。成功用 `notice`。

### 3B. 🔴 `@ModelAttribute` + `{id}` 路径变量会互撞（2026-08-18 新增）
Spring 的 `ExtendedServletRequestDataBinder` 把 **URI 模板变量一并绑进 `@ModelAttribute`**
（仅当同名请求参数缺席时）。`POST /admin/shop/products/{id}/skus` 因此把**商品 id** 绑进了
`ShopSkuForm.id`，让「新建规格」永远走成「更新规格」——
**页面上没出事只是因为模板恰好渲染了一个空的 `<input name="id"/>`**（浏览器提交 `id=""`，
参数在场 → URI 变量被跳过）。换个调用方就复现，撞号时还会**静默覆盖既有 SKU**。

**规矩：** URI 变量名不得与 `@ModelAttribute` 表单类的任一字段同名。
写 `{productId}` / `{orderId}`，不要图省事写 `{id}`。全仓当时只此一处，已修。

### 4. 埋点命名护栏
必须「**模块前缀 + 动作词尾**」，两者都在 `v112_events_test.dart` 的允许清单里。新模块**注册新前缀**（已注册 `toko_`），🔴 **不放宽规则、不塞 legacyEvents 蒙混**。

### 5. 未知枚举值一律降级到最保守档
未知 `returnPolicy` → `NO_RETURN`（宁可少承诺）；未知 `stockStatus` → `OUT_OF_STOCK`（宁可挡一次购买，不可放过一次超卖）。

---

## 四 · 已闭合的规格缺口（别再当阻塞）

| 缺口 | 闭合方式 |
|---|---|
| **SPEC-11** 拒收货入库路径 | 决策 **S-9**：退货入库批次、采购单号填原订单号、单价取最近一次采购价，不允许留空 |
| **SPEC-7** 下架时已锁定库存归属 | **2026-08-17 产品拍板：下架只改可见性**，不动库存、不取消订单、不发通知。召回走 S-3 的 AB-11D 手工选单取消 |
| **SPEC-8** 配送方式二维表 | **C-14** 降为一维，只剩 Reguler 一档 |
| **DEP-6** 每日建议喂量 | 已解除，喂量印在包装背面，录入时照抄 |
| **P-1 契约** 的两个 CHECK | 产品**临时授权**生效（HEX-SIGNOFF §临时授权），3-3 / 6-3 可照推 |
| **Flyway outOfOrder** | `application.yml:171` 显式 `true`；「默认 false 会拒绝启动」是**错的**，别再引用 |

---

## 五 · ⏳ 仍然真阻塞的（只有两件）

1. **UX-DR4 评价页 / UX-DR5 退货进度页视觉稿未交付** → 挡 **7-2** 与 **5-9** 的前端。
   这两条 story 写死了「实现前不得自行发挥」。**做法：先做后端，前端留稿子到位。**
2. **邮编 ↔ Kecamatan 对照表缺失**（Story 2.1 新登记）→ 邮编一致性校验无法实现。
   与 Story 2.2 的服务范围是同一份基础数据，建议一并向运营要。

**非阻塞但欠着的账：**
- ~~Story 1.3 的 3 条 L1~~ ✅ **2026-08-18 已补齐**
- 全部 App story 的 L2 视觉验收（需 GUI，**只能人工**）
- **HEX-SIGNOFF 至今没发出去**（有临时授权兜底，但代价随时间增长）

---

## 六 · Flyway 号段（**实现时取当前最大号 +1 并立刻回写台账**）

已用 **V101–V112**（V105 空号，Story 1.5 无需迁移）：
V101 商品/SKU · V102 库存 · V103 进货价 · V104 库存流水 · V106 收货地址 · V107 配送区域 ·
V108 购物车 · V109 订单 · **V110 ⚠️共享表 payment_intents 混合支付** · V111 订单归因+PawCoin 规则+拆分列 ·
**V112 ⚠️共享表 pawcoin_config 补偿溢价**。
**下一个取 V113。**
🔴 **绑定规则只有一条：取最大号 +1 并回写。** 台账曾因没回写而脱节一位。

---

## 七 · 测试基建的四处坑（Flutter 侧，Epic 3 会再遇到）

1. **Riverpod 3 移除了 `StateProvider`** → 用 `family` + 页面局部 State
2. **`Override` 不是可直接书写的类型名** → `List<Object>` + `.cast()`
3. **`ListView` 懒加载**：默认 800x600 画布会让下半页根本没 build → 测试里放大到 1200x3000
4. **同一 `testWidgets` 里循环 `pumpWidget` 会复用 widget 树**，第二轮的 override 不生效 → 拆成独立用例

后端侧两处：
1. **全局计数型断言（如 SKU 上限）与共享测试库冲突** → 用 `@TestPropertySource` 给该类独立配置 + `@BeforeEach` 清状态；精确边界留 L0 用 mock 覆盖。**高上限统一用 500**，值相同才共享同一个 Spring 上下文。
2. 🔴 **每多一个 `@TestPropertySource` 变体就多一个 Spring 上下文、多一个连接池**（不释放）。曾因此打满 postgres 的 `max_connections=100`，症状是**一堆无关测试报 `Failed to load ApplicationContext`、单跑却全绿**，误导性极强。已在 `ApiIntegrationTest` 基类上限制池大小（`maximum-pool-size=4`），**别去掉**。

**造数纪律**：走真实 service，别直接 INSERT。我曾直接写 `pawcoin_wallets` 结果表结构写错（该表无 `created_at`），而且直接 INSERT 会绕过双分录不变量。

---

## 八 · 工作方式

- 用户要**尽快做完全部**：按顺序推进，**不要停下来反复确认**；给选项时把推荐项放第一个
- 每条 story 三段：**后端 → 前端 → 联调**
- **L1/L2 没跑就不勾**，如实标注（本工作线至今没谎报过一条）
- story 文档可从简，**精力放在代码、测试与变异验证上**

---

## 九 · Epic 3 已完成 —— 交给 Epic 4 的东西

| 已就位 | 说明 |
|---|---|
| **订单状态机** | 只开了「待支付 → 待发货 / 已取消」两条边。Epic 4 加发货段、Epic 5 加退款段，**各加各的** |
| **支付链路** | `ShopOrderPaymentService`（pay/cancel/懒过期/扫描）· `ShopOrderPaidHandler`（同事务 MANDATORY）· `ShopOrderExpiryScanner`（1min 兜底）—— **不要碰既有逻辑，只加自己的边** |
| **订单中心第 5 类** | `OrderCenterService` 已被本工作线改过（+70/−1，只追加）。Epic 4 若要加履约态卡片，**先 merge main 再改**，仍只在末尾追加 |
| **订单详情页** | `/shop/orders/{token}`（3.8）。Epic 4 的履约态在同一页加区块即可，`ShopOrderDetailView` 只加字段不改语义 |
| **归因链** | 已闭合：加购记来源（V114）→ 下单抄到订单行。Epic 6 的复购触发只需在加购时传 `triggerType` |
| **RFC 9457 扩展成员** | `shared/error/ProblemExtensions`：业务异常实现它即可让逐行明细搭上统一信封（含 traceId） |

### 🔴 Epic 3 留给后面几条的五条硬结论
1. **游客态必须在数据层短路**：任何 `/me/*` provider 被游客 watch 一次就会 401 → 强登录引导 = 变相登录墙。
   守它的用例必须直读 `ProviderContainer`（widget 用例守不住，变异 M5 实测假绿）。
2. **`showSoftSheet` 有 `allowRepeat`**（默认 false）：用户主动动作触发的引导要传 `true`。
3. **金额一处算**：结算试算与下单共用 `CheckoutService`，前端一个数都不重算。
4. 🔴 **Java 变异验证还原源码后必须 `touch`**：`shutil.move` 会连 mtime 一起还原，Maven 增量编译
   便跳过重编，后续构建跑的仍是变异后的 class —— 表现为一条与改动毫无关系的红，极难排查。
5. 🔴 **纵深防御制造假阳性绿灯 —— 本工作线已 5 次应验**（最近两次：B1 重复回调被意图层幂等兜住、
   H3 用整文件 `contains` 断言行为被方法签名替它满足）。写安全攸关测试时先问两句：
   **「我在守哪一层」**与**「删掉被守护的那行，它会不会照样绿」**。

### 🔴 仍然悬着的两件事
1. **SPEC-6 的四条缺边**（拒收 / 退款驳回回边 / 退款执行失败 / 用户撤销退货）**须在 Epic 5 前闭合**。
2. ⚠️ **类型 chips 的分组口径待 UX 拍板**：现「Konsultasi」只映射兽医、AI 归「Lainnya」——
   后端筛选一次只接受一个类型。

---

# 十 · 2026-08-19 · 电商 UI 换版（design_handoff_ecommerce）

设计交付物在 `~/Downloads/design_handoff_ecommerce/`（18 屏 + tokens + 3 份分屏文档）。

## 10.1 🔴 双 UI 并存，v1 一行未改

用户明确要求「保留两种 UI 做对比」。实现方式：`shopUiVariantProvider`（`features/shop/presentation/shop_ui_variant.dart`）+ 路由层 `ShopUiSwitch` 二选一。

```bash
flutter run                              # v1（默认，发布安全）
flutter run --dart-define=SHOP_UI=v2     # v2（设计稿版式）
```

App 内 Toko / 订单页顶栏有 debug-only 的 ⇄ 图标可随时切。**默认必须是 v1** —— 新版式尚未人工验收，任何忘记指定的构建都该拿到已验收的那套（有测试锁着）。

**收益**：`test/shop/` 4218 行既有断言一条没动。原计划「逐屏替换 + 改断言」最大的风险是把**真回归**误当成「版式变了很正常」给改掉。

⚠️ 切换入口在 v1 顶栏渲染成**图标不是文字** —— `toko_page_test.dart` 有一条「`Kategori` 之前不得出现除 `Toko` 以外的任何 Text」的护栏，塞文字会误伤它。

## 10.2 完成度

| 批次 | 内容 | 状态 |
|---|---|---|
| 第 0 批 | Poppins 800 + IBM Plex Mono（子集化 271KB→13.5KB）、`shop_tokens.dart`、静默期落数据层 | ✅ |
| 第 1 批 | Toko 首页 / 商品详情（在售+售罄）/ 购物车 / 结算（正常+超范围）/ 新增地址 | ✅ 全部上机验过 |
| 第 2 批 | 订单列表 / 订单详情（待支付+已发货）/ 退货申请 / 退款方式 | ✅ 全部上机验过（2026-08-19，抓到 7 个缺陷，见 10.7） |
| 第 3 批 | Diary 触发卡 / 档案推荐区 | ✅ 全部上机验过（2026-08-19，见 10.7） |
| 第 3 批 | 征询卡 / 关闭态软提示 / 推荐设置 | ⏸ **冻结**，见 10.4 |

测试：前端 955 → **1127**，后端 2003 → **2012**。全程 **51 次变异验证**，无一假绿。

## 10.3 🔴 上机才抓到的 3 个缺陷（单测与 analyze 全绿）

这三个都是「跑起来才看得见」的类型，记下来是因为**每次上机都抓到了东西**，第 2/3 批还没上机：

1. **网格卡溢出 4.4px**：用 `childAspectRatio`（高度随屏宽变）去装定高内容。修法不是调准比例，是让溢出**结构上不可能**：名称 `Expanded` 吸收余量、价格贴底。
2. **顶栏标题被屏幕边缘切掉半个字**：`titleSpacing: 0` 只在左边确实有返回箭头时才对，深链直达时没有箭头。影响所有 v2 页面。
3. **左色条块不撑满宽度**：`Container` 没给 `width`，child 是纯 `Text` 时缩成文字宽。

## 10.4 ⏸ 冻结：FR-108B 三屏（征询 / 软提示 / 设置）

**冻结原因不是缺接口，是缺决策。**

要存的状态：征询结果（含**永久不再问**）、软提示计数（14 天 1 次 / 累计 3 次 / 连续 2 次 × 即永久静默）、4 个开关 + 频率三档。

- 这些**必须服务端持久化**：做成本地 prefs 的后果是用户点了「Tidak, jangan tanya lagi」，换手机或重装又被问一遍 —— 那正是这三屏要解决的问题本身。
- 设计稿 README 自己把 FR-108B 列为 Open Question：**不在原 PRD 内，需回写 PRD 并确认频率上限等具体数值**。数值没定就建表 = 给还没决定的数字建 schema。

**解冻条件**：① PRD 回写 + 频率数值定稿 ② 一张新表 + Flyway 号（注意 §六 的号段撞车）③ settings 实体 + 端点。

推荐区底部留了「Atur rekomendasi」文字入口但**没放开关** —— 一个关了之后自己又打开的开关，比没有更伤信任。测试里有一条锁住「不得出现没有持久化的假开关」。

## 10.5 🔴 设计稿与代码库的实质冲突（已按判断处理，需产品确认）

| # | 设计稿要求 | 实际 | 处理 |
|---|---|---|---|
| 1 | 退款页「无单选控件，去向不可选」，现金段 `→ QRIS asal` 原路退回 | **本支付栈不支持原路退**，后端要求选去向 + 填账号 | PawCoin 段照做；现金段保留控件；「去向不可改」那句**只贴 PawCoin 段** —— 整页照抄是假话，而这是钱的事 |
| 2 | 购物车每行勾选框 | 下单接口整车下单，无行选择 | **不画** —— 画一个不影响下单的勾选框 = 用户勾掉一行仍会被买走，能造成资损 |
| 3 | 退货照片 min 2 / max 5 | v1 是「质量问题 ≥1，最多 6」 | ✅ **2026-08-19 产品确认以 5 为准**：后端 `MAX_EVIDENCE` 6→5 已对齐（原先只挡在前端 = 换个调用方就能传 6 张），并补了 `evidenceCapIsFiveOnServerSide` 护栏（此前该上限**无任何测试守着**）。⚠️ 仍会挡住只拍一张的用户，属已确认的产品取舍 |
| 4 | 订单列表状态 Tab（Belum bayar / Dikirim / Selesai） | 列表接口只有 `type` 筛选 | 保留 Tab 样式承载类型筛选 —— 端上按状态过滤游标分页会造出「这个 Tab 是空的」的假象 |
| 5 | 详情页 `128 terjual` 已售数 | 只有评价数，两个不同的量 | 不显示 —— 贴 `terjual` 标签在评价数上是造假数据 |
| 6 | 个性化推荐卡的来源标签（含日期） | `reason` 是规则维度（年龄/体型），不是记录+日期 | **恒走降级态 MODE DASAR** —— 挂上「来自你的记录」就是把泛推荐伪装成个性化 |
| 7 | 超范围时列出已开通城市（稿里写死 3 个城市名） | — | 改从 `GET /shop/regions` 取**真实数据**，运营开新城不用等发版 |

**为满足设计稿硬要求而补的后端**（我自己 package 内追加）：`RepurchaseScanService.basisFor()` + `RepurchaseCardView` 末尾追加 `dailyGrams` / `remainingGrams` / `purchasedOn`。设计稿把「日均用量·剩余量·购买日期」列为**缺一不可** —— 它是这张卡区别于广告位的唯一凭据。三者同时给或同时不给，任一算不出就整卡不渲染。

## 10.6 🔴 本地环境的两个陷阱（都踩过）

1. **跑后端全量测试会清空本地 `petgo` 库**（77 张表 → 0）。重启后端会 Flyway 迁回来，但**演示数据要重灌**。没深挖是哪一步干的（测试依赖里有 `spring-boot-starter-flyway-test`）。
2. **DB 一旦被重置，Redis 里所有键都变成孤儿**。用户 id 序列从头开始，新用户直接继承旧账号的 `notify:unread:*` 角标 —— 表现是 `NotificationControllerEndpointTest` 3 条红，而报错内容和真正原因毫无关系。**重置 DB 记得连 Redis 一起清**：`docker exec petgo-redis redis-cli -n 0 FLUSHDB`。

顺带修了 `scripts/seed-shop-demo.sql`：采购入库那段原先用 `COALESCE(..., 1)`「找不到管理员就退回 id 1」，而全新库里一个管理员都没有 → 外键报错，信息里完全看不出「你得先建管理员」。改成跳过该段 + 大声警告，商品/SKU/库存照常灌。**staging 是生产克隆，也未必有 id=1 的超管。**



---

## 10.7 🔴 第 2/3 批上机验收（2026-08-19）—— 又抓到 7 个，且**全部是单测与 analyze 全绿的**

第 1 批上机抓到 3 个（10.3），这次 7 个。**每次上机都抓到东西，且一次比一次多** ——
说明「L0 绿灯 ≠ 可交付」在这条工作线上不是个别现象，是常态。

### 验收环境（照做即可复现）

```bash
open -a Docker                                   # pg + redis 两个容器
cd petgo-backend && mvn -B spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
# 🔴 端口是 8081 —— 8080 被 Docker Desktop 顺带拉起的 logistic-app 占着（见 §二）
{ echo "SET petgo.allow_demo_seed='yes';"; cat scripts/seed-shop-demo.sql; } \
  | docker exec -i petgo-pg psql -U petgo -d petgo -v ON_ERROR_STOP=1
cd petgo_app && flutter build apk --debug \
  --dart-define=SHOP_UI=v2 \
  --dart-define=PETGO_API_BASE_URL=http://10.0.2.2:8081 \
  --dart-define=DEV_GOOGLE_STUB=true \
  --dart-define=DEV_LOCALE=id
```

⚠️ **演示商品可能是 `is_active=false`**（集成测试跑完的残留状态），Toko 会是空的。
`UPDATE shop_products SET is_active=true WHERE public_token LIKE 'demo-%';`

⚠️ **dev 用户（id=1）默认零订单、无宠物档案**，7 屏里 6 屏点不动。造数脚本见
`_bmad-output/implementation-artifacts/v1.4.0/seed-user1-l2.sh`（下单/支付/收货走真实 API，
只有「发货」和「日期回溯」用 SQL）。要点：
- **混合支付**要靠 `shop_pawcoin_rules.max_coin_per_order` 封顶（设为 50000），
  光给余额会让整单被币付掉、拿不到两段拆分。
- **有现金段的订单 `pay` 之后不会自动结清** —— 要补一发 stub 网关回调：
  `POST /pay/callback` form `order_id=<intentToken>&transaction_id=stub-<intentToken>&transaction_status=settlement`。
- **复购触发卡**要 4 个条件同时成立：宠物档案有体重、商品有 `feeding_guide`、SKU 有
  `net_weight_g`、源订单 `delivered_at` 回溯到「耗尽日 −7 天」窗口内。
  演示数据里 Whiskas 1.2kg + 3.4kg 猫 = 55g/天 → 21 天 → 送达日回溯 16 天正好落窗口。

### 7 个缺陷（均已修 + 补护栏 + 变异验证）

| # | 屏 | 症状 | 根因 |
|---|---|---|---|
| 1 | 订单列表 | 状态位显示 **`SHIPPED`** 而不是 `Dikirim` | `orderStatusLabel` 的 `switch` 没有履约两态分支 → 落兜底 `_ => statusCode`，**把后端枚举原样显示给用户**。⚠️ 这个函数 v1 也在用，v1 同样在漏 |
| 2 | 订单列表 | 电商待支付单**一个按钮都没有**，金额也不是玫红 | 判定写的是 `statusCode == 'PENDING'`，而电商待支付码是 **`PENDING_PAYMENT`**（两个状态码空间同名不同值）。这个相等判断恒不成立，但它完全合法 |
| 3 | 订单详情 · 待支付 | 按钮写 `Bayar Sekarang Rp 171.000`，而现在真要付的现金只有 121.000 | 用了 `totalAmount`。币段下单时已冻结，写总额等于告诉用户「币白冻了，还要再付一次全款」 |
| 4 | 订单详情 · 已发货 | `Total bayar 83.000` + `+ 50.000 PawCoin` —— **把币算了两遍**（同屏 QRIS 块写的是 33.000，自相矛盾） | 总额本就含币段，再跟一行「+ 币」就是重复计。设计稿该行是 `Dibayar 现金 + N PawCoin` |
| 5 | 退款方式 | 页头第三行与流程第 3 步都显示 **`PLATFORM`** | `p.returnShipBearer ?? (按类型推)` —— 服务端**恒有值**，`??` 右半边永远不执行。l10n 文案早就写好了，只是够不着 |
| 6 | 档案推荐区 | 说明块与商品网格之间白吞 **54dp**（实测，正好一条状态栏） | 内层 `GridView` 没显式给 `padding`。`BoxScrollView` 在 `padding == null` 时会**自动把 `MediaQuery.padding` 的主轴部分当自己的内边距**；外层 `ListView` 自己设了 padding，因此没把 MediaQuery 剥掉 |
| 7 | 订单详情 · 待支付 | 次操作 `Batalkan Pesanan` 折成两行，把底部条撑高、次操作看着比主操作还重 | 设计稿写的是单词 `Batalkan`。补 `shopOrderCancelShort` |

### 🔴 这一轮真正的教训：**夹具不还原服务端真实下发值，护栏守的是另一个世界**

缺陷 5 和 6 的单测早就存在，而且断言写得没错 —— 它们只是**在一个不可能出现的输入上通过的**：

- 退款方式页的夹具里 `returnShipBearer` **恒为 null** ⇒ `??` 的右半边在测试里总是生效、
  在真机上永远不生效。测试测的是一条线上根本走不到的分支。
- 推荐区的夹具用 `MediaQueryData()`，`padding` **恒为零** ⇒ 「GridView 会继承 MediaQuery.padding」
  这个 Flutter 行为在测试里根本不会发生。

两处夹具默认值都已改成真实值（`'PLATFORM'` / `EdgeInsets.only(top: 54, bottom: 24)`）。
**新写页面级 widget 测试时，夹具的默认值应当取「线上最常见的那个值」，而不是「最省事的那个值」。**

### 设计稿要求但当前**无数据源**（不是 bug，别去硬做）

| 屏 | 设计元素 | 缺什么 |
|---|---|---|
| 订单列表 | 待支付卡的 `mm:ss` 倒计时 | 列表接口不下发 `expiresAt`（详情才有） |
| 订单详情 | `Nomor Pesanan` 显示的是内部 token，与列表页的 `TOKO-20260819-000673` 对不上 | 详情接口不下发 `displayNo`。**同一张单在两个页面显示两个"单号"，用户对不上** —— 建议补字段 |
| Diary 触发卡 | 商品条的价格与划线原价 | `RepurchaseCardView` 无价格字段 |
| 订单详情 · 已发货 | `Perkiraan tiba 20 Agu` 预计送达 | 无 ETA 字段（页面头注释已记录） |

### ⏳ 一条留给产品的文案

退货申请页的「开封不退」块，设计稿要求**追加拒退后果（货寄回买家）**；
当前追加的 `returnNoReturnAfterOpenNotice` 只是把同一条规则换句话再说一遍，
读起来像拼接，且没说后果。**需要一句新的印尼语文案**，不自行编写。
