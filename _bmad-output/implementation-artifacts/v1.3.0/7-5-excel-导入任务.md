---
baseline_commit: 5f5982cb
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 7
story: 7.5
ad: [AD-S4, AD-S13]
decisions: [SD-9, SD-14]
fr: [SHOP-FR-07]
nfr: [SHOP-NFR-03, SHOP-NFR-06, SHOP-NFR-01]
---

# Story 7-5: Excel 导入任务

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。
> 本 story **纯后端 + 后台页面**（`shop/import/` + `admin/shop/`），App 一行不改。
>
> 🔴 **前置一：admin 主题 Epic 10 完成**（电商后台 17 页迁到模板 A~E + htmx）。本 story 要新建「导入工作台」页，必须落在 Epic 10 迁移后的模板体系上；先行实施＝在旧页面上改，Epic 10 迁移时整体返工（SD-16）。
> 🔴 **前置二：Story 6-4（SPU / SKU 编号）先落地**。本 story 的「认亲」逻辑（编号列留空＝新建 / 填已有＝更新 / 填不存在＝报错）直接依赖 `shop_products.spu_no` / `shop_skus.sku_no` 存在且已回填。6-4 未完成时本 story **无法开工**，不要用主键 id 顶替（AD-S2 明令不用 id）。
> 🔴 **前置三：Story 6-1（品类表）落地**，品类列校验的是 `shop_categories.code`。
>
> ⚠️ **模板同批定稿**：7-5（导入）/ 7-7（导出）/ 8-4（药品注册号）三处的 Excel 列定义**必须同批确认**。定稿后再改列＝已导入的数据要重导。本 story 负责产出**唯一事实源** `ShopImportColumnSpec`，7-7 与 8-4 直接消费它，不得各写一份。
>
> **本 story 不做图片下载**——外链转存是 **Story 7-6**。7-5 只负责把图片列的 URL 原样落到 `shop_import_rows.raw_json`，并把「转存」这一步定义为行执行的一个阶段；7-6 填入具体实现。两者的接缝在 AC7（结果文件生成时机）。

## Story

As a 运营同事，
I want 用一份 Excel 一次上架或更新一批商品，
so that 我不用一个一个填表单 —— 264 个规格靠手填是不可能完成的工作量（SD-9）。

## Context（本 story 必须知道的现状）

### (a) 仓里已有一套 Excel 导入范式，**照抄它的形状，但不得复制它的缺口**

既有实现是种子内容批次的导入：`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchExcelService.java:50`（`@Service`，**无构造器、无依赖、几乎全 static**）。

**值得抄的三段**：

| 范式 | 位置 | 要点 |
|---|---|---|
| 解析在事务外 | `SeedBatchExcelService` **完全无 `@Transactional`** | 解析纯函数，只产出 `RawRow` 列表 |
| 落行整份原子 | `SeedBatchEntryService.appendRows`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchEntryService.java:133` 的 `@Transactional`，体 `:134-182`） | 逐行收集错误 → **行仍落库**，错误挂在行上（`:176-178` 以 `；` 拼进 `errorMessage`） |
| 执行逐行 `REQUIRES_NEW` | `SeedBatchPublishService`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchPublishService.java`）：`:240-241` `safelyFail`、`:255-256` `publishOrScheduleRow`，主循环 `:122`，异常兜底 `:159-166` | 🔴 **必须经 self-proxy 调用才生效**：`:53` `private final ObjectProvider<SeedBatchPublishService> selfProvider` + `:110` `self = selfProvider.getObject()`。直接 `this.publishOrScheduleRow(...)` 会让 `REQUIRES_NEW` 静默失效 |

**逐行错误容器**：`record RowValidation(SeedBatchRow row, List<String> errors, boolean duplicate)`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/dto/RowValidation.java:18`，`passes()`:20 / `warns()`:25）。校验器 `SeedBatchValidator.validate`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchValidator.java:56-57`，`@Transactional(readOnly = true)`）**逐行累积全部错误、一行一个 `RowValidation`、绝不中止整批**（`:62-66`、`validateRow` `:69-140`）。

### (b) 🔴 既有范式的三个缺口 —— 本 story **必须补上，不得复制**

| 缺口 | 证据 | 本 story 的要求 |
|---|---|---|
| **不校验 content-type** | `SeedBatchExcelService.parse` `:167-170` 唯一的入参判断是 `file == null \|\| file.isEmpty()`；**从不读 `getContentType()` / `getOriginalFilename()`**，全靠 `WorkbookFactory.create` 抛异常。前端只有 HTML 提示 `accept=".xlsx,.xls"`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/resources/templates/admin/seed-batch-workspace.html:115`），客户端提示不是校验 | AC3：服务端显式校验 content-type **与**扩展名 |
| **无行数上限** | `:175` `for (Row row : sheet)` 无计数器、无 break；`appendRows` 也不限行 | AC3：≤500 行，**解析期**即拒 |
| **无 dry-run** | `POST …/import`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/web/AdminSeedBatchWorkspaceController.java:240-246`）直接写 DRAFT 行；预览是对已落库行的独立步骤（`:344` `GET …/preview`） | 本 story **沿用「落行 + 独立预览」而不是真 dry-run**（见 Dev Notes「为什么不做 dry-run」） |

