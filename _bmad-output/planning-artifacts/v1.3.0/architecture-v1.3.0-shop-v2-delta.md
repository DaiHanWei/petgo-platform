---
stepsCompleted: [1, 2, 3, 4, 5, 6]
lastStep: 6
status: 'draft'
workflowType: 'architecture-delta'
project_name: 'TailTopia'
version: 'v1.3.0'
theme: 'shop-v2'
user_name: 'Dai'
date: '2026-09-16'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md
  - _bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-delta.md
  - _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md (origin/feat/1.3.0-ops-ui-refactor)
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
  - _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md
---

# 架构 delta · V1.3.0 · shop-v2（电商板块 V2）

> **基线**：`planning-artifacts/architecture.md`（V1 总纲）+ `v1.4.0/architecture-v1.4.0-delta.md`（电商一期，**已全量落地**）+ `v1.3.0/architecture-v1.3.0-admin-delta.md`（后台 UI 重构，本主题后台部分叠加其上）。
> **只写增量**：本文不重复基线已定的分层、命名、错误规范、异步范式；凡未提及处一律沿用基线。
> **需求来源**：`PRD-v1.3.0-shop-v2.md`（SHOP-FR-n / SHOP-NFR-n），决策以 `决策日志-shop-v2.md`（SD-1~18）为准。
> **本文决策编号**：`AD-S<n>`（shop-v2 architecture decision）。电商一期已占用 `AD-1~AD-13`，admin 主题占用 `AB-*`，勿混。

---

## 0 · 前置事实（写码前必须知道）

| # | 事实 | 影响 |
|---|---|---|
| F-1 | **电商一期是已上线代码，不是规划稿**。`com.tailtopia.shop/{address,cart,order,returns,review,repurchase}` + `admin/shop/` 均在树里；v1.4.0 delta §4 的 `V101–V139` 号段**已作废**，实存 21 支时间戳迁移（`V20260817_1154` 起） | 本 delta 全部是**在已实现之上的增量**，不得重新设计既有结构 |
| F-2 | Flyway 走 **E7 时间戳制** `V<yyyyMMdd_HHmm>__<snake>.sql`；已被任何环境应用过的迁移绝不可改；改 CHECK 必须 `DROP+ADD` 重列全集，值取自当前树最后一次重建 | 本 delta 所有新迁移按此；`ck_shop_products_category` 的处置见 AD-S1 |
| F-3 | **禁 MQ / 分布式锁 / 通用缓存 / 任何新中间件**；异步只能 `@Async` + DB 状态机 + `@Scheduled`；事件监听 `AFTER_COMMIT` + `REQUIRES_NEW` | 导入任务、图片转存、Lark 提醒一律按此（AD-S4、AD-S9） |
| F-4 | 库存全部是**条件原子写**：`SkuInventoryRepository.lock/release/commit/restock/damage/stocktakeTo`，后者对前值做 CAS | 批量改库存复用 `stocktakeTo`（AD-S5），不新造扣减路径 |
| F-5 | 资金三条硬线：`ck_payment_intents_mixed_shape`（coin+cash=amount 库级强制）· AD-2 整数累计法 · **PawCoin 段只退 PawCoin** | 本 delta 不碰退款执行链路（退货整体移出本版，SD-5） |
| F-6 | 对外标识 = 不可枚举 token（`ShopTokenGenerator`：SecureRandom + Base62 × 22）；但展示号 `OrderDisplayNo = TOKO-yyyyMMdd-%06d` 用自增 id、**可枚举、从不落库** | SHOP-FR-30 要改的正是后者（AD-S3） |
| F-7 | 对外契约同改（C5）：后端 record + App data DTO + 契约 test，缺一 PR 不绿。⚠️ **C5 原文写「四处」含 App mock，但 mock 子系统已于 `8e85b40d` 整体删除，实为三处** | 本 delta 所有契约变更条目均标注 |
| F-8 | 后台侧必须遵守 admin delta：五套模板 A~E + htmx 局部更新 · **AB-19A「零端点变更」**（详情 GET 按 `HX-Request` 返抽屉，不新增 `…/drawer`）· 写操作三件套 `@PreAuthorize` + `AdminAuditService.record` + 三语 key | 本 delta 后台部分逐条标注模板归属；新增端点按 AD-S13 登记为 AB-19A 的新例外 |
| F-9 | 权限码 `AdminPermissions` 现 **72 个**（本分支），admin 主题合入后为 74 并新增 `admin_roles` / `admin_role_permissions` 与 4 个预置角色（**本分支两表尚不存在**） | 新权限码要改的地方见 AD-S13（**实为 6 道关卡，非 5 处**） |
| F-10 | **服务端不剥 EXIF**：字节原样进 OSS，剥离靠投递期 `x-oss-process`，且只施用于 5 处，shop 图片路径全未套（E4 只做了一半） | 外链转存路径必须自己剥（AD-S4） |
| F-11 | 客服号硬编码**实为 2 处**（`customer_service_sheet.dart:12`、`AuthService.java:41`），深链尚不存在；**无任何通用配置表可挂它**（`platform_config` 只有 pricing / pawcoin / topup_tiers） | 需新建配置载体（AD-S8） |
| F-12 | `feedback_tickets.related_order_id` **nullable、无 FK、只认问诊单**；用户建单路径 Flutter 从不传 token ⇒ 恒为 NULL，唯一活路径是后台 `link-order` | SHOP-FR-25 是「新建能力」而非「扩展」（AD-S7） |

