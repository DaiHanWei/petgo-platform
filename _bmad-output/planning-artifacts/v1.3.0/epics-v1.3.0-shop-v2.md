---
stepsCompleted: [step-01, step-02, step-03, step-04]
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md
  - _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2-addendum.md
  - _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md
  - _bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md
  - _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md
  - _bmad-output/planning-artifacts/v1.3.0/README.md
theme: shop-v2
scope: V1.3.0 主题 shop-v2 —— 电商板块 V2
---

# TailTopia V1.3.0 · shop-v2 —— Epic 与 Story 拆分

## Overview

本文件是 **V1.3.0 主题 shop-v2（电商板块 V2）** 的 epic 与 story 拆分。同版本 admin / batch-a 主题各自独立编号、独立文件，**不共用 Epic 号段**（README §2）。引用本主题 story 必须带主题名，如「shop-v2 的 1-1」。

**冲突序**：`决策日志-shop-v2.md`（SD-1~18）> `architecture-v1.3.0-shop-v2-delta.md`（AD-S1~S13）> `PRD-v1.3.0-shop-v2.md`（SHOP-FR / SHOP-NFR）。跨 story 契约冲突以 `implementation-artifacts/CROSS-STORY-DECISIONS.md` 为准。

> ⚠️ **本文出现的「Epic 10」一律指 admin 主题的 Epic 10**（电商后台 17 页的模板化重构），不是本主题的 epic。本主题只有 Epic 1~9。

### 批次与前置

| 批次 | Epic | 随版本 | 前置 |
|---|---|---|---|
| **第一批** | Epic 1~5（18 story） | V1.3.0 | 无 |
| **第二批** | Epic 6~9（22 story） | V1.3.0，**代码冻结时 admin Epic 10 未完成则整组顺延**（SD-16） | admin 主题 Epic 10 完成 |

### 验证层级约定

每条 AC 标注所需环境：**L0** 静态（`flutter analyze` / `flutter test` / `mvn -B clean package`，无需 DB）· **L1** 集成（Docker postgres + redis 真跑）· **L2** 端到端（真机 / 模拟器视觉 / 真实第三方）。云端 session 只跑到 L0，L1/L2 留本地并在 Completion Notes 标注。

---

## Requirements Inventory

### Functional Requirements

**SHOP-FR-01 支付结果的用户告知** · 付款面板区分「支付被拒 / 超时未付 / 用户取消订单 / 仅关闭面板」四种结局并分别处置；被拒保留重试、超时不给重试；App 须读支付单状态与失败类别，不能只轮询订单；付款面板为共用组件（**代码核实：4 个调用点 / 3 条业务线** —— 电商、AI 解锁、高清身份证；问诊与充值各自内联实现、不走它），其余业务线表现不得变。

**SHOP-FR-02 支付环节监控（SD-1）** · App 端与服务端支付事件进同一个 PostHog 项目，可拼「进入支付 → 出码 → 成功」漏斗与失败原因分布；服务端事件带环境标记，staging 数据不进生产漏斗；事件属性不含个人信息。已知盲区：「已付款但未收到到账通知」两端都表现为超时。

**SHOP-FR-03 复购卡显示价格** · 复购提醒卡片补价格；无价则整行不渲染。

**SHOP-FR-04 购物车部分结算（SD-6）** · 可勾选部分商品下单；免运门槛、PawCoin 抵扣上限、库存锁定全按选中行计算；默认全选；老版本按整车下单；停用品类的行不可结算。

**SHOP-FR-05 隐藏 App 端退货入口与文案（SD-5）** · 任何订单状态下不出现退货/退款入口与退货窗口文案；只改 App，后端与后台退货功能保留。

**SHOP-FR-35 本版售后兜底流程（SD-13）** · 上线前把「平台责任补偿比例」与「转 PawCoin 激励比例」置 0；运营按既定流程处理取消与退款请求。

**SHOP-FR-06 SPU ID 与 SKU ID（SD-8）** · 系统生成、全局唯一、人可读、运营不可改；仅后台与 Excel 可见，不进任何面向用户的接口；存量补齐。

**SHOP-FR-07 商品批量导入（Excel，SD-9/SD-14）** · 编号列留空＝新建、填已有＝更新、填不存在＝报错；更新时售价与库存列忽略、空单元格保留原值；图片填外部链接，系统下载转存并剥离 EXIF；失败行不丢弃、结果文件可下载；≤500 行 / ≤5MB。

**SHOP-FR-08 商品导出** · 与导入模板同构，支持「导出 → 改 → 再导入」。

**SHOP-FR-09 订单导出** · 按筛选条件导出；独立权限位 + 每次写审计 + 单元格公式转义。

**SHOP-FR-10 图片设为主图与替换** · 一键设为主图、原位替换；满 9 张时仍可替换，不再要求先删后传。

**SHOP-FR-11 新建商品时即可配置 SKU** · 一次保存带多个规格；不做多维规格。

**SHOP-FR-12 SKU 级图片** · 后台规格表单可上传图片；App 选中规格时主图切换，无图回退商品主图。

**SHOP-FR-13 列表分页、搜索、筛选、排序** · 商品页按名称/SPU/SKU 编号搜索 + 分页；库存页分页 + 搜索 + 筛选；订单页五维搜索；排序只做总库存 + 状态；App 商品列表分页且老版本行为不变。

**SHOP-FR-14 订单显示与搜索下单账号信息** · 昵称进列表与详情且可搜；邮箱受 OD-1 约束；已注销显示「已注销用户」；收货人姓名电话地址仍只在详情。

**SHOP-FR-15 批量改价** · 勾选或按当前筛选结果圈定；二次确认 + 预览影响行数与改前改后价格 + 逐条审计；固定价与百分比增减两种，百分比向上取整到 100 IDR，单次 ≤100 行，不允许改到 0。

**SHOP-FR-16 批量上架 / 下架** · 下架只改可见性不动库存，页面显式写给运营看。

**SHOP-FR-17 批量修改库存** · 盘点语义设在手量，锁定量不变，不得写成负数；每行写库存流水；与改价同款确认与审计。

**SHOP-FR-18 批量调整品类** · 依赖 SHOP-FR-19；不可调入已停用品类。

**SHOP-FR-19 品类可配置（SD-12/SD-15）** · 四个存量品类原样迁移、编码不变；编码与显示名分离；显示名填印尼语与英语并由后端下发；只做一级；超过 4 个时首页横向滑动；停用后 App 不展示、购物车该品类不可结算、历史订单照常；老版本 App 不崩溃。

**SHOP-FR-20 必填即时校验与敏感词** · 填写时即时提示，不再整屏提交才被打回；敏感词是否走内容审核待 OD-4。

**SHOP-FR-21 药品 SKU 的注册号（SD-18）** · 驱虫保健类规格可填注册号，纳入导入导出模板；有效期本版不做。

**SHOP-FR-22 banner 多图轮播与跳转** · 多图可点；翻案「单图纯展示」口径须连代码与建表注释一起改；顶栏文字对比度每张图都验；站内站外两类跳转，站外域名白名单、站内失效兜底。

**SHOP-FR-23 对账明细下钻与导出** · 汇总可下钻到逐单明细并导出；汇总与明细加总一致。

**SHOP-FR-24 新订单 Lark 提醒** · 付款成功后运营收到提醒；不得每单一条不加节流；发送失败不影响订单。

**SHOP-FR-25 工单关联电商订单** · 用户侧与后台侧都能关联电商订单并保持归属校验；问诊单与电商单不得串单；后台「关联订单」与问诊退款审批解耦；历史工单不受影响。

**SHOP-FR-26 WhatsApp 深链与客服号统一配置（SD-10）** · 深链预填订单号、可编辑；客服号为后端配置项，深链 / 客服弹窗 / 停用提示三处读同一份；未安装时不白屏；预填不含收件人信息；点击上报埋点。

**SHOP-FR-27 评价原文不进日志** · 请求日志中评价正文打码，只对请求体生效。

**SHOP-FR-28 运营看板口径修正** · 售罄数按「所有未删除规格中可售库存为 0 的数量」计算；售后成本口径标注；PawCoin 余额区分可用与冻结中。

**SHOP-FR-29 订单号统一** · 列表、详情、工单关联、WhatsApp 预填用同一个订单号。

**SHOP-FR-30 订单号不可推算** · 保留 TOKO 前缀，随机段不可推算；新号落库，存量回填；旧号后台仍可搜。

**SHOP-FR-31 请求方法错误不再返回 500** · 返回 405 / 415，格式遵循统一错误响应。