唯一真实生效的大小上限是全局 multipart：`/Users/dai/work/petgo-platform/petgo-backend/src/main/resources/application.yml:203` `max-file-size: ${MULTIPART_MAX_FILE_SIZE:10MB}` / `:205` `max-request-size: ${MULTIPART_MAX_REQUEST_SIZE:20MB}`。本 story 的 5MB 是**服务层判定**，全局那层只负责放行（照 `:200` 的既有注释纪律：「判定点唯一地留在服务层」）。

🔴 **既有 `parse` 没有任何端到端测试**（`SeedBatchEntryIntegrationTest` 只覆盖 `template` / `parseAccount` / `parseTime`）—— 抄范式时不要以为「它测过了」。

### (c) POI 现状

`/Users/dai/work/petgo-platform/petgo-backend/pom.xml:83-87`：唯一依赖 `org.apache.poi:poi-ooxml`，版本 **`5.4.1` 硬编码在 `<version>` 里**（`:86`），`<properties>` 只有 `java.version`（`:29-31`），Spring Boot BOM 不管 POI。**本 story 不升级、不新增 POI 依赖。**

全仓 **无 SXSSF、无 SAX 流式读**，全是 DOM 模式 `WorkbookFactory.create` / `XSSFWorkbook`。500 行 × 12 列在 DOM 模式下完全够用，**本 story 不引入 SXSSF**（7-7 的 10,000 行导出另议，见该 story）。

### (d) 异步与启动重扫的真实范式

🔴 **仓里没有名叫 `RetryScanner` 的类**（AD-S4 的措辞是范式代称，不是类名）。真实范式只有两处：

- `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/triage/service/TriageTaskScanner.java:22`（类）—— `:34` `@Async` + `:35` `@EventListener(ApplicationReadyEvent.class)` + `:36` `public void rescanOnStartup()`；扫 `status ∈ {PENDING, PROCESSING}` 的残留任务续跑，逐条 try/catch（`:44-48`）。类注释 `:14-19` 明写「仅用 DB 状态机重扫，**禁引入 MQ / 定时中间件**」。
- `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/account/service/AccountDeletionService.java:189-191` 同形。

**状态机 + retry_count 的既有样板**：`TriageTask`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/triage/domain/TriageTask.java:66-67` `retry_count`、`:130-132` `markRetry()`），处理器重试上限 ≤3（`TriageProcessor.java:87-88`）。**本 story 的 job 状态机照这个形状写。**

⚠️ **线程池**：`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shared/async/AsyncConfig.java:16-17` `@EnableAsync @EnableScheduling`；业务 `@Async` 走 Boot 默认池（`application.yml:189-191` `spring.task.execution.mode: force` 强制恢复，因为 `analyticsExecutor` bean 会抑制自动配置）。**默认池队列无界**——导入任务是长跑任务（含 7-6 的图片下载），必须在 Dev Notes 记下这个事实，别让一次 500 行的导入把池占死（缓解手段见 Dev Notes）。

### (e) 权限码现状（本分支 72 个）

`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminPermissions.java:13` —— 本分支 **72 个**常量（admin 主题合入后 74）。既有 `shop.*` **10 个**（`:245` ~ `:283`）。
`ALL` 是**从 GROUPS 派生**的（`:323-327`），所以**不进 GROUPS 的常量根本不存在于 `ALL`**、`isValid` 会拒、账号页也勾不到。`GROUPS` 恰好 2 组：`:292` 声明，`:293-304` `perm.group.view`，`:305-317` `perm.group.edit`。

### (f) 商品与规格的数据形状（认亲与落库要用）

| 实体 | 位置 | 与本 story 相关的列 |
|---|---|---|
| `ShopProduct` | `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopProduct.java:31` | `name`:40 · `brand`:43 · `category`:47（6-1 后改 `String categoryCode`）· `mainImageKey`:51 · `mainImageW/H`:64,67 · `galleryKeys`:72 · `species`:76 · `detailHtml`:87 · `active`:110 · `publicToken`:37 |
| `ShopSku` | `.../shop/domain/ShopSku.java` | `productId`:37 · `specName`:40 · `price`:44（**long，印尼盾整数**）· `costPrice`:55 |
| `SkuInventory` | `.../shop/domain/SkuInventory.java` | 🔴 **库存不在 SKU 表上**：`actual`:34 / `locked`:38 / `version`:41，独立一张表 |

⇒ 「初始库存」列写的是 `SkuInventory.actual`，**只在新建 SKU 时建行**；更新时整列忽略（SD-14）。

### (g) 对外标识范式

`ShopTokenGenerator`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shop/service/ShopTokenGenerator.java:21`）：SecureRandom + Base62 × 22（`:23-25`、`:29-35`）。导入任务的 `public_token` 用它，**不外露自增 id**（基线护栏）。

### (h) 后台页面与鉴权范式

