---
title: "V1.4.0 电商工作线 · 会话交接"
type: handoff
created: 2026-08-17
branch: shawn/oneline-ecommerce
head: ede16e10
---

# V1.4.0 电商工作线 · 会话交接

> **给下一个会话的第一句话：** 规划链条已全通，Epic 1 的前 3 条 story 已实现到 `review`，**L1 已在本地真实跑通（全量 1606 通过 / 0 失败）**。
> **接手时先读三份：** 本文件 → `sprint-status-v1.4.0.yaml`（进度）→ `HEX-SIGNOFF.md`（唯一的人际阻塞）。
> 其余产物都很详尽，**不必通读**，用到哪条 story 读哪条。
>
> 🔴 **本文件 2026-08-17 有两处结论被推翻，别照旧版行事：**
> ① 「本机无 Docker、L1 跑不了」是**误判**，Docker Desktop 一直装着（§三）；
> ② 「Flyway 默认 outOfOrder=false 会拒绝启动」是**错的**，本仓库显式设了 `true`（§四）。

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

### 2026-08-17 晚间变更：DEP-6 解除，Rendy 离职

**Rendy 离职，其业务由产品负责人接手。** 连带三项变化：

| 项 | 变化 |
|---|---|
| **DEP-6**（首批 SKU 清单 / 进货价 / 每日建议喂量） | ✅ **阻塞解除**。喂量数据**印在商品包装背面**，录商品时照抄即可 —— 从「等外部交付」变成「录商品时顺手录」；SKU 清单已有初步版本 |
| **DEP-4**（品牌授权书） | 责任方由 `Rendy / Stella` 改为**产品负责人独任**。⚠️ **单点责任，建议留一个备份人** |
| **C-11 的最大风险** | ✅ **解除**。原判「若 DEP-6 拿不到应回头重议 C-11（用驱虫间隔默认值救回 FR-108）」——**现在不需要了**，FR-107 + FR-109 两条机制均可用 |

🔴 **但两条监控必须保留**：AB-13B 的「FR-109 触发覆盖率」（数据「可得」≠ 运营「真填了」）· Story 1.3 的 Makanan 留空警告（留空从「客观受阻」变成「主观跳过」，警告更有必要）。

---

## 二 · 🔴 唯一的人际阻塞：契约没签

**`HEX-SIGNOFF.md` 已写好但没发出去。** 里面按「撞了会不会被发现」分了三类，真正需要 Hex 事先同意的只有 **2 条**：

1. **Flyway 号段** —— 我占 V101–V139（V101/V102/V103 已提交），请他从 V140 起。
   🔴 **代价随时间线性增长**：现在只有 3 个迁移且只在本地跑过，改起来几乎零成本；等推过 stag 就必须清库（迁移冻结规则下不能改已提交的迁移）。**这条越早发越好。**
2. **两个 CHECK 的独占改动权** —— `ck_payment_intents_channel`（Story 3.3）与 `ck_notifications_type`（Story 6.3）。这是 2026-07-30 事故的同一个剧本。**要到 Story 3.3 才实际执行，还有缓冲。**

**另两位并行同事是谁一直没确认。** 我从 git 只找得到 **Hex**（`hexhexin1226@gmail.com`，08-07 还有提交，独占 `hex/v1.1.x` 分支）。george/george.tu **07-13 后就没提交过**。README §6 写的「另两位同事」第二位在仓库里没有证据。

**已在临时授权下推进**（产品口头拍板），代价写在 `HEX-SIGNOFF.md` 末尾。

---

## 三 · ✅ L1 已跑通（2026-08-17 解除）

> ⚠️ **本节原写「本机既无 Docker 也没装 postgres/redis，L1 一条没跑过」—— 那是误判，已作废。**

`/Applications/Docker.app`（Docker Desktop 4.69）**一直装着**，只是开机不自启，光看 `docker info` 失败就下了「无 Docker」的结论。**不需要 `brew install` 任何东西**：

```
open -a Docker      # 约 60~90s；socket 出现在 ~/.docker/run/docker.sock
docker run -d --name petgo-pg -e POSTGRES_DB=petgo -e POSTGRES_USER=petgo \
  -e POSTGRES_PASSWORD=petgo -e TZ=UTC -e PGTZ=UTC -p 5432:5432 postgres:17-alpine
docker run -d --name petgo-redis -p 6379:6379 redis:7-alpine
```
账密/库名取自 `application.yml` 默认值。首跑自动应用 102 个 Flyway 迁移，建出 62 张表。

**实测结果：**
- 全量 `mvn -B test` → **1606 通过 / 0 失败 / 0 错误 / 6 跳过**（约 32s）
- `mvn spring-boot:run` → `/actuator/health` **UP**（db=PostgreSQL / redis=7.4.10）
- 真实 HTTP 复验：游客 `GET /api/v1/shop/products` → 200；`GET /admin/shop/products` → 302 → `/admin/login`
- 1-1 / 1-2 的 L1 子任务**已全部勾选**；1-3 仍有 3 条未验（需构造登录态后台 actor，见该 story）