### NonFunctional Requirements

**SHOP-NFR-01 个人信息** · 埋点、审计表、访问日志不带个人信息；审计只记指纹；导出文件对用户可填字段做公式转义。
**SHOP-NFR-02 批量写操作** · 逐条审计；改价与改库存预览 + 二次确认；批量类整批全成或全不成；预览后数据被改即整批拒绝；每类批量操作独立权限位。
**SHOP-NFR-03 量级边界** · 导入 ≤500 行 / ≤5MB；导出 ≤10,000 行；App 商品列表每页 20 条；Lark 提醒 ≤10 分钟送达。
**SHOP-NFR-04 对外接口兼容** · 老版本 App 商品列表仍返全量；下单不带勾选按整车；新增品类对老版本不崩溃。
**SHOP-NFR-05 深链预填内容** · 只允许订单号等非个人信息。
**SHOP-NFR-06 架构护栏** · 不引入新中间件；异步用 `@Async` + DB 状态机；外链抓图防内网访问。

### Additional Requirements（架构 delta）

- **AD-S1** 品类枚举改表：`shop_categories`（code / name_id / name_en / sort_weight / is_active）；**删 `ck_shop_products_category`、用外键取代**，不再重列值集合；`ProductCategory` 枚举退出持久层与 DTO；品类名是 i18n 模型的**有意例外**（AD-S1-E），仅限品类名。
- **AD-S2** `spu_no` / `sku_no` 由专用序列生成，不用主键 id；存量回填；契约测试断言其不进对外 DTO。
- **AD-S3** `shop_orders.display_no` 落库（`TOKO-yyyyMMdd-` + Crockford Base32 6 位随机，冲突重试 ≤5），`legacy_display_no` 存旧号；`OrderDisplayNo` 工具类保留服务其余三种前缀。
- **AD-S4** 导入 = `shop_import_jobs` / `shop_import_rows` + `@Async` + 启动重扫；解析期 fail-fast、落行期逐行收集、执行期逐行 `REQUIRES_NEW`；外链抓图五道闸（https + 主机白名单 + 私网 IP 拦截 + 边读边限大小 + 魔数校验），入库前 `ImageIO` 重编码剥 EXIF 并测宽高。
- **AD-S5** 批量写用条件写实现「预览即契约」：改价 `WHERE price = :expectedPrice`、库存复用 `stocktakeTo` CAS、上下架 `WHERE is_active = :expected`、调品类 `WHERE category = :expected`；四类整批一个事务；逐行审计；单次 ≤100 行。
- **AD-S6** `shop_cart_items.selected`；选择端点走 query param 风格；`CartView` 增 `selected` / `selectedSubtotal` / `selectedCount`，`subtotal` 语义不变。
- **AD-S7** `feedback_tickets.related_order_type`（`CONSULT` / `SHOP`，存量回填 `CONSULT`）；**不加跨表 FK**；`linkOrder` 拆为 `linkConsultOrder` / `linkShopOrder`。
- **AD-S8** `support_contact_config` 单行表（比照 `pricing_config`）+ `GET /api/v1/support/contact`（免鉴权）；号码归一化复用 `IndonesiaPhone`。
- **AD-S9** 订单详情增 `paymentStatus` / `paymentFailureCategory`，分类逻辑落后端 `PaymentFailureCategory.of(intent)`，不让 App 解析 `gateway_meta`；`shared/analytics` 补 `AnalyticsEventGuard`（事件名 + 属性键白名单）；`PostHogAnalyticsClient` 统一注入 `app_env`。
- **AD-S10** `shop_skus.main_image_key` / `_w` / `_h`。
- **AD-S11** `shop_banners.target_type` / `target_value`；改既有部分索引与「只展示一张」的表注释与实体注释。
- **AD-S12** `shop_skus.drug_reg_no`；售罄数改查询；导出公式转义。
- **AD-S13** 新增 6 个 `shop.*` 权限码，**加码必改 5 处**（常量 → 两个 GROUPS 分组 → 四语 `perm.*` → `AdminPermissionsTest` 断言数 → admin 预置角色默认授予与种子迁移）；AB-19A「零端点变更」新增 5 条例外（品类管理、导入工作台、批量提交、两个导出），对账下钻**不是**例外。
- **迁移纪律** · 时间戳版本号；提交前跑 `scripts/ci/check-flyway-versions.sh origin/main`；打包一律 `mvn -B clean package`。

### UX Design Requirements

本主题**无独立 UX 稿**，全部在既有页面上改。视觉口径直接继承：
- **UX-S1** 后台页面一律用 admin 主题的模板 A~E + htmx 局部更新（200 主 fragment + `hx-swap-oob`，422 行内错，403 禁用态注明缺哪个权限，禁返 JSON）。
- **UX-S2** App 端沿用现有 Toko 视觉（紫色主题，色值源 `colors.dart`）；新增文案一律进 ARB，不硬编码。⚠️ **App 只有 `app_en.arb` / `app_id.arb` 两包，没有中文包**；「三语」指后台 Thymeleaf 的 `messages_{zh_CN,en,id}.properties`，与 App 无关。
- **UX-S3** banner 顶栏三段遮罩对比度基线 9.09:1，每张图都要满足。
- **UX-S4** 批量操作的二次确认走后台模板的确认态，不是浏览器 `confirm()`。

### FR Coverage Map

| 需求 | Story | 批次 |
|---|---|---|
| SHOP-FR-01 | 1-1, 1-3 | 一 |
| SHOP-FR-02 | 1-2, 1-4 | 一 |
| SHOP-FR-03 | 4-4 | 一 |
| SHOP-FR-04 | 4-1, 4-2 | 一 |
| SHOP-FR-05 | 2-1 | 一 |
| SHOP-FR-35 | 2-2 | 一 |
| SHOP-FR-24 | 3-4 | 一 |
| SHOP-FR-25 | 3-2, 3-3 | 一 |
| SHOP-FR-26 | 3-1, 3-3 | 一 |
| SHOP-FR-27 | 5-1 | 一 |
| SHOP-FR-28 | 5-3（查询）, 9-5（页面） | 一 / 二 |
| SHOP-FR-29 / 30 | 4-3 | 一 |
| SHOP-FR-31 | 5-2 | 一 |
| SHOP-FR-13（C 端分页部分） | 4-5 | 一 |
| SHOP-FR-19 | 6-1, 6-2, 6-3 | 二 |
| SHOP-FR-06 | 6-4 | 二 |
| SHOP-FR-15 | 7-1, 7-2 | 二 |
| SHOP-FR-17 | 7-1, 7-3 | 二 |
| SHOP-FR-16 / 18 | 7-1, 7-4 | 二 |
| SHOP-FR-07 | 7-5, 7-6 | 二 |
| SHOP-FR-08 | 7-7 | 二 |
| SHOP-FR-10 | 8-1 | 二 |
| SHOP-FR-11 | 8-2 | 二 |
| SHOP-FR-12 | 8-3 | 二 |
| SHOP-FR-21 | 8-4 | 二 |
| SHOP-FR-20 | 8-5 | 二 |
| SHOP-FR-22 | 8-6 | 二 |
| SHOP-FR-13（后台部分） | 9-1, 9-2 | 二 |
| SHOP-FR-14 | 9-2 | 二 |
| SHOP-FR-09 | 9-3 | 二 |
| SHOP-FR-23 | 9-4 | 二 |
| SHOP-NFR-01 | 1-2, 9-2, 9-3, 9-4 | — |
| SHOP-NFR-02 | 7-1（框架）+ 7-2~7-4 | 二 |
| SHOP-NFR-03 | 4-5, 7-5, 9-3, 3-4 | — |
| SHOP-NFR-04 | 4-1, 4-5, 6-3 | — |
| SHOP-NFR-05 | 3-3 | 一 |
| SHOP-NFR-06 | 3-4, 7-5, 7-6 | — |

---

## Epic List

**第一批（随 V1.3.0，无外部前置，18 story）**
1. **Epic 1 · 支付可感知与支付监控** —— 让用户知道支付为什么没成，让运营在 PostHog 看得到。
2. **Epic 2 · 售后收口** —— App 隐藏退货入口，运营侧有一条合规、走得通的兜底路径。
3. **Epic 3 · 客服可达** —— 工单能说清是哪一单，客服号全系统统一，新订单有信号。
4. **Epic 4 · 购买闭环** —— 部分结算、订单号统一且不可推算、复购卡有价、C 端列表分页。
5. **Epic 5 · 缺陷收口** —— 日志脱敏、错误码、看板口径。