`AdminShopProductController`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/shop/web/AdminShopProductController.java:49`）：`:51-53` `VIEW_AUTH` / `:54-55` `EDIT_AUTH` 两个常量 + 每个 handler `@PreAuthorize(...)`。写端点一律本地 catch `AppException`（`:221-226` 的注释写明理由：不 catch 会把 RFC 9457 裸 JSON 甩给运营）。
既有的「工作台」页形状可参照 `admin/seed-batch-workspace.html` 与 `AdminSeedBatchWorkspaceController.java:34`（**无类级 `@RequestMapping`**，每个 handler 写全路径）。

## Acceptance Criteria

**AC1 · 两张新表与 job 状态机**
**Given** 仓里没有任何导入任务表
**When** 执行迁移 `V<yyyyMMdd_HHmm>__init_shop_import_jobs.sql`（时间戳取创建时刻）
**Then** 新增 `shop_import_jobs`，至少含：`id` · `public_token` varchar(32) UNIQUE NOT NULL（Base62×22，`ShopTokenGenerator`）· `status` varchar(16) NOT NULL · `total_rows` int NOT NULL DEFAULT 0 · `success_rows` int NOT NULL DEFAULT 0 · `failed_rows` int NOT NULL DEFAULT 0 · `result_object_key` varchar(255) NULL（**生成后才填**）· `operator_account_id` bigint NOT NULL · `retry_count` int NOT NULL DEFAULT 0 · `error_message` varchar(500) NULL · `created_at` / `updated_at` timestamptz `[L1]`
**And** `status` 值域恰为 `PENDING` / `RUNNING` / `DONE` / `PARTIAL_FAILED` / `FAILED`，落库 varchar + UPPER_SNAKE `[L0]`
**And** 新增 `shop_import_rows`：`id` · `job_id` bigint NOT NULL **FK ON DELETE CASCADE** · `row_no` int NOT NULL · `raw_json` jsonb NOT NULL · `error_message` varchar(500) NULL · `product_id` bigint NULL · `sku_id` bigint NULL · `created_at` / `updated_at`；`(job_id, row_no)` 唯一 `[L1]`
**And** 🔴 计数列一律 **`int` 配 `integer`**，禁 `SMALLINT`（Hibernate 把 Java `int` 映成 `int4`，配 `SMALLINT` 会 `ddl-auto=validate` 启动即红 —— 仓内踩过）`[L1]`
**And** 表与列有 `COMMENT`，写明 `shop_import_jobs` 是**导入任务状态机**、`retry_count` 的上限语义 `[L0]`
**And** 迁移用时间戳版本号，`bash scripts/ci/check-flyway-versions.sh origin/main` 通过 `[L0]`

**AC2 · 🔴 列规则（SD-14）落为单一事实源，一条不漏**
**Given** 7-5 / 7-7 / 8-4 三处必须用同一份列定义
**When** 新增 `ShopImportColumnSpec`（`shop/import/domain`，`public final class`，全 static 常量 + 纯函数）
**Then** 模板生成、导入解析、导出（7-7）**三方共用它**，任何一方不得另写一份列顺序或表头 `[L0]`
**And** 落地下表的**全部**规则；本表即验收清单，逐条都要有对应测试 `[L0]`

| # | 列 | 新建（编号列留空） | 更新（编号列填已有） | 更新时**空单元格** |
|---|---|---|---|---|
| 1 | SPU 编号 | 留空＝新建，系统按 6-4 生成 | 填已有＝更新该 SPU；**填不存在＝该行报错** | — |
| 2 | SKU 编号 | 留空＝新建 | 填已有＝更新该 SKU；**填不存在＝该行报错** | — |
| 3 | 商品名称 | ✅ 必填 | ✅ 可改 | **保留原值** |
| 4 | 品牌 | ✅ 必填 | ✅ 可改 | **保留原值** |
| 5 | 品类编码 | ✅ 必填，须是 `shop_categories.code` 且 `is_active=true` | ✅ 可改，同校验 | **保留原值** |
| 6 | 物种 | ✅ 必填，`Species` 枚举 | ✅ 可改 | **保留原值** |
| 7 | 规格名 | ✅ 必填 | ✅ 可改 | **保留原值** |
| 8 | 售价 | ✅ 生效 | 🔴 **一律忽略**（改价只走 7-2） | — |
| 9 | 初始库存 | ✅ 生效，建 `SkuInventory` 行 | 🔴 **一律忽略**（改库存只走 7-3） | — |
| 10 | 药品注册号 | ✅（依赖 8-4） | ✅ 可改 | **保留原值** |
| 11 | 商品详情 | ✅ | ✅ 可改 | **保留原值** |
| 12 | 图片地址（多个逗号分隔） | ✅ 外链，7-6 转存 | ✅ **填写即整体替换**（不是追加） | **保留原图** |

**And** 🔴 第 8、9 两列在更新态被忽略时，**结果文件里必须逐行写一条提示**（「售价 / 库存列在更新时已忽略」），不能默默吞掉 —— 运营改了价却没生效而系统一声不吭，比报错更糟 `[L0]`
**And** 🔴 「空单元格＝保留原值」与「填了空白字符串＝保留原值」按**同一条**处理；想清空某个字段**本版不支持**，模板提示行里写明 `[L0]`
**And** 第 10 列在 Story 8-4 未落地时**保留占位列且整列忽略**（保证列顺序此刻即定稿，8-4 落地时只接线不改列序）`[L0]`

**AC3 · 🔴 上传闸：content-type、扩展名、大小、行数，四项都显式校验**
**Given** 既有 `SeedBatchExcelService.parse:167-170` 只判空，三个缺口俱在（Context (b)）
**When** 运营上传一个文件
**Then** 服务层显式校验 `MultipartFile.getContentType()` ∈ 白名单（`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 与 `application/vnd.ms-excel`）**且**文件名扩展名 ∈ `{.xlsx, .xls}`；任一不符 → 整份拒收，i18n 错误码 `[L0]`
**And** 服务层显式校验 `file.getSize() <= 5MB`（常量，注释注明与 `application.yml:203` 的 10MB 全局闸的分工：全局只放行，判定在服务层）`[L0]`
**And** 🔴 **行数在解析期计数，超过 500 立即中断并整份拒绝**，不得先全部读完再判；错误提示写明实际行数与上限 `[L0]`
**And** 零数据行 → 整份拒绝（fail-fast，照 `SeedBatchExcelService.java:201-204` 的 `noDataRows` 范式）`[L0]`
**And** 表头**按文字校验**而不是按行号：表头行必须与 `ShopImportColumnSpec` 的表头逐列一致，不一致整份拒绝并指出第几列对不上 —— 🔴 既有范式是 `row.getRowNum()==0` 跳过、**从不校验表头文字**（`SeedBatchExcelService.java:176-178`），运营删了一列或调了列序它会静默错位落库 `[L0]`
**And** 单测覆盖：非 Excel 的 content-type、伪装扩展名、5MB+1、501 行、0 数据行、表头缺列 / 乱序 —— **六条各一条用例** `[L0]`

