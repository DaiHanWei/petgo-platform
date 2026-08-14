# V1.4.0 规划输入 · 精选自营电商（已接受，非候选）

> 本目录是 **V1.4.0 电商板块的权威输入**，已随仓库 commit，云端 session clone 后可直接读取。
> 与 `incoming-prd-v1.0.0-20260606/`（待评估的变更候选）性质不同——**本目录内容可直接作为下游 UX / 架构 / epics / story 的依据。**
>
> ⚠️ 但 §4 列出的 **5 处「PRD 假设 vs 当前代码」偏差必须先闭合**，其中第 ① 条不闭合则 FR-100A / FR-105 / AB-12C / AB-13D 全部无法拆 story。

## 1. 文件清单

| 文件 | 说明 | 源文件 |
|---|---|---|
| `PRD-v1.4.0.md` | 用户端 PRD，**FR-93 ~ FR-110**（含 FR-93A / FR-94A / FR-100A 六条 PawCoin 规则 + 规则 7 溢价补偿）+ §4 三张状态机 + §5 埋点 13 事件 | `v1-4-0电商PRD.md` |
| `PRD-v1.4.0-后台.md` | 运营后台 PRD，**新建模块 10–13**（商品与库存 / 订单履约 / 退换货售后 / 电商经营数据）+ **模块 6 扩展**（AB-6D 新增、AB-6A 第二条溢价、AB-6C 口径变更） | `v1-4-0电商后台prd.md` |
| `decision-log.md` | 决策记录：已确认 C-1~C-9 · 待拍板 D-3~D-8 · 与既有文档的矛盾 L-1~L-7 · 业务前提 N-1~N-3 · 依赖 DEP-1~DEP-8 | `.decision-log.md`（去前导点，使其可见并纳入 git） |
| `Roadmap-电商改动建议.md` | Roadmap 8 处定点改写建议（**第 3 条最紧急**：带时限的商务动作方向错误） | 同名 |
| `页面/` | UI 规格预览：`00-整合总览.html` + `pages/` 下 **17 屏** + `shared.css` | `页面/v1.4.0-拆分预览/` |

**17 屏覆盖：** Toko 首页（游客态 / 有复购触发）· 商品详情（可购买 / 售罄）· 购物车含失效商品 · 结算页（正常 / 超服务范围）· 收货地址新增 · 订单列表电商卡片 · 订单详情（待支付 / 已发货）· 退货申请 · 退款方式混合支付拆分 · Diary 复购触发卡 · 档案推荐区。

> **关于预览页的 design token（落库时核实）：** 各页 `<link>` 了 `1.项目总述/UI&UX/imports/tokens.css`，但该文件**在源工作区中即不存在**（非本次复制导致）。`shared.css` 自带完整 fallback `:root` 块，注释明确其用途是「脱离仓库单独打开时仍能正确渲染」——**故 17 屏可直接打开，渲染不受影响**，断链无需修复。
>
> 但 fallback 是**冻结快照**，UX 步骤需与 App 实际色板交叉核对。已抽查一致：`shared.css` 的 `--violet-500:#845EC9` 与 `bottom_tab_bar.dart` 的 `_kViolet = '#845EC9'`（注释标 `AppColors.accentGrowth`）**逐字相同** ✅。

## 2. 来源与基准

- **源工作区**：`2026 BU/PetApp/TailTopia #2/PetGo-电商板块/V1.4.0/`（产品文档工作区，非本 monorepo）
- **PRD 自述文档基准**：`PetGo @ docs/v1.1.2-split`（commit `1dc9bda`）
- **⚠️ 引用体系不同源**：PRD 内引用的 `V1.1.0/v1-1-0PRD.md`、`1.项目总述/Roadmap.md`、`3.数据埋点/埋点文件/埋点清单v112.md` 等均在源工作区，**不在本仓库**。本仓库对应物为 `planning-artifacts/` 下的 `PRD.md` / `epics-v1.1.md` / `architecture-*-delta.md` 等。跨引用时需人工对照，勿直接按 PRD 里的路径找文件。