**第二批（依赖 admin Epic 10，22 个 Story）**
6. **Epic 6 · 品类可配置与商品编号** —— 上新品类不再改代码发版；商品与规格有人能读的编号。
7. **Epic 7 · 批量操作与 Excel 导入导出** —— 264 个规格改一次价不再点 264 次。
8. **Epic 8 · 商品编辑体验** —— 图片、规格、注册号、校验、banner。
9. **Epic 9 · 订单与财务后台** —— 列表能搜能筛能导，对账能下钻。

---

## Epic 1: 支付可感知与支付监控

**目标**：用户扫码付不成时，App 明确告诉他发生了什么、能不能重试；运营在 PostHog 持续看到支付漏斗与失败原因，不再靠一次性查库猜。

### Story 1-1: 后端下发支付状态与失败类别

As a 用户，
I want App 能知道我的付款是被拒了还是超时了，
So that 我不用对着一个永远不会成功的二维码干等。

**技术背景**：网关拒付只改 `payment_intents`、不改订单，而 App 轮询的是订单状态 ⇒ 被拒这一支 App 根本感知不到（AD-S9(a)）。

**Acceptance Criteria:**

1. **L0** 新增 `PaymentFailureCategory` 枚举：`GATEWAY_DECLINED` / `EXPIRED` / `USER_CANCELLED`，以及工厂 `of(PaymentIntent)`：`gateway_meta->>'reason'` 为 `TIMEOUT` → `EXPIRED`；`USER_CANCEL` / `CANCELLED` → `USER_CANCELLED`；其余（网关回调写入）→ `GATEWAY_DECLINED`；支付单非失败态 → `null`。
2. **L0** 订单详情响应 `ShopOrderDetailView` 新增 `paymentStatus`（透传支付单状态）与 `paymentFailureCategory`；**不下发 `gateway_meta` 原文**（可能含 PII）。
3. **L0** 契约同改（C5）：后端 record + App DTO + 契约 test —— ⚠️ C5 原文写「四处」含 App mock，但 mock 子系统已于 `8e85b40d` 整体删除，**实为三处**。
4. **L0** 单测覆盖 `of()` 的四条分支，含 `gateway_meta` 为 null 的情况。
5. **L1** 集成测试：构造被拒 / 超时 / 用户取消三种支付单，订单详情返回的类别各不相同且与预期一致。
6. **L0** 电商之外的四种支付用途（问诊 / AI 解锁 / 高清身份证 / 充值）不读取该字段，行为零变化。

### Story 1-2: 埋点护栏、环境标记与服务端支付事件

As a 运营，
I want 在 PostHog 看到电商支付的完整漏斗与失败原因，且 staging 的数据不混进来，
So that 我能持续判断支付到底出了什么问题。

**技术背景**：后端目前**无事件名白名单、无属性脱敏器**，契约只写在 javadoc 里；`PostHogAnalyticsClient.capture` 不带任何环境属性（AD-S9(b)）。

**Acceptance Criteria:**

1. **L0** 新增 `shared/analytics/AnalyticsEventGuard`：事件名白名单 + 属性键白名单，违规事件**丢弃并 WARN**（不抛异常、不影响业务）。既有三个上报点（里程碑、名片页、分享页）的事件名与属性一并纳入白名单，行为不变。
2. **L0** `PostHogAnalyticsClient.capture` 统一注入 `app_env` 属性，取值来自环境变量（`prod` / `stag` / `dev`），缺省为 `dev`。
3. **L0** 新增 `ShopPaymentAnalyticsListener`（`shop/order/service`），`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`，上报五个事件：`shop_payment_intent_created` / `shop_payment_paid` / `shop_payment_declined` / `shop_payment_expired` / `shop_payment_user_cancelled`。
4. **L0** 事件属性**只含枚举与数值**：订单金额、失败类别、支付渠道、是否含 PawCoin 段；`distinct_id` 走既有 `AnalyticsDistinctId`。**不含**姓名 / 电话 / 地址 / 邮箱 / 订单号。
5. **L0** **变异验证**：去掉 `AnalyticsEventGuard` 的属性白名单校验，必须有测试变红（护栏假绿是本工作线出过三次的事故）。
6. **L1** 集成测试：支付成功与失败各跑一次，断言监听器被触发且属性集合符合白名单。
7. **L2** staging 实跑三支，PostHog 中事件带 `app_env=stag`。

### Story 1-3: App 支付三态处置

As a 用户，
I want 付款失败时页面明确告诉我原因，该重试的给我重试入口，
So that 我不会以为是自己手机的问题然后放弃购买。

**技术背景**：`QrPaymentSheet` 是共用组件，本次必须走参数而非改默认行为（AD-S9(a)）。⚠️ **2026-09-16 代码核实更正**：实际只有 **4 个调用点 / 3 条业务线**（电商、AI 解锁、高清身份证×2）；**问诊与充值各自内联 `QrImageView` + 自轮询，不走本组件**，回归范围按此。

**Acceptance Criteria:**

1. **L0** 支付轮询改为读订单详情中的 `paymentStatus` 与 `paymentFailureCategory`，不再只看订单状态。
2. **L0** **支付被拒** → 关闭二维码 + 展示原因文案 + **保留重试入口**（订单仍在待支付倒计时内）；重试生成新的支付单。
3. **L0** **超时未付** → 关闭二维码 + 告知已超时、订单已取消，**不给重试入口**（沿用「过期后不保留支付入口」）。
4. **L0** **用户取消订单** → 关闭二维码，不弹错误；**仅关闭面板**（下滑关闭）→ 订单不取消，详情页可继续支付。
5. **L0** 新增文案全部进 ARB（en / id 两包，App 无中文包），不硬编码。
6. **L0** `QrPaymentSheet` 的新行为由参数开启，默认关闭；其余四条链路调用点不传该参数。
7. **L0** `flutter analyze` 与既有支付相关测试全绿。
8. **L2** 模拟器回归：电商三支表现符合上述；**问诊 / AI 解锁 / 高清身份证 / 充值四条链路各跑一次成功与超时，表现与改动前一致**。

### Story 1-4: App 支付环节埋点

As a 运营，
I want 知道用户在支付哪一步离开、有没有重试，
So that 我能判断卡点在出码前还是出码后。

**Acceptance Criteria:**

1. **L0** 上报事件：进入支付面板、二维码展示成功、关闭面板（未完成）、收到成功 / 被拒 / 超时反馈、点击重试、取消订单。
2. **L0** 事件走既有 `Analytics` 门面与 `scrub`；按钮类事件的 id 进 `_allowedButtonIds` 白名单与 `button_ids.dart` 常量。
3. **L0** 属性只含枚举与数值，不含订单号与任何个人信息。
4. **L0** `flutter test` 覆盖「关闭面板」与「点击重试」两个事件的触发。
5. **L2** staging 实跑，PostHog 中能拼出「进入支付 → 出码 → 成功」漏斗。

---

## Epic 2: 售后收口

**目标**：App 内不再出现退货入口，同时运营手上有一条合规、走得通、不会多发钱的处理路径。

### Story 2-1: App 隐藏退货入口与文案

As a 用户，
I want 不要在 App 里看到一个点了也没用的退货入口，
So that 我不会白填一遍表单再发现走不通。

**Acceptance Criteria:**

1. **L0** 任何订单状态下，订单详情、订单列表、帮助区都不出现「申请退货 / 退款」入口（现仅 `COMPLETED` 态有一处 push，全项目唯一）。
2. **L0** 退货窗口时长等退货相关文案不出现（含 ARB 中的「2×24 小时」文案，本 story 只隐藏不修正）。
3. **L0** **只改 App**：后端退货接口、运营后台退货页面保留且可用，本 story 不碰后端。
4. **L0** 退货相关路由保留但不可达（老深链进入时给出「暂不支持，请联系客服」并提供客服入口），不得白屏。
5. **L0** `flutter analyze` + 相关 widget 测试全绿。
6. **L2** 模拟器逐态验收：待发货 / 已发货 / 已送达 / 已完成四种订单详情均无退货入口。

### Story 2-2: 售后兜底配置与运营流程

As a 运营，
I want 用户要求取消或退款时，知道在后台点哪里、且不会多发钱给用户，
So that 我不用每次都找技术确认。

**技术背景**：SD-13。系统现状「拒收会发平台责任补偿」偏离既定规则；后台「整单取消」这条路今天会发补偿溢价。

**Acceptance Criteria:**