**AC4 · 校验分三层，层层语义不同**
**Given** AD-S4 的分层要求
**When** 一份文件被导入
**Then** **解析期 fail-fast**：AC3 的六类问题 → 整份拒绝，**一行都不落库** `[L0]`
**And** **落行期逐行收集**：格式级问题（金额非数字 / 物种不是枚举 / 品类不存在 / 编号填了但查不到）逐行收集成 `error_message`，**行仍然落库**（照 `SeedBatchEntryService.java:134-182` 范式）`[L1]`
**And** **执行期逐行 `REQUIRES_NEW`**：一行抛异常只让该行标 `FAILED`，其余行照常（照 `SeedBatchPublishService.java:255-256`）`[L1]`
**And** 🔴 `REQUIRES_NEW` **必须经 self-proxy 调用**（`ObjectProvider<自身>`，照 `SeedBatchPublishService.java:53,:110`）；写一条测试证明一行失败后其余行仍落库 —— 这条测试同时是「self-proxy 有没有写对」的探针 `[L1]`

**AC5 · 金额非法拒收，不四舍五入；新商品默认未上架**
**Given** 运营在售价列填了 `12.5` / `12,500` / `一万二` / 负数 / 0
**When** 解析该行
**Then** 🔴 **拒收该行并写明原因，绝不四舍五入、绝不静默取整**（价格是资金字段，静默取整＝资损）`[L0]`
**And** 合法值定义为：**非负整数的印尼盾**，允许千分位分隔符被去除后仍是整数；小数位非 0 一律拒 `[L0]`
**And** 价格 `0` 单独给一条可读错误（0 元商品须走单条编辑，与 7-2 的口径一致）`[L0]`
**And** 🔴 **导入新建的商品一律 `is_active = false`**（默认未上架），无论 Excel 里写了什么 —— 模板中**不提供上架列** `[L0/L1]`
**And** 单测逐个覆盖上述非法形态 `[L0]`

**AC6 · 执行走 `@Async` + 启动重扫，禁任何中间件**
**Given** 导入是长跑任务
**When** 运营提交上传
**Then** 请求**立即返回** job 的 `public_token`，`status=PENDING`；执行走 `@Async`，**不在 HTTP 线程里跑** `[L0]`
**And** 启动重扫：`@Async` + `@EventListener(ApplicationReadyEvent.class)`，扫 `status ∈ {PENDING, RUNNING}` 的残留 job 续跑，照 `TriageTaskScanner.java:34-36` 范式 `[L0/L1]`
**And** 🔴 **不得引入** Quartz / Kafka / RabbitMQ / Redis Stream / 任何队列或调度中间件（SHOP-NFR-06 / F-3）；也**不得**为此加通用缓存层 `[L0]`
**And** 单个 job 失败 → `retry_count + 1` 且保持 `PENDING`，下次重扫续跑；超过 **3** 次 → `status=FAILED` + `error_message` + `log.error`，不再重试（照 `TriageProcessor.java:87-88` 的 ≤3 口径）`[L1]`
**And** 重扫**幂等**：同一个 job 被重扫两次不会把已成功的行重复建成两个商品（判据落在行级 —— 已回填 `product_id` / `sku_id` 的行直接跳过）`[L1]`

