---
baseline_commit: 6bd8c3f264a3c74c66eeda71fc658324b2287b26
epic: 1
story: 1.1
flyway: V101
touches_shared_files: true   # shared/security/SecurityConfig.java（追加 permitAll 行）—— 见 Dev Notes「共享文件」
---

# Story 1.1: 商品与 SKU 建模及只读查询接口

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **所属**：V1.4.0 Epic 1 第一个 Story（**纯后端建模 + 只读查询**）。为 1.2（库存）、1.3/1.5（后台录入与上下架）、1.6/1.7（App 展示）铺数据地基。交付：新表 `shop_products` + `shop_skus` + 只读查询接口。
> **系统内首次出现实物商品** —— 既有全部收费场景（AI 问诊解锁 / 兽医咨询 / PawCoin 充值 / 高清图下载）都是虚拟商品。
> ⚠️ **本 Story 纯后端，无前端交付**。商品录入 UI 属 1.3（后台 AB-10A/10B），Toko 展示属 1.6/1.7。**不要顺手写前端。**

## Story

As a **后端系统**,
I want **具备商品与 SKU 的持久化模型和对外只读查询接口**,
so that **后台能往里录数据、App 能把商品取出来展示（FR-94 / FR-94A）**。

## Acceptance Criteria

> **验证层**：**L0**（`mvn -B package`、DTO 校验、service 单测，无 DB）· **L1**（Docker pg + redis：落库、CHECK 生效、`ddl-auto=validate` 一致、端点跑通）。**本 Story 无 L2**（纯后端）。
> ☁️ **云端 headless 只能跑 L0**；L1 留本地，并在 Completion Notes 标注「L1 待本地验收」。

### AC1 — `shop_products` + `shop_skus` 表 + schema validate 一致

**Given** 迁移 **`V101__init_shop_products_and_skus.sql`**（独占号段 V101–V139 内，见 Dev Notes「Flyway 号」）
**When** 上下文启动
**Then** `ddl-auto=validate` 与实体一致（列 / 类型 / 约束）
**And** `shop_products` 覆盖 FR-94 的 12 项中的 **11 项**（第 5 项「规格列表」由 `shop_skus` 表承载，不是列）：

| FR-94 第 n 项 | 列 | 说明 |
|---|---|---|
| 1 商品名称 | `name` | ≤60 |
| 2 品牌 | `brand` | |
| 3 品类 | `category` | 四值 CHECK |
| 4 主图 + 图集 | `main_image_key` · `gallery_keys`(JSONB) | 🔴 **存 OSS objectKey，不存 URL、更不存签名 URL**（NFR-5 禁记签名 URL；照既有 `image_object_keys` 范式） |
| **5 规格列表（SKU）** | — | 🔴 **由 `shop_skus` 表承载，不是 `shop_products` 的列** |
| 6 适用物种 | `species` | 必填 |
| 7 适用体型 | `body_size` | 可空 |
| 8 适用年龄段 | `age_stage` | 可空 |
| 9 商品详情 | `detail_html` | 富文本 |
| 10 每日建议喂量 | `feeding_guide`(JSONB) | 见 AC2 |
| 11 保质期说明 | `shelf_life_note` | |
| 12 退货规则标识 | `return_policy` | 见下方三值 |

**And** 另含非 FR-94 的支撑列：`public_token`（AC3）· `sort_weight`（1.5 精选排序用，本 Story 只建列）· `is_active`（1.5 上下架用，本 Story 只建列）· `created_at`/`updated_at`
**And** 枚举列一律 **`varchar` + CHECK**（UPPER_SNAKE），不用 DB 原生 enum：
- `category` ∈ `('MAKANAN','OBAT_VITAMIN','CAMILAN','PERAWATAN')`
- `species` ∈ `('DOG','CAT','UNIVERSAL')`
- `body_size` ∈ `('SMALL','MEDIUM','LARGE','UNIVERSAL')`（可空）
- `age_stage` ∈ `('PUPPY','ADULT','SENIOR','UNIVERSAL')`（可空）
- 🔴 `return_policy` ∈ **`('RETURNABLE','NO_RETURN_AFTER_OPEN','NON_RETURNABLE')`** —— **三值不含「换」**（`decision-log` **C-13** 已砍换货；原「可退可换 / 不可退换」措辞作废）
> 验证层：**L0**（迁移 DDL + 实体编译）+ **L1**（Spring 上下文启动 validate 过；直接 INSERT 非法枚举值被 DB 拒绝）。