1. **L1** 运营后台 PawCoin 配置中，「平台责任补偿比例」与「转 PawCoin 激励比例」均设为 0，配置变更进 `config_change_logs` 与审计哈希链。
2. **L0** 交付一份《本版售后运营处理流程》文档（`docs/runbooks/`），至少覆盖：
   - **待发货要取消** → 后台「订单异常处置 · 整单取消」；PawCoin 段自动退回、现金段线下打款；**操作前确认该订单商品都有采购入库记录**，否则会报错（见下条）。
   - **已发货 / 已送达 / 已完成要退款** → 后台无代用户发起退货的入口，一律线下处理（转账 + PawCoin 人工调账），在工单内部备注留痕。
   - **老版本 App 提交的退货单** → 照常出现在后台退货页，按既有流程处理；执行退款前确认用户已选退款去向，未选的转线下。
3. **L1** 验证「无采购入库记录的商品做整单取消」的实际表现，把报错信息与规避办法（先补登采购入库）写进文档。
4. **L0** 文档经运营确认；配置截图留档在 story 的 Completion Notes。
5. **L0** 本 story **不写业务代码**；若实施中发现必须改代码才能满足「不发补偿」，停下来回到决策日志确认，不得自行扩大范围。

---

## Epic 3: 客服可达

**目标**：用户找客服时，客服知道是哪一单；客服号全系统一份配置；新订单进来运营立刻知道。

### Story 3-1: 客服联系方式配置化

As a 运营，
I want 换客服号时改个配置就行，
So that 不用等 App 发版。

**技术背景**：号码现硬编码在 2 处（`customer_service_sheet.dart:12`、`AuthService.java:41`），项目**没有通用配置表**（AD-S8）。

**Acceptance Criteria:**

1. **L0** 新建 `support_contact_config` 单行表（`id=1` + 单例 CHECK，比照 `pricing_config`）：`whatsapp_number` / `email`；迁移内灌入当前值（`081290906953` / `cs@tailtopia.id`）。
2. **L0** 新增 `SupportContactProvider`（`shared/config`）；后台配置页可改，写操作进 `config_change_logs` + 审计。
3. **L0** 新增 `GET /api/v1/support/contact`（**免鉴权**）下发号码与邮箱。
4. **L0** 后端「账号已停用」提示文案改读配置（现 `AuthService.java:41` 硬编码）。
5. **L0** App 客服弹窗改读该端点，请求失败时回退本地常量，不空白。
6. **L0** 号码归一化复用既有 `IndonesiaPhone`（`08xx` → `+62…`），单测覆盖三种写法归一到同一个号。
7. **L1** 改配置后，三处消费点（弹窗 / 停用提示 / 后续的深链）同批生效。

### Story 3-2: 工单关联电商订单（后端）

As a 客服，
I want 打开工单就知道用户说的是哪一笔电商订单，
So that 我不用来回问订单号。

**技术背景**：`feedback_tickets.related_order_id` 是裸 bigint、只认问诊单，而两类订单 id 都是自增 bigint、**会撞号**（AD-S7）。

**Acceptance Criteria:**

1. **L0** 加列 `related_order_type` varchar(16) NOT NULL DEFAULT `'CONSULT'`（值域 `CONSULT` / `SHOP`），存量回填 `CONSULT`；**不加跨表 FK**。
2. **L0** `SupportTicketService.resolveRelatedOrder` 扩展：先按 token 找问诊单、再找电商单；**保持归属校验**（不属本人 → 静默 null，沿用既有宽松口径）。
3. **L0** 后台 `linkOrder` 拆为 `linkConsultOrder`（保留退款审批联动）与 `linkShopOrder`（**不触发任何退款流程**）。
4. **L0** 后台工单详情按类型展示对应订单摘要；电商单展示 `display_no`（依赖 Story 4-3，先行实现时用现有号码，4-3 完成后一并切换）。
5. **L0** 契约测试断言 `related_order_id` 与 `related_order_type` **不下发给用户**（沿用既有禁字段范式）。
6. **L1** 集成测试：同一个 id 值分别作为问诊单与电商单关联，两条工单互不串单。
7. **L1** 历史工单（`CONSULT` 类型）的关联与退款审批行为零变化。

### Story 3-3: App 工单选订单与 WhatsApp 深链

As a 用户，
I want 提工单时能选中出问题的那一单，或者直接在 WhatsApp 上找客服，
So that 我不用把订单号手打一遍。

**技术背景**：🔴 Flutter 建单页**从不传 `relatedOrderToken`**，所以后端那条路今天恒为 NULL —— 本 story 补的是这一半（AD-S7）。

**Acceptance Criteria:**

1. **L0** 建单页新增「关联订单」选择器：列出本人最近订单（问诊 + 电商），可不选；选中后随请求传 `relatedOrderToken`。
2. **L0** 从订单详情进入建单时**自动预选该订单**。
3. **L0** 订单详情页提供「在 WhatsApp 联系我们」：以外部应用打开 `wa.me/<E.164>?text=…`，**预填订单号**且用户可编辑；文案走 ARB（en / id 两包）。
4. **L0** 工单页仅当该工单**关联了电商订单**时显示该按钮；订单详情页始终显示。
5. **L0** 预填内容**不含**收件人姓名、电话、地址（SHOP-NFR-05）；单测断言预填串只含订单号。
6. **L0** 打开失败时提示并提供「复制号码」（未安装 WhatsApp 时系统会打开浏览器落地页，不是白屏）。
7. **L0** 点击上报埋点事件（支撑客服可达指标），走既有白名单。
8. **L2** 真机验收：预填带订单号、可编辑、能发出；未装 WhatsApp 的设备上不白屏。

### Story 3-4: 新订单 Lark 提醒

As a 运营，
I want 有新订单时 Lark 告诉我一声，
So that 我不用一直刷后台。

**技术背景**：触发点 `ShopOrderPaidHandler.onPaid`；`LarkOAuthClient` / `LarkContentClient` 的 token 缓存已就位。

**Acceptance Criteria:**

1. **L0** 付款成功后发送 Lark 提醒，内容含订单号、金额、商品数量；**不含**收件人姓名、电话、地址。
2. **L0** 🔴 **汇总节流**：同一时间窗内的多笔订单合并为一条（窗口长度可配，默认 10 分钟），**不得每单一条**。
3. **L0** 节流实现走 `@Scheduled` 扫描 + DB 标记位（`@Async` 投递），**禁引入队列或调度中间件**（SHOP-NFR-06）。
4. **L0** Lark 发送失败只记日志，不影响订单本身、不重试到阻塞主流程。
5. **L1** 集成测试：窗口内连续 3 笔订单 → 只发一条含 3 笔的汇总；跨窗口的订单分两条。
6. **L0** 发给谁（个人 / 群）、窗口长度、夜间是否静默走配置（OD-5 定值前用默认值，**代码不写死收件人**）。
7. **L2** 真实 Lark 群收到一条汇总提醒。

---

## Epic 4: 购买闭环

**目标**：用户能只买想买的、看得到价格、一张单只有一个号且外人猜不到。

### Story 4-1: 购物车部分结算（后端）

As a 用户，
I want 只结算购物车里选中的商品，
So that 误加的东西不用连着买、也不用先删掉。

**Acceptance Criteria:**

1. **L0** 加列 `shop_cart_items.selected` boolean NOT NULL DEFAULT true。
2. **L0** 新增 `PUT /api/v1/me/cart/items/{skuToken}/selected?selected=` 与 `PUT /api/v1/me/cart/selection?selected=`（全选 / 全不选），沿用购物车既有「query param、无请求体」风格。
3. **L0** `CartView` 增 `selected`（行级）、`selectedSubtotal`、`selectedCount`；**`subtotal` 语义不变**（全车合计），避免老版本读到变味的字段。
4. **L0** `GET /checkout` 与 `POST /shop-orders` **只取 `selected=true` 且有效的行**；全不选时返回 422。
5. **L0** 免运门槛、PawCoin 抵扣上限、库存锁定全部按选中集计算（算式本身不改，只换输入集合）。
6. **L0** 契约同改（C5，实为三处：后端 record + App DTO + 契约 test；C5 原文的 App mock 一腿已随 `8e85b40d` 删除）。
7. **L1** 集成测试：三件商品选两件下单 → 订单只含两件、金额与运费按两件算、未选的仍在购物车、库存只锁两件。
8. **L1** **老版本兼容**：不调选择端点、不传勾选信息 → 按整车下单，行为与改动前一致（SHOP-NFR-04）。

### Story 4-2: 购物车勾选（App）

