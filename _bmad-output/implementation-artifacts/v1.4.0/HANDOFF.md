---
title: "V1.4.0 电商工作线 · 会话交接"
type: handoff
created: 2026-08-17
branch: shawn/oneline-ecommerce
head: ede16e10
---

# V1.4.0 电商工作线 · 会话交接

> **给下一个会话的第一句话：** 规划链条已全通，Epic 1 的前 3 条 story 已实现到 `review`。
> **接手时先读三份：** 本文件 → `sprint-status-v1.4.0.yaml`（进度）→ `HEX-SIGNOFF.md`（唯一的人际阻塞）。
> 其余产物都很详尽，**不必通读**，用到哪条 story 读哪条。

---

## 一 · 现在在哪

### 规划链条（全部完成并提交）

```
PRD ✅ → 并行契约 ✅ → 架构 delta ✅ → UX ✅ → Epic/Story ✅ → IR ✅ → Sprint 台账 ✅
```

| 产物 | 路径 | 规模 |
|---|---|---|
| 用户端 PRD | `planning-artifacts/v1.4.0/PRD-v1.4.0.md` | FR-93~110（FR-108 已出局） |
| 后台 PRD | `planning-artifacts/v1.4.0/PRD-v1.4.0-后台.md` | AB-10A~13D + 模块 6 扩展 |
| 架构 delta | `planning-artifacts/architecture-v1.4.0-delta.md` | AD-1~AD-13 |
| UX 交接 | `planning-artifacts/v1.4.0/UX-v1.4.0.md` + `v1.4.0/页面/` | 15 屏原型，可用 14 屏 |
| 决策台账 | `planning-artifacts/v1.4.0/decision-log.md` | C-1~C-17 · **S-1~S-15** · L-1~L-13 · DEP-1~DEP-9 |
| Epic/Story | `planning-artifacts/epics-v1.4.0.md` | 9 Epic / 57 Story |
| IR 报告 | `planning-artifacts/implementation-readiness-report-2026-08-17-v1.4.0.md` | 8 发现，6 已修 |
| Sprint 台账 | `implementation-artifacts/sprint-status-v1.4.0.yaml` | 头注承载排期硬约束 |
| 并行契约 | `implementation-artifacts/v1.4.0/PARALLEL-DEV-CONTRACT.md` | **待签字** |
| Hex 签字清单 | `implementation-artifacts/v1.4.0/HEX-SIGNOFF.md` | **待发出** |

### 已实现的代码（Epic 1，全部 `review`）

| Story | 交付 | Flyway | 状态 |
|---|---|---|---|
| **1.1** 商品与 SKU 建模及只读查询 | `shop/` 模块 + 2 表 + 只读接口 | V101 | review |
| **1.2** 库存模型与可售库存口径 | 4 条原子原语 + 三态 | V102 | review |
| **1.3** 商品录入与 SKU 管理（后台） | 模块 10 + 权限 + 模板 + i18n | V103 | review |

**48 个 L0 单测全绿。** 剩余 5 条（1.4~1.8）为 `backlog`。

---

## 二 · 🔴 唯一的人际阻塞：契约没签

**`HEX-SIGNOFF.md` 已写好但没发出去。** 里面按「撞了会不会被发现」分了三类，真正需要 Hex 事先同意的只有 **2 条**：

1. **Flyway 号段** —— 我占 V101–V139（V101/V102/V103 已提交），请他从 V140 起。
   🔴 **代价随时间线性增长**：现在只有 3 个迁移且只在本地跑过，改起来几乎零成本；等推过 stag 就必须清库（迁移冻结规则下不能改已提交的迁移）。**这条越早发越好。**
2. **两个 CHECK 的独占改动权** —— `ck_payment_intents_channel`（Story 3.3）与 `ck_notifications_type`（Story 6.3）。这是 2026-07-30 事故的同一个剧本。**要到 Story 3.3 才实际执行，还有缓冲。**

**另两位并行同事是谁一直没确认。** 我从 git 只找得到 **Hex**（`hexhexin1226@gmail.com`，08-07 还有提交，独占 `hex/v1.1.x` 分支）。george/george.tu **07-13 后就没提交过**。README §6 写的「另两位同事」第二位在仓库里没有证据。

**已在临时授权下推进**（产品口头拍板），代价写在 `HEX-SIGNOFF.md` 末尾。

---

## 三 · 🔴 L1 一条都没跑过

**本机既无 Docker 也没装 postgres/redis**，`ApiIntegrationTest` 需要真实 DB（仓库未用 Testcontainers）。

累计 **17 个 L1 用例已写完并编译通过，但一条未执行**。三条 story 的 L1 子任务全部**保持未勾选**（没有谎报完成）。

**其中最要紧的一条：** `InventoryConcurrencyIntegrationTest` 的「50 线程抢 10 件，成功恰 10」—— **这是唯一能证明防超卖成立的测试**。L0 只能证明「影响 0 行时抛错」，**证明不了并发下不会有两个线程同时通过条件**。Story 1.2 顶部已写死：**未跑 L1 前不得认为防超卖已验证。**

跑起来的命令（需用户自己执行，涉及系统安装）：
```
brew install postgresql@17 redis && brew services start postgresql@17 && brew services start redis
```

---

## 四 · 本机环境的三个坑

1. **`java -version` 是 17，但 Maven 跑在 JDK 25 上**，编译产物 `major version: 65`（Java 21 字节码），与 `pom.xml` 的 `<java.version>21</java.version>` 一致。**不需要装 JDK**，我一开始误判过。
2. **无 Docker / 无 postgres / 无 redis** —— 只能跑 L0。`mvn -B test` 全量会失败 4 个既有类（`TailtopiaBackendApplicationTests` / `AdminPagesRenderSmokeTest` / `TimelinePaginationRegressionTest` / `SmokeApiTest`），全是 `Connection to localhost:5432 refused`。**已用 `git stash` 验证过是既有问题，不是本工作线的回归。**
3. **跑 L0 要指定测试类**：`mvn -B test -Dtest='Shop*Test,Inventory*Test,AdminShop*Test,FeedingGuide*Test'`