### AC2 — 每日建议喂量必须结构化，且 SKU 承载价格

**Given** `feeding_guide` 是 **FR-109 粮量见底预估的唯一计算依据**
**When** 定义该字段
**Then** 落库为 **JSONB 数组** `[{"weightMinKg":n,"weightMaxKg":n,"gramsPerDay":n}]`，🔴 **不接受自由文本**
**And** Java 侧映射为 `List<FeedingGuideEntry>`（`record`），用 `@JdbcTypeCode(SqlTypes.JSON)`（范式见 Dev Notes「复用而非重造」）
**And** 本 Story **只保证结构可存可取**；「Makanan 品类必填」的业务校验属 **1.3 后台录入**，本 Story 不做

**Given** `shop_skus` 是价格与库存的实际承载者（FR-94A）
**When** 定义 SKU
**Then** 含 `product_id`(FK) · `spec_name` · **`price`** · **`net_weight_g`**（FR-109 计算用）· `return_policy`（**可空，为空则继承商品级**）
**And** 🔴 **价格为 `BIGINT` 最小币种单位整型**（IDR 无小数，NFR-9）—— **禁用 `DECIMAL`/`double`**
**And** 同商品不同 SKU 可有不同价格、不同 `return_policy`
> 验证层：**L1**（存入含 3 段区间的 `feeding_guide` 后原样取回；SKU 级 `return_policy` 为空时读到商品级）+ **L0**（`record` 序列化/反序列化单测）。

### AC3 — 对外标识为不可枚举 token（🔒 安全攸关）

**Given** `CLAUDE.md` 强制护栏「对外暴露标识一律不可枚举 token，不用自增 id 直接外露」
**When** 生成商品/SKU 的对外标识
**Then** 两表均含 `public_token VARCHAR(32) NOT NULL` + **唯一索引**
**And** 🔴 token 由 **`SecureRandom` + Base62 22 位**生成（**AD-7**），**绝不由自增 id 派生、绝不含日期或序列规律**
**And** 🔴 **所有对外接口的路径参数与响应体一律用 `public_token`，自增 `id` 不出现在任何 JSON 中**
> 验证层：**L0**（连续生成 1000 个 token：无碰撞、无公共前缀、字符分布无明显偏斜）+ **L1**（响应体断言不含 `id` 字段）。

### AC4 — 只读查询接口

**Given** 商品已存在于库中
**When** 调用 `GET /api/v1/shop/products` 与 `GET /api/v1/shop/products/{token}`
**Then** 列表支持按 `category` 筛选，按运营权重 `sort_weight` 降序、`id` 降序兜底（稳定排序）
**And** 详情返回商品全字段 + 其 SKU 列表（各自价格与 `return_policy`）
**And** 🔴 **两个 GET 对游客放行**（FR-93A：Toko 允许未登录浏览，登录引导推迟到加购）—— 需在 `SecurityConfig` 追加放行，见 Dev Notes「共享文件」
**And** 🔴 **传入不存在的 token 返回 404 而非 403**（防枚举探测，与既有 `HealthRecordController` 同范式）
**And** 响应体 `camelCase`；错误为 **RFC 9457 ProblemDetail**，**绝不外泄堆栈**（NFR-6）
**And** ⚠️ **本 Story 不返回库存/售罄状态** —— `sku_inventory` 表属 **1.2**，本 Story 的 SKU 无库存字段，**不要提前建**
> 验证层：**L1**（两端点跑通、游客无 JWT 可访问、不存在 token→404、category 筛选正确）+ **L0**（service 层排序与筛选分支单测）。

---

## Tasks / Subtasks