As a 用户，
I want 在购物车里勾选要买的商品并看到实时小计，
So that 我下单前就知道要付多少。

**技术背景**：代码自记「画一个不影响下单的勾选框＝能造成资损的谎」—— 本 story 上线前，勾选框**不得先于后端能力出现**。

**Acceptance Criteria:**

1. **L0** 购物车每行有勾选框，顶部有全选；**默认全选**。
2. **L0** 底栏小计、免运提示、结算按钮全部按**选中行**实时更新。
3. **L0** 失效行（下架 / 售罄 / 停用品类）不可勾选，且不计入小计。
4. **L0** 全部取消勾选时结算按钮禁用并提示。
5. **L0** 取消勾选**不删除**商品；下单后未选中的商品留在购物车。
6. **L0** `flutter analyze` + 购物车 widget 测试全绿。
7. **L2** 模拟器验收：勾选变化 → 金额同步；只买其中一件，下单后购物车剩另一件。

### Story 4-3: 订单号统一且不可推算

As a 平台，
I want 对外的订单号猜不出来，且一张单只有一个号，
So that 外人无法推算订单量或试探他人订单，用户与客服也不再对不上号。

**技术背景**：现 `TOKO-yyyyMMdd-%06d` 用自增 id、**可枚举、从不落库**；列表显示它、详情显示 32 位内部标识（AD-S3）。

**Acceptance Criteria:**

1. **L0** 加列 `shop_orders.display_no` varchar(32) UNIQUE NOT NULL，格式 `TOKO-yyyyMMdd-XXXXXX`，`XXXXXX` 为 Crockford Base32 6 位随机（`SecureRandom`，去易混字符），日期取下单时刻 WIB；冲突重试 ≤5 次，唯一索引兜底。
2. **L0** 加列 `legacy_display_no` varchar(32) NULL；**同一支迁移内回填存量订单的旧号**，新订单该列为 null。
3. **L0** App 的列表、详情、工单关联、WhatsApp 预填**统一使用 `display_no`**（SHOP-FR-29）。
4. **L0** 后台订单搜索同时命中 `display_no` 与 `legacy_display_no`。
5. **L0** **保留 `TOKO` 前缀**（对账依赖它区分实物收入）；`OrderDisplayNo` 工具类保留不动，继续服务问诊 / AI / 充值三种前缀。
6. **L0** 单测：随机段字符集正确、长度固定、不含易混字符；模拟冲突时重试成功。
7. **L1** 迁移后存量订单全部有 `display_no` 与 `legacy_display_no`，且互不重复；对账页按 `TOKO` 前缀的统计口径不变。

### Story 4-4: 复购卡显示价格

As a 用户，
I want 复购提醒卡片上能看到价格，
So that 我不用点进去才知道多少钱。

**Acceptance Criteria:**

1. **L0** 复购卡响应下发价格字段（现 `RepurchaseCardView` 无价格也无图）。
2. **L0** App 复购卡渲染价格；**无价格时整行不渲染**（沿用「不显示 0」口径）。
3. **L0** 契约同改（C5，实为三处：后端 record + App DTO + 契约 test；C5 原文的 App mock 一腿已随 `8e85b40d` 删除）。
4. **L2** 模拟器验收：首页复购卡显示价格且与商品详情一致。

### Story 4-5: C 端商品列表分页

As a 用户，
I want 商品多了以后首屏也能很快打开，
So that 我不用等一次性拉完所有商品。

**技术背景**：`ShopProductController.list()` 现返回全量 `List`；🔴 **这是破坏性契约变更**，老版本 App 会只看到第一页甚至打不开（SHOP-NFR-04）。

**Acceptance Criteria:**

1. **L0** 商品列表支持分页，每页 20 条（SHOP-NFR-03），游标式（沿用项目既有分页格式）。
2. **L0** 🔴 **老版本兼容**：请求**不带分页参数时仍返回全量**，保留到最低支持版本升级为止；该兼容分支须有测试覆盖。
3. **L0** 既有的 `category` 与 `q` 组合（交集关系）行为不变。
4. **L0** 契约同改（C5，实为三处：后端 record + App DTO + 契约 test；C5 原文的 App mock 一腿已随 `8e85b40d` 删除）。
5. **L1** 集成测试：带分页参数返分页结构、不带返全量数组，两者内容一致。
6. **L2** 模拟器验收：滚动加载正常，不重复不丢项。

---

## Epic 5: 缺陷收口

**目标**：把几个「知道但一直没修」的小问题清掉。

### Story 5-1: 评价原文不进日志

As a 平台，
I want 用户写的评价内容不出现在请求日志里，
So that 不因为一个不做的功能留下泄露面。

**技术背景**：评价页本版不做，但提交接口仍可调用，正文字段名是 `content`。

**Acceptance Criteria:**

1. **L0** `LogSanitizer` 把 `content` 加入**只对请求体生效**的敏感键集合（`REQUEST_ONLY_SENSITIVE_KEYS`），**不放进全局集合**（否则会把所有响应里的同名字段都打掉）。
2. **L0** 单测：评价提交请求的正文被打码；其它含 `content` 字段的响应不受影响。
3. **L0** **变异验证**：移除该键，测试必须变红。

### Story 5-2: 请求方法错误不再返回 500

As a 运维，
I want 用错请求方法时返回 405 而不是 500，
So that 告警里不再混入这类噪声。

**技术背景**：400 类已处理，**只剩 405**（及同类的 415）。

**Acceptance Criteria:**

1. **L0** `GlobalExceptionHandler` 补 `HttpRequestMethodNotSupportedException` → 405、`HttpMediaTypeNotSupportedException` → 415 两个分支。
2. **L0** 响应体遵循统一错误格式，**不外泄堆栈**。
3. **L0** 单测覆盖两种异常。

### Story 5-3: 看板统计口径修正（查询层）

As a 运营，
I want 库存周转页的售罄数是真的，
So that 我不会因为它恒显示 0 而漏掉断货。

**技术背景**：现查询限定 `is_active = true` 且 INNER JOIN 库存表（无库存记录的规格不计入）⇒ 数字与实际不符。

**Acceptance Criteria:**

1. **L0** 售罄数改为「**所有未删除规格中可售库存为 0 的数量**」：去掉 `is_active` 限定，改 LEFT JOIN 覆盖无库存记录的规格。
2. **L0** 单测断言新口径（构造上架 / 下架 / 无库存记录三类规格，断言计数）。
3. **L0** 售后成本口径、PawCoin「可用 / 冻结中」的**定义**写进代码注释与 story 交付说明（冻结中＝已被待支付订单占用、尚未扣减的部分）。
4. **L0** ⚠️ **页面展示与口径标注**属于后台页面改动，随 Story 9-5 在 admin Epic 10 之后落地；本 story 只改查询与单测。

---

## Epic 6: 品类可配置与商品编号

> 🔴 **前置**：admin 主题 Epic 10 完成。

**目标**：上一个新品类不再需要改代码 + 发版 + 数据库迁移；商品和规格有运营能读、能填、能搜的编号。

### Story 6-1: 品类表与迁移（后端）

As a 运营，
I want 品类是数据而不是写死在代码里，
So that 上新品类不用等一次发版。

**技术背景**：AD-S1。🔴 **用外键取代 CHECK 约束**，正是为了根除「重建 CHECK 时照着过期列表抄」的事故模式（此处已出过三次事故）。

**Acceptance Criteria:**

1. **L0** 新建 `shop_categories`：`code`(UNIQUE, 创建后不可改) · `name_id` · `name_en` · `sort_weight` · `is_active` · 时间戳。
2. **L0** 同一支迁移内：灌入四条存量（`MAKANAN` / `OBAT_VITAMIN` / `CAMILAN` / `PERAWATAN`，显示名取现 ARB 文案）→ `DROP CONSTRAINT ck_shop_products_category` → `ADD CONSTRAINT fk_shop_products_category FOREIGN KEY (category) REFERENCES shop_categories(code)`。**`shop_products.category` 列名与类型不变，存量数据零改动**。
3. **L0** `ShopProduct.category` 由枚举改为 `String categoryCode`；`ProductCategory` 枚举退出持久层与 DTO，保留为 `@Deprecated` 存量码常量。
4. **L0** 新增 `shop/category/{domain,repository,service,dto}`；**不加缓存**（表只有十几行，禁通用缓存层）。
5. **L0** 新增 `GET /api/v1/shop/categories` → `[{code, name, sortWeight}]`，`name` 按 `Accept-Language` 选 `name_id` / `name_en`，**只下发一个 name**。
6. **L0** 停用语义：`is_active=false` ⇒ 商品列表与搜索过滤、复购卡与推荐区过滤；**购物车该品类行标记失效**（新增 `REASON_CATEGORY_DISABLED`），结算时进 `unavailableLines`；**历史订单照常显示**（订单行是快照，不查品类表）。
7. **L0** 契约同改（C5，实为三处：后端 record + App DTO + 契约 test；C5 原文的 App mock 一腿已随 `8e85b40d` 删除）。
8. **L1** 🔴 迁移在 scratch 库跑通，应用后 `ddl-auto=validate` 启动成功（schema 契约以 L1 绿为准）。
9. **L1** 集成测试：新增品类 → 商品可归属；停用品类 → C 端不可见、购物车行失效、历史订单仍显示。