---

## 1 · 范围与批次

| 批次 | 需求 | 架构落点 | 前置 |
|---|---|---|---|
| **第一批**（随 V1.3.0） | SHOP-FR-01~05、24~31、35 | App 端 + `shop/`、`pay/`、`support/`、`shared/analytics`、`shared/config` | 无 |
| **第二批**（Epic 10 后） | SHOP-FR-06~23 | `admin/shop/` + `shop/` 服务层 | admin 主题 Epic 10 完成；冻结时未完成则整组顺延（SD-16） |

> **与 admin Epic 10 的边界**：Epic 10 负责**把既有 17 个电商后台页迁到模板 A~E + htmx**，不加能力；本主题在迁好的页面上**加能力**。因此第二批的每个 story 都以「目标页已按 Epic 10 重构完成」为前置条件；若先行实施，视为在旧页面上改，Epic 10 迁移时会整体返工。

---

## 2 · 数据架构增量

### AD-S1 · 品类可配置（SHOP-FR-19 / 18 / 13，SD-12、SD-15）

**决策：枚举改表，编码与显示名分离。**

新表 `shop_categories`：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | |
| `code` | varchar(24) **UNIQUE NOT NULL** | 稳定编码，创建后不可改（`updatable=false`）。存量 = `MAKANAN` / `OBAT_VITAMIN` / `CAMILAN` / `PERAWATAN` |
| `name_id` / `name_en` | varchar(40) NOT NULL | 印尼语 / 英语显示名（SD-15） |
| `sort_weight` | int NOT NULL DEFAULT 0 | |
| `is_active` | boolean NOT NULL DEFAULT true | 停用不删除 |
| `created_at` / `updated_at` | timestamptz | |

**迁移三步（同一支迁移内）**：① 建表 + 灌入四条存量（`code` 取现枚举值，`name_id` / `name_en` 取现 ARB 文案）；② `ALTER TABLE shop_products DROP CONSTRAINT ck_shop_products_category`；③ `ADD CONSTRAINT fk_shop_products_category FOREIGN KEY (category) REFERENCES shop_categories(code)`。
⚠️ **用外键取代 CHECK**，不再重列值集合 —— 这正是为了根除「重建 CHECK 时照着过期列表抄」的事故模式（CLAUDE.md 纪律 5）。`shop_products.category` 列类型与列名不变，存量数据零改动。

**Java 侧**：
- `ProductCategory` 枚举**退出持久层与 DTO**：`ShopProduct.category` 由枚举改为 `String categoryCode`。枚举类保留为 `@Deprecated` 的存量码常量持有者（供迁移期引用），新代码不得再新增值。
- 新增 `shop/category/{domain,repository,service,dto}`：`ShopCategory` 实体 + `ShopCategoryService`（缓存？**不缓存** —— 四到十几行的表，每次查库，禁通用缓存层，F-3）。
- **物种不动**（SD-12）：`Species` 仍是枚举，`ck_shop_products_species` 保留。

**对外契约（C5 同改，实为三处）**：
- 新端点 `GET /api/v1/shop/categories` → `[{code, name, sortWeight}]`，`name` 由后端按 `Accept-Language` 选 `name_id` / `name_en`（**只下发一个 name，不下发双语对象**，避免 App 做语言选择逻辑）。
- 商品 DTO 中 `category` 字段语义不变（仍是 code），App 显示名改从上述端点取。
- 🔴 **这是对「App 不渲染后端显示串、按 code 本地化」模型的有意例外**（AD-S1-E）：理由是运营新建的品类不可能预先存在于 ARB。**例外仅限品类名**，不得扩散到其它文案。ARB 中现有四个品类名保留，作为端点不可达时的兜底。

**停用语义（SD-15）**：`is_active=false` ⇒ ① `GET /shop/products` 与搜索过滤掉该品类商品；② 首页不出该品类入口；③ 复购卡与推荐区过滤；④ **购物车中该品类的行标记为失效**（复用既有 `CartLine.invalidReason`，新增常量 `REASON_CATEGORY_DISABLED`），结算时进 `unavailableLines`、不可提交；⑤ **历史订单照常显示**（订单行是快照，不查品类表）。

**老版本 App**（SHOP-NFR-04）：分类入口是 App 内硬编码四项 ⇒ 新品类不显示入口，其商品在「全部」列表照常出现；`category` 是未知 code 时 App 解析为 null、不崩溃（对应基线纪律「未知枚举值降级到最保守档」）。

### AD-S2 · SPU / SKU 编号（SHOP-FR-06，SD-8）

**决策：系统生成、序列驱动、仅内部可见。**

- `shop_products.spu_no` varchar(16) UNIQUE NOT NULL；`shop_skus.sku_no` varchar(24) UNIQUE NOT NULL。
- 生成：专用序列 `seq_shop_spu_no`，格式 `SPU-%06d`；SKU 为 `<spu_no>-%02d`（同商品内序号，来自该商品现有 SKU 数 + 1，落库唯一索引兜底）。**不用主键 id**（与 AD-S3 的理由一致：不给外部推算余地，即便当前不外露）。
- **运营不可改**：实体字段 `updatable=false`，后台表单只读展示。
- **存量回填**：同一支迁移内按 `id` 升序回填，序列起点设为回填后的最大值 + 1。
- **不对外**：不进任何 `/api/v1/**` 面向用户的 DTO；契约测试断言其缺席（比照 `SupportTicketViewContractTest` 的禁字段范式）。

