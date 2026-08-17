---
title: "V1.4.0 电商工作线 · 会话交接"
type: handoff
updated: 2026-08-18
branch: shawn/oneline-ecommerce
head: 2329822d
---

# V1.4.0 电商工作线 · 会话交接

> **给下一个会话的第一句话：** 用户目标是**尽快做完全部 57 条 story**。按 Epic 顺序、story 编号升序推进即可，不必等确认。
> **接手只需读两份：** 本文件 → `sprint-status-v1.4.0.yaml`（进度与 Flyway 号段）。
> 其余产物**不必通读**，用到哪条 story 读哪条。

---

## 一 · 进度（21 / 57）

| Epic | 进度 | 说明 |
|---|---|---|
| **1 商品上架与浏览** | **8/8 ✅ 全部 review** | 后端 + 后台 + App 三段齐全，含全链路 L1 |
| **2 收货地址与配送范围** | **5/5 ✅ 全部 review** | 含全链路联调 + PII 护栏 |
| **3 完成一次购买** | **8/10** | 3.1~3.5 后端 · 3.6 购物车页 · 3.7 结算页 + 结算/下单端点 · 3.8 支付执行 + 订单详情<br>**下一条 3.9 订单列表电商卡片（App，⚠️ 触碰 OrderCenterService）** |
| 4 履约物流与收货 | 0/6 | |
| 5 退货与退款 | 0/10 | 🔴 最重（资金精度） |
| 6 复购引擎 | 0/7 | 版本核心 |
| 7 商品评价 | 0/4 | |
| 8 经营数据与对账 | 0/4 | |
| 9 边界守护与效果度量 | 0/3 | 横切 |

**测试基线：后端 `mvn -B test` **1790 通过 / 0 失败 / 6 跳过**；前端 `flutter test` **855 通过**、`flutter analyze` 零问题。**

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
`AdminPermissions` 常量 → `GROUPS` 两个 `List.of` → **`perm.<code>` i18n 三份** → `AdminPermissionsTest.listStableSize` 数字（当前 **49**）。
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

## 九 · Epic 3 剩余 2 条的已知要点（下一个窗口直接用）

| Story | 要点 |
|---|---|
| **3.9 订单列表电商卡片** | `OrderType` 末尾追加 `ECOMMERCE`，`OrderCenterService` **if 链末尾追加第 4 分支 + 独立映射方法**，既有三分支**一行不改**（AD-11 / 契约 §3） |
| **3.10 Epic 3 联调与埋点** | 照 `Epic1ChainIntegrationTest` / `Epic2ChainIntegrationTest` 的范式写第三条链路 |

### Story 3.6 落地后新增的三条可复用结论（App 侧）
1. **游客态必须在数据层短路，不能只靠页面不 watch**：任何 `/me/*` provider 被游客 watch 一次
   就会 401 → 拦截器**强登录引导**，等于给浏览路径装了登录墙（FR-93A 明令不要）。
   `CartController.build` 里那行 `if (!auth.isLoggedIn) return CartView.empty;` 是安全语义。
   🔴 守它的用例必须直读 `ProviderContainer`，widget 用例守不住（变异 M5 实测假绿）。
2. **`showSoftSheet` 现有 `allowRepeat` 参数**（默认 false，既有调用点行为不变）：
   用户主动动作触发的登录引导要传 `true`，否则「每 session 一次」的去重会让第二次点击毫无反应。
3. **购物车状态是全局单例 `cartProvider`**，角标（`cartItemCountProvider`）与购物车页同源；
   DEP-1 闭合后 Tab 角标直接 watch 它即可，不需要改本次代码。
4. 🔴 **归因（entry_source / trigger_type）目前一律传 null**：购物车行没有记录商品的进入来源
   （V108 无该列），编一个值会污染 AB-13B 看板且事后无法识别。闭合归 **Story 9.2 / Epic 6**。

### Story 3.7 落地后新增的三条（后面几条 story 会直接吃到）
1. **结算/下单 REST 端点已补齐**：`GET /me/checkout?addressToken=` · `POST /me/shop-orders`
   （3.4 只写了 service，`shop/order/web/` 当时是空目录）。3.8 的订单详情端点要照这个控制器加。
2. **RFC 9457 扩展成员机制已就位**：`shared/error/ProblemExtensions` —— 业务异常实现它即可让
   逐行明细搭上统一错误信封（含 traceId）。**不要再在控制器里自拼 ProblemDetail**。
3. 🔴 **Java 变异验证还原源码后必须 `touch`**：`shutil.move` 会连 mtime 一起还原，Maven 增量编译
   便跳过重编，后续构建跑的仍是变异后的 class —— 表现为一条与改动毫无关系的红，极难排查。

### Story 3.8 落地后新增的四条
1. **Flyway 号段推进：V113 已被 3-8 用掉**（共享 CHECK `ck_payment_intents_purpose` + 订单支付窗）。
   台账已重排：**4-2 取 V114、5-1 取 V115、6-1 取 V116、6-3 取 V117、7-1 取 V118**。
2. 🔴 **又一次触碰共享 CHECK**（purpose 追加 `SHOP_ORDER`）—— 临时授权没点名它，补签须一并认领。
3. **支付链路已通**：`ShopOrderPaymentService`（pay/cancel/懒过期/扫描）· `ShopOrderPaidHandler`
   （同事务 MANDATORY 监听 `PaymentIntentPaidEvent`）· `ShopOrderExpiryScanner`（1min 兜底）。
   Epic 4 的发货只需在状态机上加自己的边，**不要碰这三个类的既有逻辑**。
4. 🔴 **第四次假绿（B1）**：「重复回调不重复扣库存」被意图层的幂等兜住，删掉 `fulfillPaid` 的守卫
   照样全绿。补了一条**绕过意图层直接连调两次**的用例才守住。
   **纵深防御制造假阳性绿灯 —— 这条规律至今 4 次应验，写安全攸关测试时先问「我在守哪一层」。**

### 🔴 Epic 3 已埋下、Epic 4/5 必须处理的两件事
1. **订单状态机只开了「待支付 → 待发货/已取消」两条边**。Epic 4 加发货段、Epic 5 加退款段，**各加各的**。
2. **SPEC-6 的四条缺边**（拒收 / 退款驳回回边 / 退款执行失败 / 用户撤销退货）**须在 Epic 5 前闭合**。