---

## 五 · 本会话做的关键判断（不看这里会重复踩）

### 5.1 并行契约的共享物清单原本是错的

原清单（Flyway / 共享枚举 / `OrderCenterService` / App 壳）是**按 PRD 提到的东西列的，不是按代码实际共享的东西列的**。开工两条 story 各撞一个：1.1 撞 `SecurityConfig`，1.3 撞 `AdminPermissions`。

已做系统性排查并补入契约 **§二之二**（7 个文件），并加了**比清单更重要的通用判据**：只要「另两条线也可能在同一周期改它」就是共享物，**上表是已知的，不是穷尽的**。

### 5.2 S-1~S-15：15 条规格决议由设计侧代拍

产品授权「按推荐项定、不逐条问」，落在 `decision-log.md` **§一之二**，**每条都可逆**。其中：
- 🔴 **S-12 最值得财务复议** —— 不做钱包侧余额批次分层、只给近似值。若不够用需回头改钱包（代价最高）
- **S-15 是 IR 检查抓回来的** —— FR-100A 规则 6 的措辞把 PPN 排除在禁止之外（等于允许用无现金垫底的赠币缴税），且该规则在 57 条 story 里零落点

### 5.3 IR 抓到 4 个 CRITICAL，全是「不查就一定在实现时炸」

- Flyway 号与 story 顺序逆序（`outOfOrder=false` 会拒绝启动）
- `shipments` 表无任何 story 创建，而 S-2 一单多包全建立在它上面
- **Epic 6 依赖 Epic 9** —— AB-13B 的转化率要等 Story 9.2，而它是裁决 A-16 的唯一依据。已把归因前移到 Story 3.4
- FR-100A 规则 6 零落点 + 措辞缺陷

### 5.4 两处有意识的偏离，需要产品确认

| # | 偏离 | 理由 |
|---|---|---|
| 1 | **Makanan 喂量不强制必填**（FR-94 说必填） | DEP-6 未交付，强制必填会**卡死商品录入**，而商品录不进去整个 Epic 1 白做。改为留空给显著警告并允许保存 |
| 2 | **Story 3.2 提前创建 Epic 5 才用的三列** | 同一张表、可空、恒为初值，随建表写完远比 Epic 5 再起 `ALTER` 便宜 |

### 5.5 测试写法上的一条心得（建议延续）

**对「写错了不会报错」的东西，写源码级护栏测试。** 本会话用了三处：
- `InventoryServiceTest.lockNeverPreReads` —— Mockito 断言 `lock()` 不调用任何查询方法（「先查后改」单线程 100% 正确、并发下静默超卖）
- 扫源码断言 SQL 里必须含 `i.actual - i.locked >= :qty`（条件被删就是无条件加锁量 = 超卖，不引起任何编译或行为报错）
- `ShopSharedFileGuardTest` 6 条 —— i18n 三份齐全、模板引用的 key 都存在、既有权限码未丢失

⚠️ 写这类护栏时注意**剥掉注释再扫**，否则会被自己的 javadoc 绊倒（我踩过一次）。

---

## 六 · 顺带发现的既有问题（非本工作线引入）

| 发现 | 严重度 |
|---|---|
| **`messages_id.properties` 比 en/zh 少 712 个 key**；`app_id.arb` 比 `app_en.arb` 少 258 行 —— **印尼语系统性落后，而印尼是目标市场** | 建议单开治理 story |
| `CROSS-STORY-DECISIONS` **F8 已过期** —— 它写品牌真相是薄荷绿 `#7FD1AE`，实际 `colors.dart:19` 已是 violet `#845EC9`（常量名仍叫 `mint`）。方法论仍成立，色值需更新 | 低 |
| `AuditActions.java` / `SecurityConfig.java` / `AdminPermissions.java` 等共享文件**缺少 append-only 的书面约定**（本会话已补进契约） | 已处置 |

---

## 七 · 下一步（按优先级）

1. 🔴 **把 `HEX-SIGNOFF.md` 的「一句话版」发给 Hex** —— 只有两条，Flyway 那条越早越好
2. 🔴 **催 DEP-6**（每日建议喂量数据，Rendy）—— FR-108 出局后复购引擎冗余归零，缺它 FR-109 恒不触发，本版本实际只剩 FR-107
3. ⏳ **装 postgres+redis 跑 L1** —— 17 个用例待验，其中防超卖那条是硬需求
4. ▶️ **继续 Story 1.4**（库存管理与采购入库，AB-10C）—— 会再次改 `AdminPermissions`（加 `shop.inventory_*`）与导航
5. 📋 仍待拍板：**OQ-41**（注销级联，安全攸关，Legal）· OQ-37 · OQ-40 · OQ-42 · 后台 OQ-30~32 · **S-12 找财务复议**

---

## 八 · 工作方式备忘

- 用户偏好：**给选项让他选，不要开放式提问**；说「继续」时就是让你往下做，不要停下来反复确认
- 严格按 `bmad-*` skill 流程走（`CLAUDE.md` 强制），但 skill 的默认落点要改：`epics.md` / `sprint-status.yaml` 都是 V1.0 冻结基线，**必须用版本后缀**
- 每条 story 三段推进：**后端 → 前端 → 联调**，一次只碰一侧
- **L1 没跑就不勾**，Status 顶部标 DoD 部分达成 —— 本会话中途犯过一次（批量正则把 L1 也勾了），已改回