### AD-S3 · 电商订单号改为不可推算（SHOP-FR-29 / 30）

**决策：落库，保留 `TOKO` 前缀与日期段，序号段改随机。**

- `shop_orders.display_no` varchar(32) UNIQUE NOT NULL，格式 **`TOKO-yyyyMMdd-XXXXXX`**，`XXXXXX` = Crockford Base32 6 位随机（`SecureRandom`，去除易混字符），日期取下单时刻 WIB（与既有 `OrderDisplayNo` 的时区口径一致）。冲突时重试 ≤5 次，唯一索引兜底。
- `shop_orders.legacy_display_no` varchar(32) NULL：**回填存量订单的旧号**（`TOKO-yyyyMMdd-%06d`），供后台按旧号搜索。新订单该列为 null。
- App 一律只显示 `display_no`；订单详情与列表统一（SHOP-FR-29 收口「一单两号」）。
- `OrderDisplayNo` 工具类**保留不动**，继续服务问诊 / AI / 充值三种前缀（本版不改，PRD §1.4 已登记）。电商分支改为读库。
- 后台订单搜索同时命中 `display_no` 与 `legacy_display_no`（SHOP-FR-13）。
- 对账（AB-13D）依赖 `TOKO` 前缀区分实物收入 ⇒ **前缀必须保留**，已在格式中体现。

### AD-S4 · Excel 导入与外链图片转存（SHOP-FR-07 / 08，SD-9、SD-14）

**决策：导入是一个 DB 状态机驱动的异步任务，不是一次请求。**

新表：

| 表 | 关键列 |
|---|---|
| `shop_import_jobs` | `id` · `public_token`(22 Base62) · `status` varchar(16)（`PENDING`/`RUNNING`/`DONE`/`PARTIAL_FAILED`/`FAILED`）· `total_rows` · `success_rows` · `failed_rows` · `result_object_key`（结果文件，生成后才填）· `operator_account_id` · `retry_count` · `created_at`/`updated_at` |
| `shop_import_rows` | `id` · `job_id` FK CASCADE · `row_no` · `raw_json` jsonb · `error_message` varchar(500) · `product_id` / `sku_id`（成功时回填） |

- 执行：`@Async` + 启动重扫 —— ⚠️ **2026-09-16 更正：`RetryScanner` 类在本仓不存在**，真实范式是 `TriageTaskScanner`（`@Async` + `@EventListener(ApplicationReadyEvent.class)`）。**禁引入调度中间件**（F-3）。
- **列规则（SD-14）**，落为 `ShopImportColumnSpec` 单一事实源，模板生成与解析共用：

| 列 | 新建 | 更新 | 空值语义（更新时） |
|---|---|---|---|
| SPU 编号 / SKU 编号 | 留空＝新建 | 填已有＝更新；填不存在＝该行报错 | — |
| 名称 / 品牌 / 品类 / 物种 / 详情 / 规格名 | ✅ | ✅ | 保留原值 |
| 售价 / 初始库存 | ✅ | **忽略**（走 SHOP-FR-15 / 17） | — |
| 图片地址（多个逗号分隔） | ✅ | ✅（填写即整体替换） | 保留原图 |
| 药品注册号 | ✅ | ✅ | 保留原值 |

- **校验分层沿用 `SeedBatchValidator` 范式**：解析期 fail-fast（文件非法 / 零数据行）→ 落行期逐行收集错误 → 行仍落库、错误挂行上。金额列非法**拒收不四舍五入**。
- **事务粒度**：解析在事务外；`appendRows` 整份原子；**执行阶段逐行 `REQUIRES_NEW`**（比照 `SeedBatchPublishService`），一行失败不拖垮整份。
- **限额（SHOP-NFR-03）**：≤500 行、≤5MB，超限整体拒绝。⚠️ 既有 `SeedBatchExcelService.parse` 无行数上限、不校验 content-type —— 本 delta 的解析器**不得复制这个缺口**，须显式校验。
- **图片转存 `ShopRemoteImageFetcher`（新，`shared/media`）**，五道闸：
  1. **协议与主机**：仅 `https`，主机须在白名单（OD-13 定范围；配置项，非硬编码）。
  2. **SSRF 防护**：解析后的 IP 不得落在私网 / 回环 / 链路本地 / 元数据地址段；禁跟随跳转到白名单外主机。⚠️ 既有 `ImageSizeBackfillService.measure` 是**反例**（无 scheme/host 校验），勿参照。
  3. **大小**：边读边计数，超过 10MB 立即中断 —— **不得先全量下载再判**（`LarkContentClient` 的现状是反例）。
  4. **类型**：魔数校验 jpg/png/webp，不信 `Content-Type`。⚠️ **2026-09-16 更正**：既有 `looksLikeImage` **含 GIF**，照抄会静默放宽白名单 —— 必须去掉 GIF 分支。
  5. **EXIF 剥离**：入库前以 `ImageIO` 重编码为 jpg/png 后再上传（F-10：服务端无剥离能力，投递期 `x-oss-process` 未覆盖 shop 路径）。同时测出宽高写入 `main_image_w/h`。
- objectKey 沿用既有约定 —— ⚠️ **2026-09-16 更正**：完整形态是 **`<环境前缀>public/shop-product/<UUID>.<ext>`**（staging 前缀为 `stag/`），漏掉前缀会让 staging 图片写进生产命名空间。