### Story 6-2: 品类管理页（后台）

As a 运营，
I want 在后台自己增改停用品类，
So that 不用每次找技术。

**Acceptance Criteria:**

1. **L0** 新增品类列表页（模板 A）与表单（模板 C），走 htmx 局部更新（UX-S1）。
2. **L0** 支持新增、改显示名、改排序、停用 / 启用；**`code` 创建后不可改**（表单只读）。
3. **L0** 显示名必须同时填**印尼语与英语**（SD-15），两者都不可为空。
4. **L0** **有商品的品类不可删除**（本版根本不提供删除，只有停用）。
5. **L0** 新增权限码 `shop.category_manage`，🔴 **加码必改 5 处**（AD-S13）。
6. **L0** 写操作三件套：`@PreAuthorize` + `AdminAuditService.record` + 三语 key；新增 POST 端点本地 catch 业务异常。
7. **L0** 该页登记为 AB-19A「零端点变更」的**新例外**（新能力，无既有端点可复用）。
8. **L1** 无权限账号访问 → 禁用态并注明缺哪个权限（不是 JSON 403）。

### Story 6-3: App 品类下发与停用适配

As a 用户，
I want 新上的品类在 App 里能看到、名字是我的语言，
So that 我能找到想买的东西。

**技术背景**：🔴 这是对「App 不渲染后端显示串、按 code 本地化」模型的**有意例外**（AD-S1-E），**例外仅限品类名**，不得扩散。

**Acceptance Criteria:**

1. **L0** App 从 `GET /api/v1/shop/categories` 取品类并渲染后端下发的 `name`；端点不可达时回退 ARB 中现有四个品类名。
2. **L0** 首页分类入口**品类超过 4 个时改为横向滑动**（SD-15）。
3. **L0** 购物车中停用品类的行显示失效态且不可勾选结算。
4. **L0** 🔴 **老版本兼容**（SHOP-NFR-04）：新品类商品在老版本「全部」列表中照常出现；老版本遇到未知 `category` code 解析为 null、**不崩溃**；改名对老版本无效（仍显示 ARB 旧名），此为已接受行为。
5. **L0** `flutter analyze` + 相关测试全绿。
6. **L2** 模拟器验收：新增第 5 个品类后首页可横向滑动；停用某品类后该品类商品消失、购物车内该品类行变失效。

### Story 6-4: SPU / SKU 编号

As a 运营，
I want 商品和规格有我能读、能填、能搜的编号，
So that 我能用 Excel 批量更新，也能按编号找货。

**Acceptance Criteria:**

1. **L0** `shop_products.spu_no` varchar(16) UNIQUE NOT NULL；`shop_skus.sku_no` varchar(24) UNIQUE NOT NULL。
2. **L0** 生成规则：专用序列 → `SPU-%06d`；SKU 为 `<spu_no>-%02d`（同商品内序号）。**不用主键 id**；**运营不可改**（实体 `updatable=false`，后台只读展示）。
3. **L0** 同一支迁移内按 id 升序回填存量，序列起点设为回填后最大值 + 1。
4. **L0** 🔒 **不对外**：不进任何 `/api/v1/**` 面向用户的 DTO；**契约测试断言其缺席**（沿用既有禁字段范式）。
5. **L0** 后台商品页、库存页、订单详情可见并可按编号搜索（搜索能力在 Story 9-1 落地，本 story 只保证字段可用）。
6. **L1** 迁移后全部商品与规格有唯一编号；新建商品自动获得编号。

---

## Epic 7: 批量操作与 Excel 导入导出

> 🔴 **前置**：admin 主题 Epic 10 完成；Story 6-1（品类）与 6-4（编号）先于本 epic。

**目标**：264 个规格改一次全场价不再点 264 次；上架商品不再一个一个填表单。

### Story 7-1: 批量写统一框架

As a 运营，
I want 批量操作有预览和二次确认，且我预览之后别人改了数据不会被我覆盖，
So that 我不会误改一片商品。

**技术背景**：AD-S5。🔴 用**条件写**实现「预览即契约」，不加乐观锁列、不引入分布式锁。

**Acceptance Criteria:**

1. **L0** 统一的批量提交流程：圈定（勾选 / 按当前筛选结果）→ 预览（影响行数 + 改前改后值）→ 二次确认 → 提交。确认走后台模板确认态，**不是浏览器 `confirm()`**（UX-S4）。
2. **L0** **预览即契约**：提交时带上预览时看到的旧值，服务端用条件写校验；**任一行不匹配 ⇒ 整批拒绝**并提示重新预览。
3. **L0** **整批一个事务，全成或全不成**（与导入的逐行独立相反）。
4. **L0** **逐条审计**：批量 100 行 = 100 条 `AdminAuditService.record`；**不得只记一条「批量处理 N 条」**（SHOP-NFR-02）。
5. **L0** 单次上限 100 行，服务层强校验。
6. **L0** 新增权限码 `shop.bulk_edit`（四类批量共用一个码），🔴 加码必改 5 处。
7. **L0** 🔴 **变异验证**：去掉条件写守卫，必须有测试变红。
8. **L1** 并发测试：预览后由另一个会话改掉其中一行 → 提交整批被拒且无任何行被改。

### Story 7-2: 批量改价

As a 运营，
I want 一次改完全场价，
So that 不用点 264 次。

**Acceptance Criteria:**

1. **L0** 支持两种输入：**设为固定价**、**按百分比增减**；百分比结果**向上取整到 100 印尼盾**。
2. **L0** **不允许改到 0**（0 元商品须走单条编辑）。
3. **L0** 条件写守卫 `WHERE price = :expectedPrice`（Story 7-1 框架）。
4. **L0** 预览展示每行改前改后价格与影响行数。
5. **L0** ⚠️ 264 个规格的全场改价需**分 3 批**（单次 ≤100 行），页面上提示运营。
6. **L1** 集成测试：改价成功后价格正确、审计逐条、既有订单快照价格不受影响。

### Story 7-3: 批量修改库存

As a 运营，
I want 一次盘点多个规格的库存，
So that 不用逐个改。

**技术背景**：🔴 风险高于导入，改错直接超卖。**复用既有盘点原语的 CAS**，不新造扣减路径（AD-S5）。

**Acceptance Criteria:**

1. **L0** 语义为**盘点（设为绝对值）**，不做增减，避免并发叠加错误。
2. **L0** 🔴 设定的是**在手库存**，**下单时已锁定的库存保持不变**；复用 `SkuInventoryRepository.stocktakeTo(skuId, counted, expectedBefore)` 的 CAS，其 `i.locked <= :counted` 守卫防止可售量为负。
3. **L0** 每一行写库存流水（`inventory_movements`，类型 `STOCKTAKE`）。
4. **L0** 与改价同款预览 + 二次确认 + 逐条审计。
5. **L1** 集成测试：有待支付订单锁着库存时盘点 → 锁定量不变、可售量正确、不出现负数。
6. **L1** 盘点数小于已锁定量时整批拒绝并给出可读提示。

### Story 7-4: 批量上下架与批量调品类

As a 运营，
I want 一次上架或下架一批商品、一次换一批商品的品类，
So that 季节性调整不用逐个点。

**Acceptance Criteria:**

1. **L0** 批量上架 / 下架：条件写 `WHERE is_active = :expectedActive`。
2. **L0** 🔴 **下架只改可见性、不动库存**，**页面上显式写给运营看**（沿用电商一期口径）。
3. **L0** 批量调品类：条件写 `WHERE category = :expectedCategory`；**目标品类必须是启用状态**，停用品类不可作为目标。
4. **L0** 两者共用 Story 7-1 的预览、确认、审计、上限与权限码。
5. **L1** 集成测试：下架后库存数字不变；调入停用品类被拒。

### Story 7-5: Excel 导入任务