**AC7 · 🔴 结果文件在图片转存全部结束之后才生成**
**Given** 图片转存（Story 7-6）是行执行的最后一个阶段，且可能失败
**When** job 推进
**Then** `result_object_key` **只在所有行的图片阶段都已终结（成功或失败）之后**才写入；在此之前该列为 NULL，页面显示「处理中」而不是给一个内容不全的文件 `[L1]`
**And** job 终态判定：全部行成功 → `DONE`；有成功也有失败 → `PARTIAL_FAILED`；**一行都没成功** → `FAILED` `[L0/L1]`
**And** 结果文件是一份 Excel，**列与输入模板同构**并追加两列：`处理结果`（成功 / 失败）与 `失败原因`；失败行的原因逐行可读 `[L0]`
**And** 结果文件对象 key 走既有 `public/<folder>/<UUID>.<ext>` 约定但落**私有**语义：🔴 结果文件含商品编号，**不要放公共桶裸 CDN**，用 `SignedUrlService` 短 TTL 预签名下载（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shared/media/SignedUrlService.java:36`，TTL 默认 300s）`[L0/L2]`
**And** 7-6 尚未落地时，图片列**原样存进 `raw_json` 并整列跳过**，结果文件照常生成且在提示列写明「图片转存待 7-6」`[L1]`

**AC8 · 新权限码 `shop.product_import`，🔴 加码必改 5 处**
**Given** 本分支 `AdminPermissions.ALL` 现为 **72**
**When** 加 `shop.product_import`
**Then** 下面 5 处**全部**改到，缺一不绿 `[L0]`：

| # | 位置 | 具体动作 |
|---|---|---|
| 1 | `AdminPermissions.java` shop 常量块（`:243-283`） | 新增 `public static final String SHOP_PRODUCT_IMPORT = "shop.product_import";` |
| 2 | `AdminPermissions.GROUPS`（`:292` 声明；view 组 `:293-304` / edit 组 `:305-317`） | 🔴 加进 **edit 组**（导入是写操作）。**不进 GROUPS 就不在 `ALL` 里**（`ALL` 由 GROUPS 派生，`:323-327`），`isValid` 会拒、账号页勾不到 |
| 3 | i18n —— 🔴 **是 4 个文件不是 3 个** | `i18n/messages.properties`（shop 块 `:1389-1398`）· `messages_zh_CN.properties`（`:1415-1424`）· `messages_en.properties`（`:1412-1421`）· `messages_id.properties`（`:1371-1380`），各加一行 `perm.shop.product_import=…` |
| 4 | `AdminPermissionsTest.java` | `:126` `assertThat(all).hasSize(72);` → `73`；并在 `:79-124` 的台账注释里**补一行说明**（该文件的纪律是「每加一码记一笔账」） |
| 5 | 预置角色默认授予 —— 🔴 **实为 `AdminRole.java` 的 Java 枚举，不是种子迁移** | `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminRole.java:83`：`:29-38` 加静态导入 + 授予 `OPS_MANAGER`（`:97`）与 `OPERATIONS`（`:119`）。**不授予 `SUPPORT` / `FINANCE`** |

**And** 🔴 还有一道会红的守门测试：`AdminPermissionWiringTest.java:115-116` 要求 `ALL` 里每个码**至少有一处 `hasAuthority(...)` 落点** —— 所以权限码必须与本 story 的端点同批落地，不能先加码后接线 `[L0]`
**And** 导入相关的**所有**端点（上传、查状态、下载模板、下载结果）都 `@PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('shop.product_import')")`，照 `AdminShopProductController.java:54-55` 的常量范式抽成一个 `IMPORT_AUTH` 常量 `[L0]`
**And** 每次**提交导入**写一条 `AdminAuditService.record`（动作码 `SHOP_PRODUCT_IMPORT`），记 job token / 行数 / 操作人；🔴 **不记 Excel 内容本身** `[L0/L1]`
**And** 「导入工作台」页在 admin 主题登记为 **AB-19A「零端点变更」的新例外**（AD-S13）：它是新能力，不存在可复用的既有端点。登记动作写进 Completion Notes `[L0]`

**AC9 · 认亲：编号三态（依赖 6-4）**
**Given** 6-4 已落地 `spu_no` / `sku_no` 且存量已回填
**When** 解析编号列
**Then** 编号列**两列都留空** → 新建 SPU + 新建 SKU `[L1]`
**And** SPU 编号填了**已有值**、SKU 编号留空 → 在该 SPU 下**新增一个 SKU** `[L1]`
**And** 两列都填了**已有值**且 SKU 确属该 SPU → 更新 `[L1]`
**And** 🔴 编号填了但**查不到** → **该行报错**（不是创建、不是忽略）`[L1]`
**And** 🔴 SKU 编号存在但**不属于**所填 SPU → 该行报错（防止跨商品错改）`[L1]`
**And** 🔴 编号列**运营不可改**：即使 Excel 里改了编号也不写回（6-4 的 `updatable=false`）；更新态下编号仅作定位键 `[L0]`

**AC10 · 集成测试：三类行混在一份文件里**
**When** 导入一份含 **新建行 / 更新行 / 报错行** 三类的文件
**Then** `total_rows` / `success_rows` / `failed_rows` 三个计数与实际一致且相加等于总行数 `[L1]`
**And** 成功行真的落了库（新建的能查到、更新的字段真变了）`[L1]`
**And** 失败行有可读 `error_message`，**且不影响其余行** `[L1]`
**And** 更新行的**售价与库存没有被改动**（SD-14 的核心断言，单独一条用例）`[L1]`
**And** 更新行留空的单元格对应字段**保持原值**（不是被置空）`[L1]`