### AD-S5 · 批量写统一规格（SHOP-FR-15~18，SHOP-NFR-02）

**决策：用条件写实现「预览即契约」，不引入乐观锁列，不引入分布式锁。**

| 操作 | 条件写守卫 | 实现 |
|---|---|---|
| 批量改价 | `WHERE price = :expectedPrice` | 预览时记录每行现价，提交时带上；影响 0 行 ⇒ 整批回滚 |
| 批量改库存 | 复用 `SkuInventoryRepository.stocktakeTo(skuId, counted, expectedBefore)` 的 CAS | 天然满足「预览后被改即拒」；**设的是在手量，`locked` 不变**，CAS 的 `i.locked <= :counted` 守卫防止可售量为负。<br>🔴 **2026-09-16 关键更正**：**不能循环调既有的四参 `stocktake`** —— 它内部自己重读前值，会让 CAS 形同虚设、「预览即契约」变成假保护。须新增带 `expectedBefore` 的重载。 |
| 批量上下架 | `WHERE is_active = :expectedActive` | 同上 |
| 批量调品类 | `WHERE category = :expectedCategory` | 目标品类须 `is_active=true` |

- **事务语义**：上述四类**整批一个事务，全成或全不成**（与导入的逐行独立相反，因为它们是「一次决策」而非「一批数据」）。
- **审计**：逐行 `AdminAuditService.record`（SHOP-NFR-02 明令不得只记一条汇总）。批量 100 行 = 100 条审计，可接受。
- **二次确认**：走后台模板的确认态，不是浏览器 `confirm()`。
- **单次上限 100 行**（SHOP-FR-15 `[ASSUMPTION]`），服务层强校验。

### AD-S6 · 购物车部分结算（SHOP-FR-04，SD-6）

- `shop_cart_items.selected` boolean NOT NULL DEFAULT true。
- 端点：`PUT /api/v1/me/cart/items/{skuToken}/selected?selected=true|false` + `PUT /api/v1/me/cart/selection?selected=true|false`（全选 / 全不选）。沿用既有购物车「query param、无请求体 DTO」的风格。
- `GET /checkout` 与 `POST /shop-orders` **只取 `selected=true` 且有效的行**；全不选时 422。
- `CartView` 增 `selected`（行级）与 `selectedSubtotal`、`selectedCount`；`subtotal` 语义不变（全车合计），避免老版本读到变味的字段。
- **免运门槛、PawCoin 抵扣上限、库存锁定全部按选中行计算**（SD-6）——即 `CheckoutService.preview/placeOrder` 的输入集合从「整车」换成「选中集」，其余算式不动。
- **老版本兼容**（SHOP-NFR-04）：老版本不调选择端点、所有行默认 `selected=true` ⇒ 行为与今日一致。
- 契约变更同改（C5，实为三处）。

### AD-S7 · 工单关联电商订单（SHOP-FR-25）

- `feedback_tickets.related_order_type` varchar(16) NOT NULL DEFAULT `'CONSULT'`，值域 `CONSULT` / `SHOP`；存量按现状回填 `CONSULT`。`related_order_id` 的解释依赖它。
- ⚠️ **不加跨表 FK**（一列指两表），用类型列 + 服务层解析；这是本仓「两类订单 id 都是自增 bigint、会撞号」的唯一安全解法。
- `SupportTicketService.resolveRelatedOrder` 扩展为**先按 token 找问诊单、再找电商单**，仍保持归属校验与「找不到静默 null」的宽松口径（OPEN-1 不变）。
- 🔴 **用户侧建单路径今日恒为 NULL**（F-12）：Flutter `ticket_compose_page` 未传 `relatedOrderToken`。本主题必须补前端传参 + 订单选择入口，否则后端改了也没人用。
- **解耦**：`AdminTicketRefundService.linkOrder` 拆为 `linkConsultOrder`（保留退款审批联动）与 `linkShopOrder`（**不触发任何退款流程**）。后台「关联订单」抽屉按类型分流。

### AD-S8 · 客服联系方式配置化（SHOP-FR-26，SD-10）

**决策：在既有 `platform_config` 体系中新增单行表，不建通用 key-value 表。**

- 新表 `support_contact_config`（单行 `id=1`，CHECK 单例，与 `pricing_config` 同范式）：`whatsapp_number` varchar(20) · `email` varchar(120) · `created_at` / `updated_at`。
- 写操作走 admin 配置页，记 `config_change_logs` + 审计哈希链（与 V78 既有配置一致）。
- 读：`SupportContactProvider`（`shared/config`），三处消费统一：
  1. App 客服弹窗（现 `customer_service_sheet.dart:12` 硬编码）；
  2. 订单详情 / 工单页的 WhatsApp 深链（新建）；
  3. 后端「账号已停用」提示文案（现 `AuthService.java:41` 硬编码）。
- 下发：`GET /api/v1/support/contact`（**免鉴权**，内容非敏感），App 启动后取一次、失败时回退到本地常量。
- 深链形态：`https://wa.me/<E.164>?text=<urlencoded>`；号码归一化复用既有 `IndonesiaPhone`（`08xx` → `+62…`）。预填文案走 ARB（三语），**只含订单号**（SHOP-NFR-05）。
- 打开方式：`url_launcher` 外部应用模式；失败时提示 + 提供「复制号码」（wa.me 在未安装时会落到浏览器页，不是白屏）。