### 覆盖关系登记（沿用仓库惯例：增量并存，不改基线）

| 被覆盖对象 | 位置 | 覆盖内容 |
|---|---|---|
| 基线 PRD 的电商排除条款 | `../PRD.md:54` / `:1336` / `:1398` | 三处均写明"不做电商 / 自营电商排除"。本版本由 C-1 拍板转为**精选自营**，直接覆盖。**基线 `PRD.md` 不改写**（v1.1 / v1.1.2 均为此先例），以本条登记为准 |
| Roadmap 电商两阶段路线 | 源工作区 `1.项目总述/Roadmap.md` 1.4.0 / 2.1.0 | Phase A（导流跳转 Tokopedia/Shopee）与 Phase B（商家自行履约）**两条作废**（C-2）。改写建议见 `Roadmap-电商改动建议.md`，由 Dai 合入源工作区 |

---

## 3. 本版本的工程性质（排期前必读）

`decision-log.md` L-5 已指出：既有全部收费场景（AI 问诊解锁 / 兽医咨询 / PawCoin 充值 / 身份证高清）**均为虚拟商品**，系统中完全不存在 **收货地址 / 物流 / 实物退货 / 库存** 四类能力——这四块是本版本的工程主要增量。

其余为**复用既有基座**（落库时已核实存在）：

| 复用点 | 仓库位置 | 接入方式 |
|---|---|---|
| 支付意图 + 幂等 + 回调 | `pay/domain/PaymentIntent.java`、`pay/service/PaymentIntentService.java`、`pay/web/PayCallbackController.java` | 加 `PaymentPurpose` 枚举值（⚠️ 但见 §4 ①） |
| 双分录总账 | `pay/service/LedgerService.java`、`pay/domain/LedgerEntry.java` | 电商资金流水走同一总账 |
| PawCoin 钱包 | `pay/service/PawCoinWalletService.java`、`PawCoinTopupService.java`、`PawCoinTxnType{TOPUP,SPEND,REFUND,BONUS}` | `BONUS` 类型可承接 FR-100A 规则 7 的补偿溢价 |
| 订单中心（聚合层） | `order/service/OrderCenterService.java`、`order/dto/OrderType.java` | 加 `OrderType` 值 + 一组 mapper，即 FR-101 的第 5 类卡片 |
| 退款两段审批 | `pay/refund/service/RefundService.java`、`refund/domain/ApprovalStatus.java` | 承接 FR-104 / AB-12A |
| 推送 + 深链 | `notify/domain/NotificationType.java`（现 18 值） | 发货 / 超时提醒 / 复购触发加枚举值 |
| 媒体直传 | Story 2-1 的 OSS 双桶 + STS + EXIF 剥离 | 商品图 / 退货凭证图 / 评价图 |
| 结构化健康记录 | `profile/domain/HealthRecordType{VACCINE,DEWORM,MENSTRUATION,NEUTER,CUSTOM}` | FR-108 的驱虫 / 疫苗类型假设**成立**（⚠️ 但见 §4 ③） |
| App 端已有 feature | `petgo_app/lib/features/{order,pawcoin,refund}/` | 订单 / 钱包 / 退款 UI |

**排期时应按此区分，不要把整个电商板块都当作新建。**

---

## 4. ⚠️ 落库时核实：5 处「PRD 假设 vs 当前代码」偏差

以下为 2026-08-14 落库时逐条核对源码得出，**PRD 与 decision-log 均未记录**。按阻塞程度排序。

### ① 混合支付与现有支付模型冲突 —— **最高，阻塞整条退款/对账链**

**代码事实：** `pay/domain/PaymentIntent.java` 是**严格单渠道单金额**模型——`channel` 为单值枚举、`amount` 为单个数值，且**两者均 `updatable = false`**；`PayChannel` 仅 `{QRIS, PAWCOIN}` 二选一，无混合表达能力。

**PRD 要求：** FR-100A 规则 2 要求一笔订单同时含 PawCoin 段与 QRIS 段，且**支付比例须在订单创建时固化**，供后续多次部分退款按固定比例拆分。