**AC11 · 回归与打包**
**Then** 既有 `admin/seed` 那套导入**一行不改、测试全绿**（本 story 是新建平行实现，不重构它）`[L0]`
**And** `mvn -B clean package` 通过；`ddl-auto=validate` 下能启动（L1）`[L0/L1]`

---

## Tasks / Subtasks

- [ ] **T0 · 开工前确认前置**（前置一 / 二 / 三）
  - [ ] 确认 admin Epic 10 已完成、目标页已在模板 A~E 体系里；未完成则**停手**，不要在旧页面上改
  - [ ] 确认 6-4 的 `spu_no` / `sku_no` 已落地并回填；6-1 的 `shop_categories` 已落地
  - [ ] 与 7-7 / 8-4 的负责人对齐列定义并**当场定稿**（AC2 的表），定稿结论写进 Completion Notes

- [ ] **T1 · 两张表与实体**（AC1）
  - [ ] 新建迁移（时间戳号）+ `ShopImportJob` / `ShopImportRow` 实体 + 两个 repository
  - [ ] `status` 用独立枚举类 `ShopImportJobStatus`（varchar + UPPER_SNAKE），照 `SeedBatchRowStatus`（`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/domain/SeedBatchRowStatus.java:35`）的形状写**允许迁移表**与 `canGoTo`
  - [ ] 🔴 计数列用 `integer`（禁 `SMALLINT`）；`raw_json` 用 `jsonb`
  - [ ] `job_id` FK **ON DELETE CASCADE**（删 job 连行一起走，不留孤儿）
  - [ ] `bash scripts/ci/check-flyway-versions.sh origin/main`

- [ ] **T2 · `ShopImportColumnSpec` 单一事实源**（AC2）
  - [ ] `shop/import/domain/ShopImportColumnSpec.java`，`public final class` + 私有构造
  - [ ] 常量：`HEADERS`（列顺序与表头文字）· `HINTS`（模板提示行）· 每列的 `ColumnRule`（新建是否必填 / 更新是否忽略 / 空值语义）
  - [ ] 🔴 **模板生成、解析、7-7 导出三方都从这里取**；写类注释挂上「改这里等于改契约，须三处同批」
  - [ ] 第 10 列（药品注册号）留占位 + 注释标 `TODO(8-4)`

- [ ] **T3 · 解析器 `ShopImportExcelParser`**（AC3、AC4 第一层、AC5）
  - [ ] **无 `@Transactional`**（照 `SeedBatchExcelService` 解析在事务外的范式）
  - [ ] 入口先跑四道闸：content-type → 扩展名 → size → （读到第 501 行即中断）
  - [ ] 🔴 表头**按文字**逐列比对，不用行号跳过
  - [ ] 金额解析：去千分位 → 必须是非负整数 → 0 单独报错；**任何形式的四舍五入都是 bug**
  - [ ] 空行跳过、模板提示行跳过（照 `isHintRow` 范式 `SeedBatchExcelService.java:214-216`）
  - [ ] null-safe 取单元格（照 `cell()` `:218-220`）；用 `DataFormatter` 拿**显示值**，别用 `getNumericCellValue` 直接取（数字格式会把编号变成 `1.0E5`）

- [ ] **T4 · 落行 `ShopImportJobService.submit`**（AC4 第二层、AC6 前半、AC8 审计）
  - [ ] `@Transactional` 整份原子：建 job（`PENDING`）+ 批量插 rows（`raw_json` 原样存，错误挂 `error_message`，**行仍落库**）
  - [ ] 建完立即返回 job token；`@Async` 触发执行（🔴 事务内直接调 `@Async` 方法会在提交前开跑 —— 用 `@TransactionalEventListener(AFTER_COMMIT)` 或在事务外触发，二选一并在注释写明理由）
  - [ ] `AdminAuditService.record("SHOP_PRODUCT_IMPORT", …)`，只记 token / 行数 / 操作人

- [ ] **T5 · 执行器 `ShopImportExecutor`**（AC4 第三层、AC5 后半、AC6、AC9）
  - [ ] `@Async` 主方法：job → `RUNNING` → 逐行 `self.executeRow(rowId)`（🔴 `ObjectProvider<自身>` self-proxy，照 `SeedBatchPublishService.java:53,:110`）
  - [ ] `executeRow` 标 `@Transactional(propagation = REQUIRES_NEW)`；失败走 `safelyFail(rowId, reason)`（同样 `REQUIRES_NEW`）
  - [ ] 认亲三态（AC9）；🔴 SKU 不属于所填 SPU 要单独报错
  - [ ] 新建商品 `is_active = false` **写死**，不读 Excel
  - [ ] 更新时**跳过**售价与库存两列，并往该行的提示里追加一句（AC2）
  - [ ] 更新时空单元格 → 不调 setter（不是 `set(null)`）
  - [ ] 图片列：**本 story 只把 URL 列表存进 `raw_json` 并留出 `fetchImages(row)` 接缝**，7-6 填实现；7-6 未落地时整列跳过
  - [ ] 幂等：已回填 `product_id`/`sku_id` 的行直接跳过