### AD-S9 · 支付可感知与支付监控（SHOP-FR-01 / 02，SD-1）

**两个独立问题，分开解。**

**(a) App 感知不到「网关拒付」** —— 根因：网关拒付只改 `payment_intents`，不改订单，App 轮询的是订单状态。
- 订单详情 DTO 增 `paymentStatus`（透传支付单状态）与 `paymentFailureCategory` 枚举：`GATEWAY_DECLINED` / `EXPIRED` / `USER_CANCELLED` / `null`。
- 分类来源：🔴 **先判 status、再判 reason**（2026-09-16 代码核实更正）—— `PaymentStatus` 有独立的 `EXPIRED` 终态，电商意图带 ttl，`PaymentIntentExpiryScanner` 每 60s 扫且不按 purpose 过滤，会先把超时意图置 `EXPIRED` 且 `markExpired(null)` **不写 reason**；随后 `failByToken` 因 `isTerminal()` 短路，reason 永远写不进去。若照「其余 → GATEWAY_DECLINED」映射，**超时会被稳定误判为网关拒付并给出不该有的重试入口**。正确顺序：`status==EXPIRED` → `EXPIRED`；否则读 `gateway_meta->>'reason'`（`TIMEOUT` → `EXPIRED`、`USER_CANCEL`/`CANCELLED` → `USER_CANCELLED`、其余 → `GATEWAY_DECLINED`）。**分类逻辑落在后端一处** `PaymentFailureCategory.of(intent)`，不让 App 解析 meta（meta 可能含 PII）。
- App 轮询改为读订单详情中的这两个字段；三种结局的处置见 SHOP-FR-01。
- 🔴 **`QrPaymentSheet` 是共用组件**：本次改造须**走参数而非改默认行为**，其余业务线表现不变，回归用例写进 story。⚠️ **2026-09-16 代码核实更正**：实际 **4 个调用点 / 3 条业务线**（电商、AI 解锁、高清身份证×2）；**问诊（`vet_timed_pay_page.dart`）与充值（`recharge_page.dart`）各自内联 `QrImageView` + 自轮询，不走本组件**。

**(b) 监控口径**
- **服务端**：新增 `ShopPaymentAnalyticsListener`（`shop/order/service`），`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`，事件名固定集合：`shop_payment_intent_created` / `shop_payment_paid` / `shop_payment_declined` / `shop_payment_expired` / `shop_payment_user_cancelled`。属性只允许**枚举与数值**（订单金额、失败类别、支付渠道），`distinctId` 走既有 `AnalyticsDistinctId`。
- 🔴 **后端目前无事件名白名单、无属性脱敏器**（契约只在 javadoc 里）。本 delta 在 `shared/analytics` 补 `AnalyticsEventGuard`：事件名白名单 + 属性键白名单，违反即丢弃并告警。这是新增的跨切面护栏，**须做变异验证**（删掉守卫应让测试变红）。
- **环境标记**：`PostHogAnalyticsClient.capture` 统一注入 `app_env`（env `APP_ENV`，值 `prod` / `stag` / `dev`）。影响既有三个上报点，属预期内的口径统一 —— 否则 staging 事件会混进生产漏斗（SHOP-FR-02 验收要求）。
- **App 端**：新增支付事件走既有 `Analytics` 门面与 scrub；按钮类事件须进 `_allowedButtonIds` 白名单。
- **验收环境**：支付模拟器只在 `stag` 分支（stag 专属工具，合并须排除），故三支验收在 staging 跑。

### AD-S10 · SKU 级图片（SHOP-FR-12）

- `shop_skus.main_image_key` **varchar(255)** NULL + `main_image_w` / `main_image_h` int NULL（与商品级同构 —— ⚠️ 2026-09-16 更正：商品级实际是 255 不是 200）。
- App 商品详情选中规格时主图切换；规格无图回退商品主图。
- 上传复用既有后台 multipart 路径与 `folder=shop-product`。

### AD-S11 · banner 多图与跳转（SHOP-FR-22）

- `shop_banners` 加列：`target_type` varchar(16) NULL（`NONE` / `INTERNAL` / `EXTERNAL`）· `target_value` varchar(300) NULL。
- **多图**：现有部分索引 `ix_shop_banners_active_pick` 与「同一时间只展示一张」的表注释须**连注释一起改**（电商一期把该口径写死在建表注释与实体注释里；ACT-D 已登记留痕）。
- 站内跳转按 path 白名单（App 侧 `go_router` 可达路由）；站外按域名白名单（配置项）。站内目标失效 ⇒ 回首页并静默上报。
- 顶栏三段遮罩对比度**每张图都验**（电商一期基线 9.09:1）。

### AD-S12 · 其它数据增量

| 需求 | 增量 |
|---|---|
| SHOP-FR-21 药品注册号 | `shop_skus.drug_reg_no` varchar(40) NULL；**有效期不做**（SD-18，批次属性） |
| SHOP-FR-28 售罄数口径 | 不改表；修查询（去掉 `is_active` 限定、改 LEFT JOIN 覆盖无库存行），单测断言口径 |
| SHOP-FR-09 / 23 导出 | 不建表；POI 导出复用 `AdminPaymentExportService` 范式；**单元格公式转义**（`=`/`+`/`-`/`@` 开头前置 `'`），SHOP-NFR-01 |
| SHOP-FR-05 隐藏退货入口 | **零后端改动**，纯 App |
| SHOP-FR-35 售后兜底 | **零代码**：后台把 `pawcoin_config.premium_rate` 与补偿溢价配置置 0 + 运营流程文档 |