⚠️ 起 Docker Desktop 会顺带拉起另外两套无关 stack（Dify 15 个 + logistic 3 个），`logistic-app` 占 8080 —— 跑 `spring-boot:run` 需改端口或先停它。

### 🔴 但防超卖那条测试原本是假绿

`InventoryConcurrencyIntegrationTest` 跑绿了，可**把 `SkuInventoryRepository.lock` 的 `and i.actual - i.locked >= :qty` 整条删掉，它照样全绿**。

原因：`ck_sku_inventory_locked_le_actual` 这个 DB CHECK 兜住了结果，而测试写的是 `catch (Exception e) { soldOut++ }`，把「干净的售罄 409」和「DB CHECK 拒绝 500」记成了同一件事。它证明的是**系统**不超卖，不是**应用层原子条件**成立。

已补判别性断言（分别 catch `AppException` 与其他异常，要求后者为 0），修后再变异 → **正确变红**。守卫已还原。

📌 **沉淀：安全攸关的测试，跑绿之后必须再做一次「删掉被测保护 → 确认变红」。** 纵深防御会制造假阳性绿灯，这类问题跑多少次测试都发现不了，只有变异能发现。

---

## 四 · 本机环境

1. **`java -version` 是 17，但 Maven 跑在 JDK 25 上**，编译产物 `major version: 65`（Java 21 字节码），与 `pom.xml` 的 `<java.version>21</java.version>` 一致。**不需要装 JDK**，我一开始误判过。
2. ~~无 Docker / 无 postgres / 无 redis~~ —— ✅ **已作废，见 §三。** Docker Desktop 一直装着，起两个容器即可跑全量；那 4 个「既有失败类」DB 一起就全绿了，纯属 `Connection refused`。
3. 🔴 **不要用窄过滤器代替全量回归。** 原文推荐的 `mvn -B test -Dtest='Shop*Test,Inventory*Test,AdminShop*Test,FeedingGuide*Test'` **正是 Story 1.3 三个红灯被漏检的原因** —— 跨模块护栏（`AdminPermissionsTest` / `AdminPermissionWiringTest`）不在本模块前缀里，窄跑必漏。改共享文件后**必须** `mvn -B test` 全量。

### 🔴 顺带纠正一条扩散了的错误陈述：`outOfOrder`

`application.yml:171` **显式设了 `out-of-order: true`**（附理由注释：集成分支模型下按「谁测好谁先上」上生产，迁移号无法保证单调应用），`V87` 迁移头也写了「out-of-order 已开」。本地实测 Flyway 打的是 `outOfOrder mode is active` 的 **WARN**，不是拒绝启动。

「Flyway 默认 `outOfOrder=false`，先跑大号再出小号会拒绝启动」这句是**错的**，且已扩散到 4 处：

| 位置 | 处置 |
|---|---|
| `HEX-SIGNOFF.md` A-1 第二条论据 + 一句话版 | ✅ 已删并加更正说明 |
| `sprint-status-v1.4.0.yaml` Flyway 号段头注 | ✅ 已改 |
| IR 报告的 CRITICAL「Flyway 号与 story 顺序逆序」 | ⏳ **未改** —— 该发现建立在同一错误前提上，应作废或重述 |
| `V101` / `V102` / `V103` 三个迁移的文件头注释 | ⏳ **有意不改** —— Flyway 校验和覆盖整个文件内容（注释也算），改了会让已应用该迁移的库 `Validate failed: checksum mismatch`。按迁移冻结规则留着，等有清库机会再一并订正 |

**A-1 的结论不变**：重号（两人各建 `V101__*.sql`）git 不冲突但 Flyway 硬失败「Found more than one migration with version 101」，号段仍必须划分。只是别拿 outOfOrder 当论据 —— Hex grep 一下就能反驳。

号序与 story 顺序一致这件事**也仍然成立**，但真实理由是**表依赖**：V102 的 FK 指向 V101 建的 `shop_skus`，1.1 未合入时 V102 根本应用不了。

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

**🔴 2026-08-17 补充两条，都是「护栏本身出问题」，比业务 bug 更难发现：**

1. **写完护栏要变异验证一次 —— 删掉被保护的东西，确认它变红。**
   `InventoryConcurrencyIntegrationTest` 就是反例：跑绿，但删掉应用层守卫**照样绿**（DB CHECK 兜住了，而 `catch (Exception)` 把两种失败混为一谈）。**纵深防御会制造假阳性绿灯**，这类问题跑多少遍测试都发现不了。