As a 运营，
I want 用 Excel 一次上架一批商品，
So that 不用一个一个填表单。

**技术背景**：AD-S4。导入是**一个 DB 状态机驱动的异步任务**，不是一次请求。

**Acceptance Criteria:**

1. **L0** 新建 `shop_import_jobs`（状态 `PENDING`/`RUNNING`/`DONE`/`PARTIAL_FAILED`/`FAILED` + 计数 + 结果文件 key + `retry_count`）与 `shop_import_rows`（`raw_json` / `error_message` / 回填的商品与规格 id）。
2. **L0** 执行走 `@Async` + 启动重扫，**禁引入调度或消息中间件**（SHOP-NFR-06）。
3. **L0** **列规则（SD-14）**，落为单一事实源供模板生成与解析共用：
   - 编号列留空＝新建（系统生成编号）；填已有＝更新；**填不存在＝该行报错**；
   - **更新时售价与初始库存列一律忽略**（改价改库存只走 7-2 / 7-3）；
   - **更新时空单元格＝保留原值**，不清空；图片列留空保留原图、填写则整体替换。
4. **L0** 导入的新商品**一律默认未上架**；金额列不合法时**拒收而非四舍五入**。
5. **L0** 校验分层：解析期 fail-fast（文件非法 / 零数据行）→ 落行期逐行收集错误、行仍落库 → 执行期逐行 `REQUIRES_NEW`，一行失败不拖垮整份。
6. **L0** 🔴 **限额**：≤500 行、≤5MB，超限整体拒绝并提示；**必须显式校验 content-type 与行数** —— 既有 `SeedBatchExcelService` 无此校验，**不得复制该缺口**。
7. **L0** 结果文件在图片转存全部结束后生成，可下载；失败行标出原因。
8. **L0** 新增权限码 `shop.product_import`，🔴 加码必改 5 处；工作台页登记为 AB-19A 新例外。
9. **L1** 集成测试：一份含新建、更新、报错三类行的文件 → 计数正确、成功行落库、失败行有原因、其余行不受影响。

### Story 7-6: 外链图片转存

As a 运营，
I want Excel 里直接填图片链接，
So that 我不用先一张张上传。

**技术背景**：🔴 SD-9。这是本主题**唯一的新出网面**，五道闸缺一不可（AD-S4）。项目现状：服务端**不剥 EXIF**（F-10），既有远端抓取代码**无 SSRF 防护**（反例，勿参照）。

**Acceptance Criteria:**

1. **L0** 新增 `ShopRemoteImageFetcher`（`shared/media`），五道闸：
   - ① **仅 https**，主机须在白名单（配置项，非硬编码；范围见 OD-13）；
   - ② **SSRF 防护**：解析后 IP 不得落在私网 / 回环 / 链路本地 / 云元数据地址段；**禁跟随跳转到白名单外主机**；
   - ③ **大小**：边读边计数，超 10MB 立即中断 —— **不得先全量下载再判**；
   - ④ **类型**：魔数校验 jpg / png / webp，**不信 `Content-Type`**；
   - ⑤ **EXIF 剥离**：入库前以 `ImageIO` 重编码后再上传，同时测出宽高写入 `main_image_w/h`。
2. **L0** objectKey 沿用既有 `public/shop-product/<UUID>.<ext>` 约定。
3. **L0** 下载失败（超时 / 404 / 类型不符 / 超限）→ **该行标错，其余行照常导入**。
4. **L0** 单测覆盖五道闸各自的拒绝路径，**私网 IP 段黑名单必须有单测**。
5. **L0** 🔴 **变异验证**：去掉 SSRF 校验，必须有测试变红。
6. **L1** 集成测试：合法外链导入成功且图片可访问；转存后的图片**不含 EXIF**。

### Story 7-7: 商品导出

As a 运营，
I want 把商品导出成 Excel、改完再导回去，
So that 批量维护更顺手。

**Acceptance Criteria:**

1. **L0** 导出与导入模板**同构**（列顺序、表头、编号列一致），支持「导出 → 改 → 再导入」闭环。
2. **L0** 导出含 SPU / SKU 编号、名称、品牌、品类、物种、规格名、售价、库存、注册号、图片地址。
3. **L0** 单元格**公式转义**（`=` / `+` / `-` / `@` 开头前置 `'`），SHOP-NFR-01。
4. **L0** 导出上限 10,000 行（SHOP-NFR-03）。
5. **L1** 端到端：导出 → 改一列 → 导入 → 数据正确更新，且售价与库存列被忽略（SD-14）。

---

## Epic 8: 商品编辑体验

> 🔴 **前置**：admin 主题 Epic 10 完成。

**目标**：换一张图、配一个规格、填一个注册号，都不再是三步绕路。

### Story 8-1: 图片设为主图与替换

As a 运营，
I want 一键把某张图设为主图、或原位替换某张图，
So that 我不用「上传 → 拖到首位 → 删旧图」三步，也不怕删错。

**技术背景**：现状 `addThumb` 恒追加到末尾、无「设为主图」按钮、满 9 张时 `alert` 拒绝并要求先删后传（**删错无撤销**）。

**Acceptance Criteria:**

1. **L0** 任一图片可一键**设为主图**。
2. **L0** 可**原位替换**某张图（位置不变）。
3. **L0** **图满 9 张时仍可替换**，不再要求先删后传。
4. **L0** 拖拽排序能力保留。
5. **L0** 写操作三件套（`@PreAuthorize` + 审计 + 三语 key）。
6. **L2** 后台页面实操验收：替换主图一步完成；满 9 张时替换成功。

### Story 8-2: 新建商品时即可配置 SKU

As a 运营，
I want 新建商品时就能配规格，
So that 不用先保存再重新打开。

**技术背景**：🔴 要改的正是出过「静默覆盖 SKU」事故的端点（路径变量已改名修复）。**纪律：路径变量名不得与表单字段同名**，新建态恰恰是那个隐藏 input 不一定还在的场景。

**Acceptance Criteria:**

1. **L0** SKU 区从「仅编辑态」放开到新建态；一次保存即可带多个规格。
2. **L0** **不做多维规格**，规格名维持单一自由文本。
3. **L0** 🔴 提交参数命名遵守「路径变量名不得与表单字段同名」纪律，并在代码注释中标注原因。
4. **L0** 新建失败时已填规格不丢失（表单回填）。
5. **L1** 集成测试：一次提交创建商品 + 3 个规格，编号自动生成（依赖 6-4）。
6. **L2** 后台页面实操验收。

### Story 8-3: SKU 级图片

As a 用户，
I want 选不同规格时看到对应的图，
So that 我知道自己买的是哪一款。

**Acceptance Criteria:**

1. **L0** 加列 `shop_skus.main_image_key` / `main_image_w` / `main_image_h`。
2. **L0** 后台规格表单可上传图片，复用既有 multipart 路径与 `folder=shop-product`。
3. **L0** App 商品详情选中规格时主图切换；**规格无图时回退商品主图**。
4. **L0** 契约同改（C5，实为三处：后端 record + App DTO + 契约 test；C5 原文的 App mock 一腿已随 `8e85b40d` 删除）。
5. **L2** 模拟器验收：切换规格 → 主图随之变化；无图规格回退不闪烁。

### Story 8-4: 药品注册号

As a 平台，
I want 驱虫保健类商品能登记注册号，
So that 符合印尼农业部对该品类的管辖要求。

**技术背景**：SD-18。**有效期不做**（批次属性，做对需要批次管理）。

**Acceptance Criteria:**

1. **L0** 加列 `shop_skus.drug_reg_no` varchar(40) NULL。
2. **L0** 后台规格表单可填；**仅对 `OBAT_VITAMIN`（驱虫保健）品类展示该字段**。
3. **L0** 纳入导入导出模板（与 7-5 / 7-7 同批定稿，避免定稿后改模板再重导数据）。
4. **L0** **有效期字段不做**，本 story 明确不实现。
5. **L0** `[ASSUMPTION]` 本版不区分处方 / 非处方（OD-8 法务复核后再收紧）。

### Story 8-5: 必填项即时校验

As a 运营，
I want 填错的地方当场提示，
So that 不用填完一屏提交才被打回。

**技术背景**：**服务端校验已完备**（8 处），运营卡的是前端不提示。

**Acceptance Criteria:**