---

## 3 · 后台（admin）增量

> 全部在 admin 主题 Epic 10 迁移后的页面上叠加（AD-S13 之外不新增端点）。

> 🔴 **2026-09-16 更正**：本表原先的模板字母与 admin 主题的定义**对不上**。admin 的真实定义是 **A=处置工作台 · B=管理列表 · C=报表 · D=配置卡 · E=分步**，各页归属以 admin 主题 Story 10.x 为准，下表已按其修正。

| 页面 | 模板（admin 定义） | 增量 |
|---|---|---|
| 商品列表 | **B**（管理列表） | 搜索（名称 / SPU / SKU 编号）、分页、批量勾选工具条 |
| 商品表单 | **D**（配置卡） | 新建态 SKU 区（SHOP-FR-11）、图片设为主图与替换（SHOP-FR-10）、SKU 图片与注册号、即时校验 |
| 库存列表 | **B** | 分页 / 搜索 / 筛选、批量盘点 |
| 订单列表 | **B** | 五维搜索、下单人昵称列、导出 |
| 对账 | **C**（报表）→ 扩为可下钻 | 汇总行点击进明细抽屉（`HX-Request` 分支，不新增 `…/drawer`）、导出 |
| 品类管理 | **B + D**（新页） | 新增 / 改名 / 停用 / 排序 |
| 导入工作台 | 新页，比照 `seed-batch-workspace` | 上传 → 任务状态轮询 → 结果文件下载 |
| banner | **B** | 多图、跳转配置 |
| 库存周转 / 毛利看板 | **C** | 售罄数口径与标注（SHOP-FR-28） |

🔴 **模板 D/E 与所有整页表单维持现状 PRG，不与 htmx 混用**（admin AD-9 原文）。商品表单页是模板 D ⇒ **SHOP-FR-20 的即时校验不能想当然用 htmx 422**，须按 PRG 分支实现或先与 admin 主题对齐，见 §9 更正表第 4 条。

🔴 **导出一律走 admin 主题 AD-10 的 `AdminExportWriter`**（xlsx 走 POI、CSV 走 RFC 4180 且已含前导 `=` 防公式注入），**禁止各页自拼字符串**。本 delta 原先要求各导出自行实现公式转义，改为复用它；若 Epic 10 尚未交付该类，则在本主题内先实现同名工具并在 Epic 10 合入时收敛为一份。

### AD-S13 · 新权限码与「零端点变更」例外

**新增 6 个权限码**（`shop.*` 从 10 增至 16）：

| 码 | 用途 |
|---|---|
| `shop.category_manage` | 品类增改停 |
| `shop.product_import` | Excel 导入 / 导出模板 |
| `shop.bulk_edit` | 批量改价 / 改库存 / 上下架 / 调品类（**统一一个码，不按操作细分**——四者风险同级，细分只增加配错概率） |
| `shop.order_export` | 订单导出（含 PII） |
| `shop.finance_export` | 对账明细导出 |
| `shop.order_email_search` | 按账号邮箱搜订单（受 OD-1 约束；OD-1 若定为不做，此码不加） |

🔴 **加码要改的地方（2026-09-16 代码核实更正，原写「5 处」不准）**：
1. `AdminPermissions` 常量；
2. **`GROUPS` 里的其中一组**（view 或 edit，**不是两组都加**）—— `ALL` 由 `GROUPS` 派生，不进组 ＝ 不在 `ALL` ＝ `isValid` 拒绝，且**编译不报错**；
3. **4 个** properties 文件的 `perm.*`（`messages.properties` 默认包 + `_zh_CN` + `_en` + `_id`），⚠️ 守门测试**只校验 zh_CN 与 en**，漏写默认包或印尼包**不会变红**，须人工核对；
4. `AdminPermissionsTest.listStableSize` 断言数；
5. `AdminPermissionWiringTest` 是**双向守门**：不只要求代码里的 `hasAuthority` 字面量 ∈ ALL，还要求**每个 ALL 码至少有一处闸门** ⇒ 加码与写页面必须**同批完成**，中间态测试必红；
6. admin 主题预置角色的默认授予 —— ⚠️ **本分支上 `admin_roles` / `admin_role_permissions` 两张表尚不存在**（admin 主题产物），预置角色目前是 Java 枚举。**不要为了凑满这一处而自己造表或造迁移**（会与 admin 撞车），登记为待办、待 admin 合入后补。默认授予建议：`category_manage` / `product_import` / `bulk_edit` → 运营专员；`order_export` / `order_email_search` → 客服 + 财务；`finance_export` → 财务。**不默认授予任何含 PII 的码给运营专员**。

**AB-19A「零端点变更」新增例外**（须在 admin 主题登记）：品类管理、导入工作台、批量操作提交、两个导出 —— 这些是**新能力**，不存在可复用的既有端点。对账下钻**不是**例外（走既有详情 GET 的 `HX-Request` 分支）。

---

## 4 · 迁移清单（时间戳号，实施时取创建时刻）