- [ ] **T6 · 启动重扫**（AC6）
  - [ ] `ShopImportJobScanner`：`@Async` + `@EventListener(ApplicationReadyEvent.class)`，照 `TriageTaskScanner.java:34-36` 逐字抄形状（含逐条 try/catch + `log.warn`）
  - [ ] `retry_count` ≤3，超限置 `FAILED`
  - [ ] 🔴 类注释写明「禁 MQ / 调度中间件」，照 `TriageTaskScanner.java:14-19`

- [ ] **T7 · 结果文件**（AC7）
  - [ ] `ShopImportResultWriter`：用 `XSSFWorkbook`（照既有导出范式；500 行不需要 SXSSF）
  - [ ] 🔴 **只在所有行终结后**才生成并写 `result_object_key`
  - [ ] 上传走 `AliyunOssClient`；下载走 `SignedUrlService` 短 TTL 预签名，**不给裸 CDN URL**
  - [ ] 结果文件的单元格也要做**公式转义**（与 7-7 同一个工具方法 —— 谁先落地谁建，另一方直接用，不要建第二份）

- [ ] **T8 · 权限码 5 处 + 端点 + 页面**（AC8）
  - [ ] 按 AC8 的表逐处改；改完先跑 `AdminPermissionsTest` 与 `AdminPermissionWiringTest` 确认两处都绿
  - [ ] 端点（全部 `@PreAuthorize(IMPORT_AUTH)`）：`GET …/template`（`@ResponseBody` xlsx）· `POST …/import` · `GET …/jobs/{token}`（htmx 轮询状态）· `GET …/jobs/{token}/result`
  - [ ] 页面：导入工作台，比照 `admin/seed-batch-workspace.html`，走 Epic 10 的模板 + htmx 局部更新（200 主 fragment + `hx-swap-oob`，422 行内错，403 禁用态注明缺哪个权限，**禁返 JSON**）
  - [ ] 写端点本地 catch `AppException`（照 `AdminShopProductController.java:221-226` 的纪律）
  - [ ] 在 admin 主题登记 AB-19A 新例外

- [ ] **T9 · 测试**（AC1~AC11）
  - [ ] L0：AC3 的六条闸各一条；AC5 的金额非法形态各一条；`ShopImportColumnSpec` 的列顺序/表头快照测试（防止有人悄悄改列序）
  - [ ] L1：三类行混合导入（AC10 五条断言）· 一行抛异常其余照常（AC4 self-proxy 探针）· 重扫幂等 · 认亲五态 · 结果文件生成时机
  - [ ] 🔴 **一条专门的「售价与库存在更新时未被改动」测试**，这是 SD-14 的核心，不要混在别的用例里

- [ ] **T10 · 云端执行须知**
  - [ ] 云端只跑 L0：`cd petgo-backend && ./mvnw -B clean package`
  - [ ] L1（Docker postgres + redis）与 L2（真实 OSS 下载结果文件）留本地
  - [ ] Completion Notes 写「L1/L2 待本地验收」+ 列定义定稿结论 + AB-19A 登记 + 权限码 5 处的实际改动清单

---

## Dev Notes

### 为什么不做真 dry-run

AD-S4 与 epics 都没要求 dry-run，既有范式也没有（Context (b)）。本 story 的等价能力是「**落行 + 结果文件**」：所有行先落 `shop_import_rows`（含错误），运营在工作台看得到逐行结果，出错的行原样留在库里可追查。真 dry-run 需要把整套认亲与校验跑两遍（一遍空跑一遍真写），代价与收益不成比例。
🔴 但**行数与文件闸必须是真的**（AC3）—— 没有 dry-run 就更不能让一份 5000 行的垃圾文件先落库再说。

### `@Async` 池的现实

业务 `@Async` 走 Boot 默认池（`AsyncConfig.java` 只定义了 `analyticsExecutor`，`application.yml:189-191` 用 `spring.task.execution.mode: force` 把业务池救回来），**默认队列无界**。一次导入 = 500 行 × （可能的图片下载，7-6）可能跑很久。缓解：
- **同一时刻只允许一个 `RUNNING` job**（服务层判断；提交时若已有 `RUNNING` 则新 job 保持 `PENDING`，由当前 job 结束后或重扫接力）。这条既防池被占死，也防两个运营同时导入同一批商品打架。
- 🔴 **不要**为此新建线程池 bean —— `AsyncConfig.java:33-36` 的注释已经写明再加一个实现 `Executor` 的 bean 会怎样搅乱自动配置。

### POI 的两个坑

1. `DataFormatter` 取的是**显示值**；直接 `getNumericCellValue()` 会把 `SPU-000123` 这类被 Excel 当成数字的单元格变成 `123.0`，把大数变成 `1.23E5`。**一律走 `DataFormatter`**（照 `SeedBatchExcelService.java:173`）。
2. 全仓 POI 是 **5.4.1 硬编码**（`pom.xml:86`）。本 story 不动版本；若发现需要新 API，在 Completion Notes 提出来，**不要顺手升级**。

### 与其它 story 的接缝