> **纯后端**。顺序：迁移 → 实体/枚举/repo → token 生成器 → 查询 service → 接口 → SecurityConfig 放行 → 测试。

### 🟦 后端子任务（petgo-backend / Spring Boot 4 · Java 21）

- [ ] **T1 迁移 `V101__init_shop_products_and_skus.sql`**（AC1/AC2/AC3）
  - [ ] 文件头注释写明：**所属工作线 V1.4.0 电商 · 独占号段 V101–V139 · 本迁移不触碰共享表、无需认领**（照 `V60__init_payment_intents.sql` 的注释风格）
  - [ ] `shop_products`：12 字段 + `public_token` + `sort_weight` + `is_active` + `created_at`/`updated_at`(`TIMESTAMPTZ`)
  - [ ] `shop_skus`：`product_id` FK `ON DELETE CASCADE` + `spec_name` + `price BIGINT` + `net_weight_g` + `return_policy`(可空) + `public_token` + 时间戳
  - [ ] 全部枚举列写 CHECK；`uq_shop_products_token` / `uq_shop_skus_token` 唯一索引；`idx_shop_products_category` / `idx_shop_skus_product`
- [ ] **T2 新建 `shop/` 模块骨架**（AD-4）
  - [ ] `com.tailtopia.shop.{domain,dto,repository,service,web}` —— 与 `order/` **平级**
  - [ ] 🔴 `order/` **一行不改**（它是跨类型订单中心只读聚合层，电商订单接入属 3.9）
- [ ] **T3 实体与枚举**（AC1/AC2）
  - [ ] `ShopProduct` / `ShopSku`；枚举 `ProductCategory` / `Species` / `BodySize` / `AgeStage` / `ReturnPolicy`
  - [ ] `record FeedingGuideEntry(int weightMinKg, int weightMaxKg, int gramsPerDay)`
  - [ ] `public_token` / `created_at` 标 `updatable = false`（照 `PaymentIntent` 范式）
- [ ] **T4 `ShopTokenGenerator`**（AC3）—— `SecureRandom` + Base62 22 位，**自建于 `shop/service/`**，理由见 Dev Notes
- [ ] **T5 查询 service + DTO**（AC4）—— `ShopProductQueryService`；DTO **只暴露 `publicToken`，不暴露 `id`**
- [ ] **T6 `ShopProductController`**（AC4）—— `/api/v1/shop/products`；未知 token → `AppException.notFound(...)`
- [ ] **T7 ⚠️ `SecurityConfig` 追加游客放行**（AC4）—— **改共享文件，见 Dev Notes「共享文件」**
- [ ] **T8 测试**
  - [ ] `[L0]` `ShopTokenGeneratorTest`（1000 次无碰撞/无前缀规律）· `FeedingGuideEntryContractTest`（JSON 往返）· `ShopProductQueryServiceTest`（筛选/排序分支）
  - [ ] `[L1]` `ShopProductEndpointIntegrationTest`（两端点 · 游客可访问 · 未知 token 404 · 非法枚举被 DB 拒 · `feeding_guide` 原样往返）

### 🟨 联调验收子任务（L1 ⏳ 待本地 Docker）

- [ ] `docker compose` 起 postgres + redis → `mvn spring-boot:run` → `/actuator/health = UP`
- [ ] `ddl-auto=validate` 启动无报错
- [ ] 全量回归：`mvn -B test` 绿（**本 Story 不改任何既有代码，回归应零影响**；若有失败，先怀疑是自己动了不该动的）

### 🟩 前端子任务

- [ ] **无。** 本 Story 纯后端。

---

## Dev Notes

### 关键约定

| 项 | 值 |
|---|---|
| 模块 | **新建 `com.tailtopia.shop`**，与 `order/` 平级（**AD-4**） |
| 分层 | `domain` / `dto` / `repository` / `service` / `web` —— 照 `profile/`、`pay/` 既有分层 |
| API 前缀 | `/api/v1/shop/products`（资源小写复数，NFR-7） |
| 金额 | `BIGINT` 最小币种单位（IDR 无小数）。🔴 **禁 `DECIMAL`/`double`** |
| 时间 | `TIMESTAMPTZ`，**一律 UTC** |
| 枚举 | Java `enum` + `@Enumerated(EnumType.STRING)`；DB `varchar` + CHECK，UPPER_SNAKE |
| 命名 | DB `snake_case` ↔ Java/Dart `camelCase` ↔ JSON `camelCase`（JPA + Jackson 自动桥接） |

