---
title: "V1.4.0 电商工作线 · 会话交接"
type: handoff
updated: 2026-08-17
branch: shawn/oneline-ecommerce
head: 455d1c52
---

# V1.4.0 电商工作线 · 会话交接

> **给下一个会话的第一句话：** 用户目标是**尽快做完全部 57 条 story**。按 Epic 顺序、story 编号升序推进即可，不必等确认。
> **接手只需读两份：** 本文件 → `sprint-status-v1.4.0.yaml`（进度与 Flyway 号段）。
> 其余产物**不必通读**，用到哪条 story 读哪条。

---

## 一 · 进度（18 / 57）

| Epic | 进度 | 说明 |
|---|---|---|
| **1 商品上架与浏览** | **8/8 ✅ 全部 review** | 后端 + 后台 + App 三段齐全，含全链路 L1 |
| **2 收货地址与配送范围** | **5/5 ✅ 全部 review** | 含全链路联调 + PII 护栏 |
| **3 完成一次购买** | **5/10** | 3.1 购物车 · 3.2 订单+状态机 · 3.3 混合支付 · 3.4 结算下单 · 3.5 PawCoin 规则<br>**下一条 3.6 购物车页（App）** |
| 4 履约物流与收货 | 0/6 | |
| 5 退货与退款 | 0/10 | 🔴 最重（资金精度） |
| 6 复购引擎 | 0/7 | 版本核心 |
| 7 商品评价 | 0/4 | |
| 8 经营数据与对账 | 0/4 | |
| 9 边界守护与效果度量 | 0/3 | 横切 |

**测试基线：后端 `mvn -B test` **1760 通过 / 0 失败 / 6 跳过**；前端 `flutter test` **799 通过**、`flutter analyze` 零问题。**

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

## 九 · Epic 3 剩余 5 条的已知要点（下一个窗口直接用）

| Story | 要点 |
|---|---|
| **3.6 购物车页（App）** | 消费 `/me/cart`。🔴 **失效商品单独成组、不参与合计、不可勾选**；角标用 `itemCount`（**件数非种类数**）；游客无车 → 401 |
| **3.7 结算页与两段金额** | 调 `CheckoutService.preview`（**别另算一遍运费与拆分，两处必漂移**）。🔴 **FR-104 三处明示的第 2 处**（措辞须与详情页逐字一致） |
| **3.8 支付与待支付订单详情** | 调 `CheckoutService.settlePawCoinSegment`（已实现，幂等键 `shop-order:{token}`）。60 分钟超时释放库存挂在同一状态迁移事务内（AD-8） |
| **3.9 订单列表电商卡片** | `OrderType` 末尾追加 `ECOMMERCE`，`OrderCenterService` **if 链末尾追加第 4 分支 + 独立映射方法**，既有三分支**一行不改**（AD-11 / 契约 §3） |
| **3.10 Epic 3 联调与埋点** | 照 `Epic1ChainIntegrationTest` / `Epic2ChainIntegrationTest` 的范式写第三条链路 |

### 🔴 Epic 3 已埋下、Epic 4/5 必须处理的两件事
1. **订单状态机只开了「待支付 → 待发货/已取消」两条边**。Epic 4 加发货段、Epic 5 加退款段，**各加各的**。
2. **SPEC-6 的四条缺边**（拒收 / 退款驳回回边 / 退款执行失败 / 用户撤销退货）**须在 Epic 5 前闭合**。