| story | 接缝 | 谁先谁后 |
|---|---|---|
| **6-1** 品类表 | 品类列校验 `shop_categories.code` + `is_active` | 6-1 先 |
| **6-4** 编号 | 认亲的全部依据 | 🔴 6-4 必须先 |
| **7-6** 外链图片转存 | 本 story 留 `fetchImages(row)` 接缝 + AC7 的结果文件时机 | 7-5 先（7-6 填实现） |
| **7-7** 商品导出 | **共用 `ShopImportColumnSpec`**；公式转义工具二者共用一份 | 同批定稿，实现可并行 |
| **8-4** 药品注册号 | 第 10 列 | 列此刻定稿，8-4 落地时只接线 |
| **7-2 / 7-3** 批量改价 / 改库存 | 🔴 本 story 在更新态**主动放弃**这两列的写权 | 无依赖 |

### 不做什么

- 不做多 sheet、不做公式、不做单元格样式校验。
- 不做「导入即上架」——新商品恒为未上架（AC5），运营自己去上架。
- 不做删除（Excel 里没有「删除」列，删商品走单条操作）。
- 不重构 `admin/seed` 那套导入；它服务的是另一个域，共用只会把两边都拖住。
- 不引入任何中间件、不加新依赖。

### Project Structure Notes

- 新增包 `com.tailtopia.shop.imports`（🔴 **不要用 `shop.import`** —— `import` 是 Java 关键字，不能作包名段）：`domain/`（`ShopImportJob` · `ShopImportRow` · `ShopImportJobStatus` · `ShopImportColumnSpec`）· `repository/` · `service/`（`ShopImportExcelParser` · `ShopImportJobService` · `ShopImportExecutor` · `ShopImportResultWriter` · `ShopImportJobScanner`）。
- 后台：`admin/shop/web/AdminShopImportController` + `templates/admin/shop-import-workspace.html`。
- 修改：`AdminPermissions.java` · `AdminRole.java` · 四个 `i18n/messages*.properties` · `AdminPermissionsTest.java` · `application.yml`（若新增 `petgo.shop.import.*` 配置段）。
- 一支新迁移（时间戳号）。

### References

- [Source: `_bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S4`（含限额与「不得复制缺口」原文）+ `#AD-S13` + §0 F-3 + §4 M5]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 7-5`]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md` SD-9 / SD-14]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#D.9`（`SeedBatchExcelService` 完整范式与缺口）+ `#D.11`（权限码台账）]
- [Source: `petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchExcelService.java`]
- [Source: `petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchPublishService.java:53,:110,:240,:255`]
- [Source: `petgo-backend/src/main/java/com/tailtopia/triage/service/TriageTaskScanner.java:34-36`（启动重扫真实范式）]
- [Source: `petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminPermissions.java:292-327`、`AdminRole.java:83`]

## 验收与交付

- **L0**
  - `cd petgo-backend && ./mvnw -B clean package`
  - `bash scripts/ci/check-flyway-versions.sh origin/main`
  - 重点用例：`ShopImportExcelParserTest`（四道闸 + 金额）· `ShopImportColumnSpecTest`（列序快照）· `AdminPermissionsTest` · `AdminPermissionWiringTest`
- **L1**（需 Docker daemon + postgres + redis）
  - 跑法：flush Redis DB0 → 重建 scratch 库 → 带 `DB_NAME` env（共享 dev 库会被别的分支迁移污染）
  - `ShopImportIntegrationTest`：三类行混合 · 计数 · 售价库存被忽略 · 空单元格保留原值 · 一行失败其余照常 · 重扫幂等 · 认亲五态
  - 本地起服务确认 `ddl-auto=validate` 通过、`/actuator/health` 为 UP
- **L2**（真实对象存储）
  - 上传一份真文件 → 工作台看到状态推进 → 下载结果文件确认可打开、失败原因可读、预签名 URL 有效期正常
- Completion Notes 必须写：**L1/L2 待本地验收** · 列定义定稿结论（与 7-7 / 8-4 对齐的证据）· AB-19A 例外登记 · 权限码 5 处的实际改动清单 · 「同一时刻单 job」的决策是否采纳。

## Definition of Done

- [ ] AC1~AC11 全部满足，每条能指到具体测试方法名
- [ ] 🔴 AC2 的**列规则表逐行**都有对应测试；「更新时售价与库存被忽略」有独立用例
- [ ] 🔴 AC3 的四道闸（content-type / 扩展名 / 5MB / 500 行）**服务端显式校验**，六条用例俱在；表头按文字校验
- [ ] 金额非法一律拒收，无任何四舍五入路径；新商品恒 `is_active=false`
- [ ] 执行期 `REQUIRES_NEW` 经 self-proxy 生效，有测试证明一行失败不拖垮整份
- [ ] 启动重扫用 `@Async` + `ApplicationReadyEvent`，**零中间件、零新依赖**
- [ ] 结果文件在图片阶段全部终结后才生成，下载走预签名
- [ ] 权限码 5 处改满，`AdminPermissionsTest` 断言数已从 72 改为 73，`AdminPermissionWiringTest` 绿
- [ ] `ShopImportColumnSpec` 是唯一事实源，7-7 / 8-4 已确认同一份
- [ ] 迁移用时间戳号且 CI 检查通过；`mvn -B clean package` 全绿
- [ ] Completion Notes 已写「L1/L2 待本地验收」+ 列定稿结论 + AB-19A 登记