**影响面：** FR-100A 全部 7 条规则 · FR-105 退款到账方式 · 后台 AB-12C 退款执行拆分 · AB-13D 对账「PawCoin 与 QRIS 两段须拆分」与「被 PawCoin 抵扣的运费须单独可拆」。

**待架构 delta 拍板（二选一）：**
- (a) 扩展 `PaymentIntent`：加 coin/cash 两段金额列 + 比例固化列（改动共享基座，影响另两位同事）
- (b) 电商订单侧自建支付拆分表，对同一订单起两笔 intent（隔离性好，但订单-支付一对多，对账链路变长）

> **此条不闭合，FR-100A / FR-105 / AB-12C / AB-13D 相关 story 无法开写。**

### ② 宠物档案无体重字段 —— **高，复购引擎三缺一**

**代码事实：** `profile/domain/PetProfile.java` 字段全集为 `ownerId / avatarUrl / petType / name / systemDefaultName / breed / birthday / intro / cardToken / ogImageUrl / serialId` + 时间戳。**无体重，无绝育状态。**

**PRD 要求：**
- FR-107 档案推荐步骤 3「按适用体型匹配档案体重」——**有降级路径**（缺体重→按物种推荐），可活
- FR-109 粮量见底预估「日喂量按档案体重在区间表中匹配」——PRD 明写「缺少任一输入时不触发，**不做兜底猜测**」

**后果：** 体重字段不加，**FR-109 恒 100% 不触发**。而复购引擎（FR-107/108/109 三条机制）是 PRD §1 认定的"版本存在的意义"，`decision-log.md` N-1 更以财务计划的月交易率缺口为其论证依据。

**需排入 story：** `pet_profiles` 加体重列 + 建档/编辑表单加字段 + 存量用户回填引导（FR-107 的「补全档案，推荐更准」引导卡正是入口，但字段须先有）。绝育状态同理，若 FR-107 需按其过滤则一并加。

### ③ FR-108 的到期判定无数据源 —— **高，属跨版本依赖**

**代码事实：** `HealthRecordType` 已含 `VACCINE` / `DEWORM`，PRD「仅驱虫疫苗参与触发」的类型假设**成立** ✅。但 `profile/domain/HealthRecord.java` 仅有 `eventDate`，**无 next_due_date、无 interval**。

**PRD 要求：** FR-108 数据源写为「FR-45A 结构化健康记录 **+ 1.2.0 健康提醒的间隔配置**」——**后半截在当前代码中不存在**（健康提醒排在 Roadmap 1.2.0）。

**需产品拍板（二选一）：** (a) FR-108 依赖 1.2.0 先落地，本版本不做；(b) 本版本自带一套周期默认值（犬猫驱虫/疫苗常规间隔）。**属范围问题，不可由实现者临场决定。**

### ④ FR-93 的多宠物前提不成立 —— 低，不阻塞

**代码事实：** `profile/service/ProfileService.java:56` 硬约束 `existsByOwnerId` → 409，注释明写「单账号单宠物，FR-11」。多宠物排在 Roadmap 1.3.0。

**PRD 表述：** FR-93「多宠物用户区域 ①② 按当前选中宠物渲染，**复用多宠物管理已有的全局当前宠物概念**」——该概念不存在。

**处置：** 不阻塞（单宠物下自然退化为唯一宠物）。但"复用已有"的措辞会让实现者去找一个找不到的东西，**epics 阶段须标注「1.3.0 后启用」**。

### ⑤ 底部 Tab 没有预留位 —— 中，归 DEP-1

**代码事实：** `petgo_app/lib/shared/widgets/bottom_tab_bar.dart:18` 的 `AppTab` 枚举为 **4 值**——`profile('/profile','diary')` / `triage('/triage','health')` / `home('/home','social')` / `me('/me','me')`，加中间 `[+]` 发布按钮。**无空位。**