### 🔴 复用而非重造（照抄这些，别自己发明）

| 要做的事 | 照抄谁 | 位置 |
|---|---|---|
| 不可枚举 token 生成 | `CardTokenGenerator` —— `SecureRandom` + `BASE62` 字符表 + `LENGTH = 22` | `profile/service/CardTokenGenerator.java` |
| `public_token` + `updatable=false` + 实体内聚校验 | `PaymentIntent` | `pay/domain/PaymentIntent.java` |
| **JSONB 列表映射** | `@JdbcTypeCode(SqlTypes.JSON)` + `List<T>` | `admin/vetqual/domain/VetQualification.java:77-79`（`List<String> specialties`） |
| 建表迁移的写法与文件头注释风格 | `V60__init_payment_intents.sql`（varchar + CHECK + 唯一索引 + 注释说明设计意图） | `db/migration/` |
| Controller 鉴权与 404 防枚举 | `HealthRecordController`（owner 取 JWT、越权 404 非 403） | `profile/web/HealthRecordController.java` |
| 领域异常 | `AppException.notFound(...)` / `.validation(...)` → 自动转 ProblemDetail | `shared/error/AppException.java` |

> **为什么 token 生成器要在 `shop/` 自建而不是复用 `CardTokenGenerator`：**
> **AD-7** 给了两个选项（自建同范式 / 提升到 `shared/`）。**本 Story 取自建**——`CardTokenGenerator` 在 `profile/` 模块，跨模块 import 会让 `shop/` 依赖 `profile/`；而把它挪进 `shared/` 要改 `profile/` 的既有 import，**在三人并行下等于无谓地碰共享文件**。自建一个 20 行的同范式类，代价远低于协调成本。
> 🔴 **但字符表与长度必须与 `CardTokenGenerator` 完全一致**（Base62 / 22 位），否则两套 token 的碰撞概率与外观不一致。

### ⚠️ 共享文件：`SecurityConfig.java`

**T7 要改 `shared/security/SecurityConfig.java` —— 这是三人共享文件。**

- **并行契约 §2/§3 的共享物清单里没有列它**（清单只有 Flyway、共享枚举 CHECK、`OrderCenterService`、App 壳）。**这是契约的一个盲点，本 Story 是第一个撞上的。**
- **改法：纯追加一行 + 一行注释**，插在既有游客放行块的末尾（当前锚点在 `:166-173` 附近，`/api/v1/public/**` → `content-posts` → `comments` → `mini-profile`），照既有注释风格：
  ```java
  // Toko 商品只读对游客可见（Story 1.1，FR-93A）：GET 商品列表/详情放行（写与加购仍需 JWT）
  .requestMatchers(HttpMethod.GET, "/api/v1/shop/products",
          "/api/v1/shop/products/**").permitAll()
  ```
- 🔴 **不重排既有 `requestMatchers` 顺序**（Spring Security 按声明顺序匹配，重排会静默改变鉴权语义 —— 与共享枚举重排同类风险）
- 🔴 **改前在群里说一声**，并把这条盲点反馈到 `PARALLEL-DEV-CONTRACT.md`

### Flyway 号

