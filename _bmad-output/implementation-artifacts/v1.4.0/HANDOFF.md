---
title: "V1.4.0 电商工作线 · 会话交接"
type: handoff
updated: 2026-08-18
branch: shawn/oneline-ecommerce
head: f9c98bf2
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

**测试基线：后端 `mvn -B test` **1991 通过 / 0 失败 / 6 跳过**（BUILD SUCCESS）；
前端 `flutter test` **953 通过**、`flutter analyze` 零问题。**

> ✅ **NOTIFY-CURSOR-TIE 已修**（2026-08-18，经用户要求跨模块动手）：
> 通知中心游标改 `(created_at, id)` 复合、编码按**微秒**、V125 补配套索引。
> 🔴 `nextCursor` 的 wire 格式因此变了（→ `"<epochMicros>_<id>"`），对客户端是**不透明串**，
> 服务端保留一轮过渡兼容。详见 sprint-status 的 `action_items`。
>
> ⚠️ **同一类缺陷的兄弟没修**：`OrderCenterService#listOrders` 也是
> 「epochMillis + 严格 `<`」。它是**三线共享**文件，且是跨 3 源 in-memory 归并，
> `(created_at, id)` 不直接成立 —— 记为 `ORDER-CENTER-CURSOR-TIE`，须先认领再动。

## 一 B · 🔴 交付前必须由人完成的事（代码这边做不了）

| # | 事项 | 卡在哪 |
|---|---|---|
| 1 | **全部 App story 的 L2 视觉验收** | 云端 headless 跑不了模拟器，必须本地 |
| 2 | **PostHog 后台实测收到全部事件**（Story 9.3 L2） | 需真机 + 真实 key |
| 3 | 🔴 **V121 `pet_profiles` 加列的共享表认领** | **不在产品临时授权范围内**，须补签 |
| 4 | **HEX-SIGNOFF 未发出** | 见同目录 `HEX-SIGNOFF.md` |
| 5 | Story 1.3 遗留的 3 个 L1 | 见该 story 的 Completion Notes |
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
- Story 1.3 的 3 条 L1（现有 `staffWith(...)` 范式，照抄即可，约 20 分钟）
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