2. **放宽护栏时要防「把牙拔掉」。** 本次让 `AdminPermissionWiringTest` 认识编程式门控，两个地方差一点就废掉整个护栏：
   - 扫 `AdminPermissions.<常量>` 时**必须排除 `AdminPermissions.java` 自身** —— 它声明并在 `GROUPS` 里列出全部常量，一旦计入则任何码都「自证已接线」。
   - **必须用词边界 `\b` 而非 `contains`** —— `CONTENT_VIEW` 是 `CONTENT_VIEW_REPORTS` 的前缀、`VET_QUALIFY` 是 `VET_QUALIFY_VIEW` 的前缀。
   改完同样做了变异（把唯一引用换成裸字符串 → 正确报红）才算数。

---

## 六 · 顺带发现的既有问题（非本工作线引入）

| 发现 | 严重度 |
|---|---|
| **`messages_id.properties` 比 en/zh 少 712 个 key**；`app_id.arb` 比 `app_en.arb` 少 258 行 —— **印尼语系统性落后，而印尼是目标市场** | 建议单开治理 story |
| **`perm.*` 权限标签在 `messages_id.properties` 里整块缺失**（既有 45 码一条未译）—— 上一条的一个具体切面。`AdminPermissionsTest.everyPermissionHasBilingualLabel` **只校验 zh + en，不校验 id**，所以缺得静悄悄。本次只补了电商 4 码 | 并入 i18n 治理 story |
| `CROSS-STORY-DECISIONS` **F8 已过期** —— 它写品牌真相是薄荷绿 `#7FD1AE`，实际 `colors.dart:19` 已是 violet `#845EC9`（常量名仍叫 `mint`）。方法论仍成立，色值需更新 | 低 |
| `AuditActions.java` / `SecurityConfig.java` / `AdminPermissions.java` 等共享文件**缺少 append-only 的书面约定**（本会话已补进契约） | 已处置 |

---

## 七 · 下一步（按优先级）

1. 🔴 **把 `HEX-SIGNOFF.md` 的「一句话版」发给 Hex** —— 只有两条，Flyway 那条越早越好
2. ✅ ~~催 DEP-6~~ **已于 2026-08-17 解除** —— Rendy 离职，产品负责人接手其业务，并确认**每日建议喂量印在商品包装背面**（印尼市售犬粮普遍印有喂食表），录商品时照抄即可；首批 SKU 清单亦已有初步版本。**复购引擎两条机制均可用，无需回头重议 C-11。**
3. ✅ ~~装 postgres+redis 跑 L1~~ **已于 2026-08-17 完成** —— 见 §三。全量 1606 通过 / 0 失败；防超卖已真验并做过变异确认。剩 1-3 的 3 条需登录态后台 actor 的 L1 未验。
4. ▶️ **继续 Story 1.4**（库存管理与采购入库，AB-10C）—— 会再次改 `AdminPermissions`（加 `shop.inventory_*`）与导航
   > 🔴 **本次踩过的坑，1.4 会原样再踩一遍，开工前先看这三条：**
   > - 加权限码时，除了 `AdminPermissions` 常量 + `GROUPS`，还必须同步加 **`perm.<code>` i18n 标签**（zh/en/id 三份）。`ShopSharedFileGuardTest` 查的是 `admin.shop.*` 页面文案，**不查 `perm.*`**，漏了它不报错，只是勾选页显示空白。
   > - `AdminPermissionsTest.listStableSize` 是**故意**的 canary，每加一个码都要有意识地更新数字并续写注释链（现在是 47）。
   > - 若新权限用**编程式**门控（`has(admin, X)` 而非 `@PreAuthorize`），`AdminPermissionWiringTest` 现已能识别（2026-08-17 改）；但仍要确保引用的是 `AdminPermissions.<常量>` 而非裸字符串，否则仍会被判死码。
5. ⏳ **补 1-3 剩余 3 条 L1** —— 需按 `AdminPagesRenderSmokeTest` 范式构造「已登录 + 指定权限组合」的后台 actor。**其中「无 `shop.cost_view` 的账号看不到进货价」目前只验了对外 API 那一半，后台页面那一半（才是真正的断言对象）无任何测试覆盖。**
6. 📋 仍待拍板：**OQ-41**（注销级联，安全攸关，Legal）· OQ-37 · OQ-40 · OQ-42 · 后台 OQ-30~32 · **S-12 找财务复议**
7. 📋 **IR 报告那条 CRITICAL「Flyway 号与 story 顺序逆序会拒绝启动」需作废或重述** —— 前提是错的，见 §四。

---

## 八 · 工作方式备忘

- 用户偏好：**给选项让他选，不要开放式提问**；说「继续」时就是让你往下做，不要停下来反复确认
- 严格按 `bmad-*` skill 流程走（`CLAUDE.md` 强制），但 skill 的默认落点要改：`epics.md` / `sprint-status.yaml` 都是 V1.0 冻结基线，**必须用版本后缀**
- 每条 story 三段推进：**后端 → 前端 → 联调**，一次只碰一侧
- **L1 没跑就不勾**，Status 顶部标 DoD 部分达成 —— 本会话中途犯过一次（批量正则把 L1 也勾了），已改回