- **本 Story 用 `V101`**，在 V1.4.0 独占号段 **V101–V139** 内（并行契约 §1）
- 🔴 **号序必须与 story 实现顺序一致** —— Flyway 默认 `outOfOrder=false`，先跑了大号再出现小号会**直接拒绝启动**。本 Epic 后续：1.2→`V102`
- 🔴 **合并 main 时不重排号**（与决策 E2 的最大差异；重排会让已应用迁移的校验和失配）
- ✅ **本迁移只建新表，不触碰任何共享表，无需认领**（对比 3.3 改 `payment_intents` / 6.1 改 `pet_profiles` / 6.3 改 `ck_notifications_type` 三条必须先认领）
- ⚠️ **本地库可能被跨分支迁移污染**：V1.1 的 Story 6-1 遇到过「共享 petgo 库跨分支迁移污染挡 validate」，当时用 `petgo_scratch` 库绕过。若 `validate` 报既有迁移校验和不符，**先确认是不是别人的分支迁移混进了本地库，不要去改自己的迁移**

### 强制护栏（违反即返工）

- 🔴 **禁引入 MQ / 通用缓存层 / 任何新中间件**（NFR-1）—— 本 Story 是纯 CRUD 查询，**不需要缓存**，SKU 上限 30
- 🔴 `ddl-auto=validate`，**schema 归 Flyway**，禁 `update`/`create`（NFR-2）
- 🔴 **对外标识不可枚举**（NFR-3 / AC3）
- 🔴 **RFC 9457 ProblemDetail，绝不外泄堆栈**（NFR-6）
- 🔴 **日志禁 PII**（NFR-5）—— 本 Story 数据非 PII（商品信息），但**不要把整个请求体打进日志**，避免以后加字段时踩坑

### 边界：本 Story 不做什么

| 不做 | 属于 |
|---|---|
| 库存字段 / 可售库存 / 售罄状态 | **1.2**（`sku_inventory`，`V102`） |
| 商品录入/编辑 UI、`feeding_guide` 的「Makanan 必填」业务校验 | **1.3**（后台 AB-10A/10B） |
| 上下架逻辑、精选排序维护、SKU 上限 30 告警 | **1.5**（AB-10D）—— 本 Story 只建 `is_active` 与 `sort_weight` **字段**，不建维护逻辑 |
| Toko 首页、商品详情页 | **1.6 / 1.7**（App） |
| 任何写接口（POST/PATCH/DELETE） | **1.3**（后台） |

### Project Structure Notes

- **新增目录** `petgo-backend/src/main/java/com/tailtopia/shop/`，与既有 16 个模块（`account` `admin` `auth` `consult` `content` `moderation` `notify` `order` `pay` `profile` `shared` `support` `triage` `vet` 等）平级
- **测试**放 `petgo-backend/src/test/java/com/tailtopia/shop/{domain,dto,service,web}/`，命名照既有 `*ServiceTest` / `*ContractTest` / `*EndpointIntegrationTest`
- **唯一改动的既有文件**：`shared/security/SecurityConfig.java`（纯追加）

### References

- 需求：[Source: `_bmad-output/planning-artifacts/epics-v1.4.0.md#Story 1.1`]（AC 原文）· [`v1.4.0/PRD-v1.4.0.md#FR-94`] [`#FR-94A`] [`#FR-93A`]
- 架构：[Source: `_bmad-output/planning-artifacts/architecture-v1.4.0-delta.md#AD-4`]（模块位置）[`#AD-7`]（token）
- 决策：[Source: `_bmad-output/planning-artifacts/v1.4.0/decision-log.md#C-13`]（砍换货，`return_policy` 三值）[`#C-7`]（SKU 上限 30）[`#DEP-6`]（喂量数据未到位，Rendy）
- 并行：[Source: `_bmad-output/implementation-artifacts/v1.4.0/PARALLEL-DEV-CONTRACT.md#§1`]（Flyway 号段）
- 护栏：[Source: `CLAUDE.md#强制护栏`]

---

## Dev Agent Record

### Agent Model Used

（dev agent 填写）

### Debug Log References

### Completion Notes List

<!-- 必填：
     - L0 结果（mvn -B package / 单测数）
     - L1 是否已跑；若在云端 headless 无 Docker，标注「L1 待本地验收」
     - SecurityConfig 改动是否已在群里知会
     - 实际使用的 Flyway 号（应为 V101；若因撞号顺延须写明）
-->

### File List

---

## Change Log

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-08-17 | 创建 story（`bmad-create-story`），status → ready-for-dev | 设计侧 |