**PRD 表述冲突：** §3.1 FR-93 写「占用底部 Tab Bar 中**已预留的位置**」，但 §5 埋点章节写「电商 Tab **会替换掉一个既有 Tab**」并据此提出基线埋点要求——**PRD 内部两处措辞不一致**，实际只能是"替换其一"或"扩为 5 Tab（挤压 [+]）"。

**处置：** 归 **DEP-1**（Tab 改版负责人），PRD 已标「高，阻塞 FR-93/93A 定稿」。**因归属他人且阻塞度高，建议现在即启动对齐，不要等到写 story 时。**

---

## 5. 开工前须闭合的开放项

### 待产品/财务/法务拍板（`decision-log.md` §二）

| # | 事项 | 设计侧建议 | 阻塞 |
|---|---|---|---|
| **D-7** / OQ-33 | PawCoin 单笔支付上限 | Rp 1.000.000。⚠️ 用途是**故障/欺诈爆炸半径 + DEP-7 监管姿态**，**不是控浮存**（见 L-7 自纠）；应设在几乎不触发的水平，**定低反而有害** | AB-6D 配置默认值 |
| **D-8** / OQ-34 | 平台责任补偿溢价比例与上限 | 起步 5%、单笔上限 Rp 50.000。**必须与 AB-6A 既有激励溢价分开取值** | AB-6A 扩展、AB-13A 毛利扣减口径 |
| D-3 / OQ-27 | 自动确认 7 日 + 退货窗口 7 日是否过长 | 保持 | FR-102 状态机 |
| D-4 / OQ-28 | 「开封」判定标准与执行人 | CS 依凭证图判定，AB-12D 沉淀判例库 | FR-104 / AB-12D |
| D-5 / OQ-29 | 复购触发卡与健康提醒是否合并为一条推送 | 合并 | FR-108 推送模板 |
| D-6 | 商品评价是否需平台回复 | 首版不做 | FR-106 |

### 外部依赖（`decision-log.md` §五）

| # | 依赖 | 责任方 | 阻塞 |
|---|---|---|---|
| **DEP-1** | Toko Tab 位序 / 被替换模块迁移 / FR-78 落地规则是否调整 | Tab 改版负责人 | **FR-93/93A 定稿**（见 §4 ⑤） |
| **DEP-3** | NIB / KBLI 是否覆盖网络零售；PPN 登记与开票 | Joko / Lantip | **上线** |
| **DEP-4** | 品牌授权书；从本地授权经销商采购以规避农业部许可与 Karantina | Rendy / Stella | **备货** |
| **DEP-7** | PawCoin 扩展至实物的合规确认（须维持不可提现 / 不可转让 / 仅限自营商品三条闭环特征） | Joko / Lantip | **PawCoin 购物上线**（不阻塞电商本身） |
| DEP-2 | Toko Tab 图标常态/激活态（含 FR-78A 萌化规范） | 设计 | UI 交付 |
| DEP-5 | 承运商企业账号与费率 | 运营 | FR-99 运费表 |
| DEP-6 | 首批 SKU 清单、进货价、**每日建议喂量数据** | Rendy | 商品录入；⚠️ 每日建议喂量是 FR-109 的唯一计算依据 |
| DEP-8 | PawCoin 支付实物的税务与收入确认口径 | Joko | AB-13D 对账口径 |

### 埋点基线（L-6，有前车之鉴）

`埋点清单v112.md` §0.3 记录：V1.1.2 改版的埋点与改版**同版本发布**，导致三项核心指标不可得。**电商 Tab 同样是替换既有 Tab，风险完全相同。**

→ `toko_tab_viewed` 与**被替换 Tab 的曝光事件必须在电商上线的前一个版本先行发布**，积累 ≥2 周基线。做不到则需在评审时明确承认「本次 Tab 替换的得失不可评估」。

---

## 6. ⚠️ 三人并行护栏（本版本独有，须落进架构 delta）

**背景：** 本版本与另两位同事的模块并行开发，电商在 `shawn/oneline-ecommerce` 分支。以下冲突面按风险排序，须在 `architecture-v1.4.0-delta.md` 中落成 AD 条目。