1. **L0** 必填项、长度、价格、图片数量与大小在**填写时即时提示**，走 htmx 422 行内错（UX-S1）。
2. **L0** **不改服务端校验规则**，只补前端反馈；服务端仍是权威。
3. **L0** 提示文案三语齐备。
4. **L0** 敏感词过滤**本 story 不做**，等 OD-4 定论（后台是可信角色，与用户内容审核口径不同）。
5. **L2** 后台页面实操验收：故意填错 → 当场提示、不丢失其它已填内容。

### Story 8-6: banner 多图与跳转

As a 用户，
I want 首页 banner 能点、能看到多张，
So that 我不会点了没反应以为 App 坏了。

**技术背景**：🔴 翻案电商一期「单图 + 纯展示」口径，该口径**写死在建表注释与实体注释里，必须连注释一起改**；翻案须留痕（ACT-D）。

**Acceptance Criteria:**

1. **L0** `shop_banners` 加列 `target_type`（`NONE` / `INTERNAL` / `EXTERNAL`）与 `target_value`。
2. **L0** 支持多张同时上架；改既有部分索引与「同一时间只展示一张」的**表注释与实体注释**。
3. **L0** 站内跳转按 App 可达路由白名单；**站外按域名白名单**（配置项）；站内目标失效 → 回首页并静默上报。
4. **L0** 🔴 顶栏三段遮罩对比度**每一张图都要满足**基线 9.09:1，不能只验第一张（UX-S3）。
5. **L0** 轮播参数（张数上限、停留时长、是否自动轮播、比例不一致时的高度策略）待 OD-6；**定值前不写死在代码里**。
6. **L0** 后台 banner 表单可配置跳转目标。
7. **L2** 模拟器验收：多图轮播、点击跳转正确、失效目标回首页、每张图的顶栏文字都清晰可读。

---

## Epic 9: 订单与财务后台

> 🔴 **前置**：admin 主题 Epic 10 完成。

**目标**：运营能搜到、筛到、导出该导的，对账能下钻到单。

### Story 9-1: 商品与库存列表的分页、搜索、筛选

As a 运营，
I want 在商品页和库存页能搜能筛能翻页，
So that 264 行不用靠 Ctrl+F。

**Acceptance Criteria:**

1. **L0** **商品页**：按名称、SPU 编号、SKU 编号搜索 + 分页。
2. **L0** **库存页**：分页 + 搜索 + 按品类 / 状态筛选（现状**什么都没有**）。
3. **L0** **排序只做总库存 + 状态**；🔴 **无销量维度**（销量字段已裁，须在需求交底时向运营明示，ACT-B）。
4. **L0** 走 admin 模板 A + htmx 局部更新（UX-S1）。
5. **L0** 遵守 AB-19A「零端点变更」：在既有列表端点上加参数，不新增端点。
6. **L1** 集成测试：按编号精确命中、按名称模糊命中、筛选与分页组合正确。

### Story 9-2: 订单五维搜索与下单账号信息

As a 运营，
I want 订单列表能按五种方式搜、并看到下单人是谁，
So that 我发货前不用逐单点进详情确认。

**技术背景**：订单列表刻意不放个人信息（这偏离了电商一期后台规格，ACT-D 补留痕）。

**Acceptance Criteria:**

1. **L0** 五维搜索：**订单号**（`display_no` 与 `legacy_display_no` 都能搜到同一单，依赖 4-3）/ 账号昵称 / 账号邮箱（受 OD-1 约束）/ 商品名 / SKU 编号。
2. **L0** **昵称**进订单列表与详情，可搜（昵称在社区里本就公开）。
3. **L0** **邮箱**：OD-1 定为做，则沿用「按电话搜索」的三道处置 —— 独立权限位 `shop.order_email_search` + 每次写审计 + **审计只记指纹不记原值**；🔴 加码必改 5 处。OD-1 定为不做，则本条与该权限码一并不实现。
4. **L0** 🔒 **不检索已注销账号的邮箱**；**已注销用户的订单显示「已注销用户」**。
5. **L0** 收货人姓名、电话、地址**仍只在详情中显示**，不进列表。
6. **L1** 集成测试：五个维度各命中一次；无权限账号搜邮箱被拒且有可读提示。

### Story 9-3: 订单导出

As a 运营，
I want 按当前筛选条件把订单导出成 Excel，
So that 我能做线下核对。

**Acceptance Criteria:**

1. **L0** 按当前筛选条件导出；上限 10,000 行。
2. **L0** 🔒 新增权限码 `shop.order_export`，**不默认授予运营专员**；🔴 加码必改 5 处。
3. **L0** **每次导出写审计**（谁、何时、什么条件、多少行）。
4. **L0** 单元格**公式转义**（SHOP-NFR-01）—— 昵称、收件人等用户可填字段。
5. **L0** 导出字段范围按 OD-10；定论前**不导出电话与详细地址**。
6. **L1** 集成测试：导出行数与列表一致；含 `=` 开头昵称的单元格被转义。

### Story 9-4: 对账明细下钻与导出

As a 运营，
I want 对不平的时候能点进去看是哪几单，
So that 周对账不用人肉抄 12 个数字。

**技术背景**：现状只有 12 个汇总数字 + from/to，无明细无导出。

**Acceptance Criteria:**

1. **L0** 汇总数字可**下钻到逐单明细**；⚠️ 走既有详情端点的 `HX-Request` 分支返回抽屉，**不新增端点**（AD-S13：对账下钻**不是** AB-19A 例外）。
2. **L0** 明细可导出，新增权限码 `shop.finance_export`；🔴 加码必改 5 处；导出写审计 + 公式转义。
3. **L0** **汇总与明细加总一致**（同一套查询口径，单测断言）。
4. **L0** 按周筛选沿用现有 from/to。
5. **L1** 集成测试：构造一周订单，汇总 = 明细加总；下钻结果与汇总口径一致。

### Story 9-5: 看板口径的页面呈现

As a 运营，
I want 看板上的数字旁边写清楚它是怎么算的，
So that 我不会误读。

**技术背景**：查询口径已在 Story 5-3 修正，本 story 只做页面呈现。

**Acceptance Criteria:**

1. **L0** 库存周转页展示修正后的售罄数（口径：所有未删除规格中可售库存为 0 的数量）。
2. **L0** 售后成本口径在页面上标注。
3. **L0** 后台 PawCoin 余额**区分「可用」与「冻结中」**（冻结中＝已被待支付订单占用、尚未扣减的部分）。
4. **L0** 走 admin 模板 B（只读看板）。
5. **L1** 数字与明细可对上。

---

## 开放项对 story 的影响

| 开放项 | 影响的 story | 不定的后果 |
|---|---|---|
| **OD-1** 邮箱搜订单做不做 | 9-2 | 决定是否加 `shop.order_email_search` 权限码 |
| **OD-4** 商品文案走不走内容审核 | 8-5 | 敏感词部分本版不做，等定论 |
| **OD-5** Lark 提醒发给谁 / 窗口 / 夜间静默 | 3-4 | 代码不写死收件人，先用默认值 |
| **OD-6** banner 轮播参数 | 8-6 | 参数不写死在代码里 |
| **OD-7** 批量改价 / 改库存输入模型 | 7-2, 7-3 | 已按推荐值实现，可改配置 |
| **OD-8** 处方药限制 | 8-4 | 本版不区分，发布前法务复核 |
| **OD-9** 订单号随机段长度与字符集 | 4-3 | 已按 Base32×6 实现，实施前可调 |
| **OD-10** 订单导出字段范围 | 9-3 | 定论前不导出电话与详细地址 |
| **OD-11** 客服号最终确认 | 3-1 | 号码是配置项，随时可改 |
| **OD-12** 评价提交端点去留 | 5-1 | 推荐关闭；不关则 5-1 的脱敏长期有效 |
| **OD-13** 外链图片来源白名单 | 7-6 | 白名单是配置项，开工前定范围 |

## 交付检查单

- [ ] 每条 AC 的验证层级已标注，云端跑的 story 在 Completion Notes 写明「L1/L2 待本地验收」
- [ ] 所有新迁移用时间戳版本号，提交前跑 `scripts/ci/check-flyway-versions.sh origin/main`
- [ ] 打包一律 `mvn -B clean package`
- [ ] 契约变更同改（后端 record + App DTO + 契约 test；C5 原文写四处，App mock 已随 `8e85b40d` 删除，实为三处）
- [ ] 新权限码改满 5 处（含 admin 预置角色默认授予与种子迁移）
- [ ] 三处**变异验证**已做：埋点属性白名单（1-2）、日志脱敏（5-1）、批量条件写与 SSRF 校验（7-1 / 7-6）
- [ ] ACT-A~E 五个行动项在发布前闭合