| # | 内容 | 触及既有对象 |
|---|---|---|
| M1 | `shop_categories` 建表 + 灌存量 + 删 `ck_shop_products_category` + 加 FK | 🔴 改既有约束 |
| M2 | `shop_products.spu_no` / `shop_skus.sku_no` + 序列 + 回填 | 加列 |
| M3 | `shop_orders.display_no` / `legacy_display_no` + 回填 | 加列 |
| M4 | `shop_cart_items.selected` | 加列 |
| M5 | `shop_import_jobs` / `shop_import_rows` | 建表 |
| M6 | `shop_skus.main_image_key` / `_w` / `_h` / `drug_reg_no` | 加列 |
| M7 | `shop_banners.target_type` / `target_value` + 改部分索引与注释 | 🔴 改既有索引与注释 |
| M8 | `feedback_tickets.related_order_type` + 回填 | 加列 |
| M9 | `support_contact_config` 建表 + 灌当前号码 | 建表 |
| M10 | 新权限码的预置角色授予种子（admin 主题合入后） | 依赖 admin |

**纪律**：提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`；打包一律 `mvn -B clean package`（改迁移后旧 class 残留会让 Flyway 报重复版本）。

---

## 5 · 契约变更清单（C5 同改 —— 后端 record + App DTO + 契约 test 三处；C5 原文的 App mock 一腿已随 `8e85b40d` 删除）

| # | 变更 | 老版本影响 |
|---|---|---|
| K1 | `GET /api/v1/shop/categories`（新） | 无（新增） |
| K2 | `CartView` 增 `selected` / `selectedSubtotal` / `selectedCount`；新增选择端点 | 无（默认全选） |
| K3 | 订单详情增 `paymentStatus` / `paymentFailureCategory` | 无（新增字段） |
| K4 | 订单 `displayNo` 语义变更（不可推算、落库） | 老版本显示新号，可读性不变 |
| K5 | `GET /api/v1/shop/products` 分页 | 🔴 **老版本不带分页参数时必须仍返全量**，直到最低支持版本升级 |
| K6 | `GET /api/v1/support/contact`（新） | 无 |
| K7 | 建单请求真正使用 `relatedOrderToken` | 无（字段早已存在） |
| K8 | **banner 由单张改多张** | 🔴 **2026-09-16 补**：现 App 端按**对象**解析该端点，改成数组会让**所有存量 App 的首页 banner 静默消失**（解析异常被「失败当作没有」吞掉，不报错、无人发现）。⇒ **老端点原样保留返回单张，多图另开复数端点**；老版本继续看到权重最高的那一张 |
| K9 | SKU 图片字段进商品详情 DTO | 无（新增字段） |

---

## 6 · 风险与护栏

| # | 风险 | 护栏 |
|---|---|---|
| G-1 | 品类改表触碰 `shop_products` 既有约束，改错即启动失败 | 用 FK 取代 CHECK，不重列值集；L1 必须在 scratch 库跑通 `validate` |
| G-2 | 外链抓图引入 SSRF 面 | AD-S4 五道闸；私网 IP 段黑名单须有单测 |
| G-3 | 批量写误操作造成资损 / 超卖 | AD-S5 条件写 + 整批事务 + 逐条审计；**变异验证**：去掉 `expectedPrice` 守卫应让测试变红 |
| G-4 | 支付面板改动波及其它四条链路 | AD-S9 参数化，回归用例覆盖四条链路 |
| G-5 | 埋点带出 PII | `AnalyticsEventGuard` 属性白名单 + App 侧既有 scrub；**变异验证** |
| G-6 | 第二批依赖 admin Epic 10，延期即顺延 | SD-16 发布规则；第二批 story 前置条件写明 |
| G-7 | 订单号回填涉及存量数据 | 回填在迁移内完成 + 唯一索引；旧号保留在 `legacy_display_no`，后台可搜 |
| G-8 | 注销级联未闭合（OQ-41） | **本 delta 新增的表均不含用户 PII**（导入任务挂 admin 账号、配置表无用户数据）⇒ 不新增注销级联面；若后续加列含用户数据须重评 |

---

## 7 · 开放项（架构侧）

| # | 事项 | 何时定 |
|---|---|---|
| OD-9 | 订单号随机段长度与字符集（本文取 Base32 × 6；碰撞概率与可读性的权衡） | 实施 M3 前 |
| OD-13 | 外链图片来源白名单范围与维护方式 | 实施 AD-S4 前 |
| OD-1 | 邮箱搜订单做不做 —— 决定 `shop.order_email_search` 是否加码 | 订单页 story 前 |
| OD-12 | 评价提交端点去留（推荐关闭） | 架构评审时一并定 |

---

## 8 · 实施顺序建议

1. **第一批 · 后端**：AD-S8 客服配置 → AD-S9(a) 支付状态下发 + (b) 埋点护栏与环境标记 → AD-S7 工单关联 → AD-S3 订单号（M3 回填）→ AD-S6 购物车选择
2. **第一批 · App**：支付三态处置 → 隐藏退货入口 → 购物车勾选 → 深链与客服号统一 → 工单选订单
3. **第二批**（Epic 10 后）：AD-S1 品类 → AD-S2 编号 → AD-S4 导入导出 → AD-S5 批量写 → AD-S10/11/12 图片、banner、口径 → 对账下钻导出

> 顺序依据：AD-S1 与 AD-S2 是 AD-S4 / AD-S5 的数据前置；AD-S9(b) 的环境标记越早越好，否则后续验收数据都是脏的。

---

## 9 · 2026-09-16 写 story 时的代码核实更正（权威）

> 40 个 story 生成过程中逐条 grep 核实，发现本 delta 与代码现状不符之处。**下表结论优先于正文**；正文中易误导的几处已就地改。

| # | 文档原说法 | 代码现状 | 影响 |
|---|---|---|---|
| 1 | 批量改库存「复用盘点原语」 | 既有四参 `stocktake` **内部自己重读前值** | 🔴 循环调它会让 CAS 形同虚设、「预览即契约」成为**假保护**。须加带 `expectedBefore` 的重载（已改 AD-S5） |
| 2 | banner 改多图 | App 端按**对象**解析该端点，失败被「当作没有」吞掉 | 🔴 直接改数组 ⇒ **存量 App 首页 banner 静默消失**。老端点保留、多图另开复数端点（已补 K8） |
| 3 | 后台页面模板字母 | admin 真实定义 A=工作台 B=列表 C=报表 D=配置卡 E=分步 | 🔴 §3 原表**四个页面全标错**，已按 admin Story 10.x 更正 |
| 4 | 即时校验走 htmx 422 | admin AD-9：**模板 D/E 与所有整页表单维持 PRG，不混用**；商品表单页是模板 D | 🔴 SHOP-FR-20 须按 PRG 实现或先与 admin 对齐，不能想当然用 htmx |
| 5 | 加权限码「5 处」 | 实为 **6 道关卡**；`GROUPS` 只加**一组**；properties 是 **4 个文件**且守门只覆盖 zh_CN/en；`AdminPermissionWiringTest` **双向**守门；预置角色两表**本分支不存在** | 已重写 AD-S13。**不要为凑满而自己造表** |
| 6 | 「三语 key」 | `i18n/` 是 **4 个** properties（默认包 + zh_CN + en + id），各 74 个 `perm.*` | 漏写默认包或印尼包**测试不会红**，须人工核对 |
| 7 | 导出各自实现公式转义 | admin AD-10 已定 **`AdminExportWriter`**（含前导 `=` 防注入）；⚠️ 全仓现有导出**一个都没做转义** | 一律复用 AD-10；Epic 10 未交付时先实现同名工具，合入时收敛为一份 |
| 8 | 启动重扫用 `RetryScanner` | **该类不存在**，真实范式是 `TriageTaskScanner` | 已改 AD-S4 |
| 9 | 魔数校验「比照 `looksLikeImage`」 | 该方法**含 GIF** | 照抄＝静默放宽白名单，须去掉 GIF（已改 AD-S4） |
| 10 | objectKey `public/shop-product/…` | 实际带**环境前缀**（staging 为 `stag/`） | 漏掉会让 staging 图写进生产命名空间（已改 AD-S4） |
| 11 | SKU 图片列 varchar(200) | 商品级同构列是 **255** | 已改 AD-S10 |
| 12 | 「品类超过 4 个改横向滑动」需实现 | ✅ **App 侧早已是横向滚动**，与品类数解耦 | SHOP-FR-19 该条降级为「验证 + 换数据源」，**省一块工作量** |
| 13 | `ProductCategory` 枚举可退为废弃常量 | 它被另一模块引用，且有**反射断言测试钉死** | 枚举**不能删**；AD-S1 的「保留」有代码层强制理由 |
| 14 | 品类改表的改造面 | 另有：原生 SQL 里**硬编码品类字面量**、模板调 `枚举.name()`、Controller 用**枚举 `==` 内存过滤**（改 String 后会**静默筛出空列表**） | 三处必须一并改，已写进 6-1 |
| 15 | 加外键 / 加编号列的波及面 | **16 个集成测试**用原生 SQL 插商品 | 是 6-1 / 6-4 的工作量大头 |
| 16 | 服务端校验「8 处」 | 实为 **12 个抛点 / 16 条规则** | 结论不变（服务端已完备），数字更正 |
| 17 | 对账页「12 个汇总数字」 | 实为 **11 字段 + 2 派生 = 13 个数字** | 已写进 9-4 |
| 18 | 「按电话搜索」的三道处置 | 权限位**不走 `@PreAuthorize`**，是渲染门控 + 服务端手写再判 403 两段代码 | 邮箱搜索照抄时别写成注解 |
| 19 | 本仓有批量操作先例可循 | **没有合格先例**：现有三个「批量」只记一条汇总审计、无行数上限、部分失败不回滚 | 7-1 是首个；已标为**反例，禁止照抄** |
| 20 | 8-2 只需遵守命名纪律 | 另有**同类隐患未修**：改商品的端点今天安全只因表单类恰好没有同名字段，而本 story 正要往它上面加字段 | 须一并改名（对外地址不变） |
| 21 | banner 对比度基线 9.09:1 | 该数字是**某一张实测图**的测值；代码里真正门槛是标题 ≥3:1、胶囊 ≥4.5:1 | 「每张图都验」的要求保留，口径待澄清 |
| 22 | 后台规格表单可编辑 | 今天**只能新增、不预填已有规格**（标题却写「新增/编辑」） | 影响 SKU 图片能否补传，8-3 列为实施前确认项 |
| 23 | Epic 10 基建 | 本分支上模板 fragment 与前端脚本**一个都不存在** | 「前置 Epic 10」已具体化为可检查的停手条件 |

**另有两条跨 story 的钉子**：
- 7-7 的「导出→原样导入」闭环会把自家 CDN URL 送进 7-6 的白名单校验 ⇒ **自家 CDN 域名必须在白名单里**，否则闭环测试必红。
- 9-5 并非纯页面 story：5-3 把「可用 / 冻结中」的**查询实现**交接给了它（不加列、不加迁移）。