| # | 冲突面 | 现状 | 护栏 |
|---|---|---|---|
| 1 | **Flyway 序号** | 已占到 **V100**（`V100__add_id_card_student_fields.sql`） | 给电商划**独占号段**，三人各占一段并登记在册。CLAUDE.md 的"按执行顺序单调分配"是**单人串行前提**，并行下必须升级为号段隔离。`sprint-status-v1.1.yaml` 头部记录了 2026-07-11 因跨分支撞号被迫全量重排的先例 |
| 2 | **三个共享枚举** | `pay/domain/PaymentPurpose.java`（4 值）· `order/dto/OrderType.java`（4 值）· `notify/domain/NotificationType.java`（18 值） | **只在末尾追加，不重排 / 不删除 / 不改既有值拼写**。三者均落库为 varchar + CHECK，重排不报错但会静默改语义 |
| 3 | **`OrderCenterService.java`** | 275 行，唯一 fan-in 聚合器。加一个订单类型要改 **3 处**：filter 分支链（:77/:83/:89）、detail mapper（:158/:171/:184）、summary mapper（:209/:235/:243） | 新增类型**在既有 if 链末尾追加分支 + 新增独立 private 方法**，不重构既有分支，使 diff 为纯追加 |
| 4 | **App 端共享壳** | `app_router.dart` · `bottom_tab_bar.dart`（v1.1.2 Epic 1 刚重排完）· l10n ARB | ARB **只追加 key**；路由只追加。Tab 顺序改动归 DEP-1 统一处置，**电商侧不得自行调整** |
| 5 | **分支协作** | `origin` 上已有大量并行 `feat/*` 分支先例 | **定期 merge main**，勿攒到最后一次性合 |

> 建议将本节落为 `implementation-artifacts/v1.4.0/PARALLEL-DEV-CONTRACT.md`，或并入既有 `implementation-artifacts/CROSS-STORY-DECISIONS.md`（CLAUDE.md 指定"遇冲突以此为准"）。**该契约须三人共同确认，不是单方面写。**

---

## 7. 数据库迁移编号

仓库现有 Flyway 最高到 **V100**。V1.4.0 新增迁移按 §6 护栏 1 划定的**独占号段**分配，**不是简单从 V101 顺延**——号段边界待三人对齐后写死进架构 delta（决策 E2 的并行版本）。

## 8. 下游产物命名（沿用 V1.1 / V1.1.2 的 delta 模式，并存不覆盖）

- `planning-artifacts/architecture-v1.4.0-delta.md`
- `planning-artifacts/epics-v1.4.0.md`
- `implementation-artifacts/sprint-status-v1.4.0.yaml`
- story 文件放 `implementation-artifacts/v1.4.0/`（避免与前三版的 `1-1` / `2-1` 编号撞名）

## 9. 版本上下文

- **V1.0.0**：46 story + Epic 8 里程碑 6 story，全部 `review`（`sprint-status.yaml`）
- **V1.1.0**：9 Epic / 46 story，3 `done` + 42 `review`；Epic 8 已取消（`sprint-status-v1.1.yaml`）
- **V1.1.2**：7 Epic / 19 story，14 `review` + 5 `in-progress`（6-1 埋点收尾、Epic 7 Splash 四条），卡在 L2 真机与 PostHog 后台实测（`sprint-status-v1.1.2.yaml`）
- **V1.4.0**：本目录，**规划中**。跨 App 端 + 服务端 + 运营后台三侧，与另两位同事的模块并行开发
- 测试基线（供回归对照）：前端 **701**、后端 **1515**

## 10. 下一步

1. **`bmad-prd`（validate 意图）** —— 对两份 PRD 跑校验，产出 findings report。**§4 的 5 处偏差应在此被正式承接为编号项**，而非停留在本 README。具体操作见 §11
2. `bmad-ux`（**CU**）—— 17 屏已覆盖主链路，本步重点是补异常态/空态并对齐 v1.1.2 设计 token
3. `bmad-architecture`（**CA**）—— 先定 §4 ① 混合支付模型，再定 §6 并行护栏
4. `bmad-create-epics-and-stories`（**CE**）→ `bmad-check-implementation-readiness`（**IR**）→ `bmad-sprint-planning`（**SP**）

**并行启动（不等上述链条）：** DEP-1 与 Tab 改版负责人对齐；DEP-3 / DEP-4 / DEP-7 的法务与商务前置。

---

## 11. 第 1 步的具体操作：跑 `bmad-prd` validate

### 先决

**开新 context**（`/clear` 或新开终端 session），然后 `/bmad-prd`。

`bmad-prd` 激活时**自己判断意图**（Create / Update / Validate），判不出来会反问——所以开场必须把 validate 说死，否则容易被带进 Create 的 Discovery 引导流程。

### 开场话术（直接粘贴）

```
validate 意图，不要 create 也不要 update。

验证对象（两份一起验，它们互相引用，跨文档一致性是重点）：
- _bmad-output/planning-artifacts/v1.4.0/PRD-v1.4.0.md（用户端，FR-93~110）
- _bmad-output/planning-artifacts/v1.4.0/PRD-v1.4.0-后台.md（后台，模块10-13 + 模块6扩展）

配套输入：
- _bmad-output/planning-artifacts/v1.4.0/README.md（§4 已列出 5 处「PRD 假设 vs 当前代码」偏差，
  请承接为正式编号项，不要重新发现）
- _bmad-output/planning-artifacts/v1.4.0/decision-log.md（C-1~C-9 已确认 / D-3~D-8 待拍板 / DEP-1~8）

除默认 rubric walker 外，额外加两个评审员：
1. adversarial-general
2. 临时评审员：跨文档一致性 —— 用户端 FR 与后台 AB 的引用是否双向对齐、
   有无一方定义了另一方未实现的配置项

报告用中文。
```

### 三个必须在话术里交代的点（及原因）

| # | 交代什么 | 为什么不交代会出问题 |
|---|---|---|
| 1 | **两份一起验，不拆** | 用户端与后台互相引用（FR-100A ↔ AB-6D / AB-6A 两类溢价 · FR-105 ↔ AB-12C 退款拆分 · FR-95 ↔ AB-10C 库存流转）。**跨文档一致性恰恰是最该验的部分**——如"两条溢价必须分开配置"这类约束横跨两份文档，拆开验必然漏掉 |
| 2 | **额外加评审员** | `.claude/skills/bmad-prd/customize.toml` 的 `finalize_reviewers = []` **是空的**，默认只跑一个 rubric walker。不主动要，就只有一份视角 |
| 3 | **§4 五处偏差作为输入喂进去** | rubric 验的是 PRD **自身**质量（完整性 / 可测性 / 内部一致性），**不读代码库**。那五条要读 `PaymentIntent.java`、`PetProfile.java`、`HealthRecord.java`、`ProfileService.java`、`bottom_tab_bar.dart` 才成立，rubric 不会重新发现 |

### 产出落点（⚠️ 不在本目录）

`customize.toml` 的 `prd_output_path = "{planning_artifacts}/prds"` + `run_folder_pattern = "prd-{project_name}-{date}"`，所以报告落在：

```
_bmad-output/planning-artifacts/prds/prd-petgo-platform-<日期>/
├── review-rubric.md          # rubric walker 原始评审
├── review-<slug>.md          # 每个额外评审员一份
├── validation-report.html    # 合成报告，跑完自动用浏览器打开
└── validation-report.md      # markdown 孪生版，按严重度分组（下游复读用这份）
```

### 跑完之后

它会按严重度逐条问处置方式（autofix / 讨论 / 挪进 open items / 忽略）。

**那一步产生的决策必须回写**进 `decision-log.md` 的 D-n 或 `PRD-v1.4.0.md` §9 的 OQ 表——**报告是一次性产物，不是台账**。§4 的五处偏差同理：validate 跑完后它们应当已在 PRD/decision-log 里有编号，本 README §4 退化为历史记录。
