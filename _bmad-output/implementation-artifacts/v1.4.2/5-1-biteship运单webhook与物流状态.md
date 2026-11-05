# Story 5.1: Biteship 运单、Webhook 与物流状态（后端）

Status: review

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全线 25 story 同一分支、云端单会话串行）
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9）> PRD `电商/1-4-2电商prd.md`（§3.0 旅程 D、§3.10 FR-172）> `architecture-v1.4.2-shop-delta.md`（D-12、D-13、D-24、§1.2 第 2 / 10 / 25 条、P-4 / P-7 / P-8 ④ / P-12）> UI 稿（F7 / F10 说明）（均在 `_bmad-output/planning-artifacts/v1.4.2/`）；跨 story 契约以 `_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md` 为准；云端执行以 `cloud-rules-shop.md` 为准。
> 前置：**1.1**（`BiteshipClient` / `BiteshipProperties` / stub 模式、SKU 包裹参数、`shipping_settings` 发货地列）、**3.2**（订单头 `courier_code / courier_service_code / courier_name / courier_service_name / insurance_selected / insurance_fee / discount_coupon_amount` 快照列）、**4.1**（`CancelReason`、`ShopOrderRefundService`，本 story 只读引用）、**2.1**（`ShopStagController` 与 stag 入口路径，见「待确认」1）。
> 范围层：**只后端**。后台页面不在本线（S-0 / S-2）：本 story 实现 service 层 + 测试，后台线只加 Controller 与页面。App 物流块归 9.1。
> 迁移：`V20261105_1640__shipments_biteship_and_logistics_exception.sql`（sprint-status 预分配；**5.2 的订单头物流异常列也在这条迁移里建**，5.2 只写逻辑）。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-6**：物流异常类型 `LOST_DAMAGED` 拆为 `LOST`、`DAMAGED`（订单头 `logistics_exception` 取值：`PICKUP_FAILED | RETURNED | STALLED | LOST | DAMAGED`）；用户侧「配送异常」条件为已确认的 `RETURNED / LOST / DAMAGED`；埋点 `exception_type ∈ returned | lost | damaged`。取消原因 `CancelReason.LOST_DAMAGED` 保持不拆。

## Story

As a 买家,
I want 订单详情自动显示快递与物流状态,
so that 不用自己去承运商官网查。

## Acceptance Criteria

> 层级：`[L0]` 纯单元 / 静态（云端可跑）· `[L1]` 需 Docker postgres + redis（本地）· `[L2]` Biteship sandbox 真调 / 真机（本地）。
> Biteship 事实（已调研，本 story 依据）：下单 `POST /v1/orders`（`origin_contact_name / origin_contact_phone / origin_address / origin_postal_code`、`destination_*` 同构、`courier_company`、`courier_type`、`delivery_type = now | scheduled`、`items`、`courier_insurance`、`reference_id` **唯一**，重复返回错误码 `40002060`），返回 `id`、`courier.waybill_id`、`courier.tracking_id`、`status`；取消 `POST /v1/orders/:id/cancel`（`cancellation_reason_code ∈ change_courier | pickup_delay | change_address | others`），**只有 `confirmed / scheduled / allocated / picking_up` 可取消**；查单 `GET /v1/orders/:id`；状态 15 值：`confirmed, scheduled, allocated, picking_up, picked, cancelled, on_hold, in_transit, dropping_off, return_in_transit, returned, rejected, disposed, courier_not_found, delivered`；Webhook 事件 `order.status / order.price / order.waybill_id`；**验签与重试机制文档未写**。

### AC1 — 迁移 ④ `V20261105_1640__shipments_biteship_and_logistics_exception.sql` `[L0]` `[L1]`（D-12、D-13、P-8 ④）

1. `shipments` 改造（存量行全部是手工运单，迁移后语义不变）：
   ```sql
   -- carrier 放宽：存 Biteship 快递码（大写，如 JNE / JNT / SICEPAT），原三值保留兼容存量
   ALTER TABLE shipments DROP CONSTRAINT ck_shipments_carrier;
   ALTER TABLE shipments ALTER COLUMN carrier TYPE VARCHAR(32);
   -- Biteship 运单创建时可能还没有运单号（order.waybill_id 事件稍后补），也还没揽收
   ALTER TABLE shipments ALTER COLUMN tracking_no DROP NOT NULL;
   ALTER TABLE shipments ALTER COLUMN shipped_at DROP NOT NULL;
   ALTER TABLE shipments ALTER COLUMN shipped_at DROP DEFAULT;
   ALTER TABLE shipments DROP CONSTRAINT ck_shipments_status;
   ALTER TABLE shipments ADD CONSTRAINT ck_shipments_status
       CHECK (status IN ('CREATED', 'SHIPPED', 'DELIVERED', 'CANCELLED'));
   ALTER TABLE shipments
       ADD COLUMN provider             VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
       ADD COLUMN biteship_order_id    VARCHAR(64),
       ADD COLUMN reference_id         VARCHAR(64),
       ADD COLUMN waybill_id           VARCHAR(64),
       ADD COLUMN tracking_id          VARCHAR(64),
       ADD COLUMN courier_code         VARCHAR(32),
       ADD COLUMN courier_service_code VARCHAR(32),
       ADD COLUMN provider_status      VARCHAR(32),
       ADD COLUMN provider_status_at   TIMESTAMPTZ,
       ADD COLUMN user_status_key      VARCHAR(24),
       ADD COLUMN tracking_url         VARCHAR(512),
       ADD COLUMN insurance_amount     BIGINT       NOT NULL DEFAULT 0,
       ADD COLUMN superseded_at        TIMESTAMPTZ;
   ALTER TABLE shipments ADD CONSTRAINT ck_shipments_provider CHECK (provider IN ('MANUAL', 'BITESHIP'));
   -- 手工运单必有单号；Biteship 运单必有 Biteship 订单 id
   ALTER TABLE shipments ADD CONSTRAINT ck_shipments_provider_fields CHECK (
       (provider = 'MANUAL'   AND tracking_no IS NOT NULL) OR
       (provider = 'BITESHIP' AND biteship_order_id IS NOT NULL));
   ALTER TABLE shipments ADD CONSTRAINT ck_shipments_user_status_key CHECK (user_status_key IS NULL OR
       user_status_key IN ('awaiting_pickup', 'picked', 'in_transit', 'out_for_delivery', 'delivered'));
   ALTER TABLE shipments ADD CONSTRAINT ck_shipments_insurance CHECK (insurance_amount >= 0);
   CREATE UNIQUE INDEX uq_shipments_biteship_order ON shipments (biteship_order_id) WHERE biteship_order_id IS NOT NULL;
   CREATE UNIQUE INDEX uq_shipments_reference ON shipments (reference_id) WHERE reference_id IS NOT NULL;
   -- 在途回查 / 停滞扫描（5.2）的驱动索引
   CREATE INDEX ix_shipments_biteship_active ON shipments (provider_status_at)
       WHERE provider = 'BITESHIP' AND superseded_at IS NULL AND status IN ('CREATED', 'SHIPPED');
   ```
   保留原 `ck_shipments_delivered_at`、`ck_shipments_cost`、`uq_shipments_carrier_tracking`（PG 唯一索引对 NULL 不冲突，`tracking_no` 为空的 Biteship 行不受影响）。
2. `shop_orders` 新列（5.1 用前三组，5.2 用物流异常组；**一律可空或带默认，存量订单不变**）：
   ```sql
   ALTER TABLE shop_orders
       ADD COLUMN shipped_source                VARCHAR(24),
       ADD COLUMN waybill_failed_at             TIMESTAMPTZ,
       ADD COLUMN waybill_fail_reason           VARCHAR(200),
       ADD COLUMN waybill_attempts              INTEGER     NOT NULL DEFAULT 0,
       ADD COLUMN logistics_exception           VARCHAR(24),
       ADD COLUMN logistics_exception_at        TIMESTAMPTZ,
       ADD COLUMN logistics_exception_confirmed BOOLEAN     NOT NULL DEFAULT false,
       ADD COLUMN claim_pending                 BOOLEAN     NOT NULL DEFAULT false,
       ADD COLUMN timer_resumed_at              TIMESTAMPTZ;
   ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_shipped_source
       CHECK (shipped_source IS NULL OR shipped_source IN ('MANUAL', 'BITESHIP_PICKED'));
   ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_logistics_exception CHECK (logistics_exception IS NULL OR
       logistics_exception IN ('PICKUP_FAILED', 'RETURNED', 'STALLED', 'LOST', 'DAMAGED'));   -- C-6 拆分（dev 对齐）
   ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_waybill_attempts CHECK (waybill_attempts >= 0);
   -- DeliverySource 加 BITESHIP：DROP + ADD 重列全集（取 V20260818_0357 原文全集 + 1）
   ALTER TABLE shop_orders DROP CONSTRAINT ck_shop_orders_delivery_source;
   ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_delivery_source CHECK (
       delivery_source IS NULL OR delivery_source IN (
           'SHIPMENTS_ALL_DELIVERED', 'ADMIN_MARK', 'USER_CONFIRM', 'AUTO_TIMEOUT', 'BITESHIP'));
   CREATE INDEX ix_shop_orders_logistics_exception ON shop_orders (logistics_exception_at)
       WHERE logistics_exception IS NOT NULL;
   ```
   存量已发货订单 `shipped_source` 不回填（NULL = 1.4.2 前手工发货，只在后台可见，App 不用）。
3. Webhook 收件表：
   ```sql
   CREATE TABLE biteship_webhook_events (
       id                BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
       biteship_order_id VARCHAR(64),
       event             VARCHAR(40)  NOT NULL,
       payload           JSONB        NOT NULL,
       received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
       processed_at      TIMESTAMPTZ,
       attempts          INTEGER      NOT NULL DEFAULT 0,
       last_error        VARCHAR(200),
       alerted_at        TIMESTAMPTZ,
       CONSTRAINT ck_biteship_webhook_events_attempts CHECK (attempts >= 0)
   );
   CREATE INDEX ix_biteship_webhook_events_pending ON biteship_webhook_events (received_at)
       WHERE processed_at IS NULL;
   ```
4. 整数列一律 `INTEGER`、金额 `BIGINT`，**文件中不得出现 `SMALLINT`**；文件头注释写明：存量 shipments 全部 `provider='MANUAL'`；`carrier` 放宽后原三值仍合法；`delivery_source` 约束值的基数来源（V20260818_0357 原文 4 值 + `BITESHIP`）；物流异常列由 5.2 使用。
5. 提交前 `bash scripts/ci/check-flyway-versions.sh origin/main`（云端跑不通在 Completion Notes 记一句）。

### AC2 — 实体与枚举 `[L0]` `[L1]`
1. `Shipment`（`B/shop/order/domain/Shipment.java`）：
   - `carrier` 由 `@Enumerated Carrier` 改为 `String`（列名不变，长度 32）；新增 `carrierDisplayName()`（`Carrier.fromCode(carrier)` 命中取 `displayName()`，否则原样返回码）与 `trackingUrl()`（手工运单取 `Carrier` 枚举官网；Biteship 运单取 `tracking_url` 列）。`Carrier` 加 `static Optional<Carrier> fromCode(String)`（不抛错），原 `parse` 不变（后台手工发货仍只允许三家）。
   - 新增 AC1.1 全部列的映射；`ShipmentStatus` 加 `CREATED`、`CANCELLED`（注释改写：接入 Biteship 后有了真实中间态来源）；新增 `ShipmentProvider { MANUAL, BITESHIP }`。
   - 新工厂 `Shipment.biteship(orderId, carrierCode, courierCode, serviceCode, biteshipOrderId, referenceId, waybillId, trackingId, carrierCost, insuranceAmount, now)` → `status=CREATED`、`shipped_at=null`、`user_status_key='awaiting_pickup'`。原 `Shipment.ship(...)`（手工）保持 `provider=MANUAL`、`status=SHIPPED`。
   - 领域方法：`markPicked(at)`（CREATED → SHIPPED，写 `shipped_at`；已 SHIPPED / DELIVERED 幂等）、`markDelivered(at)`（CREATED / SHIPPED → DELIVERED；从 CREATED 直达时 `shipped_at` 为空则同写 `at`；原幂等语义不变）、`markCancelled(at)`（仅 CREATED 可取消，否则抛 `AppException.conflict`）、`supersede(at)`、`isActive()`（`superseded_at == null && status != CANCELLED`）、`applyProviderStatus(raw, at, userKeyOrNull)`（`provider_status_at` 只前进不后退；`userKeyOrNull` 为 null 时不改 `user_status_key`）。
   - `toString()` 仍只含承运商 / 单号 / 状态（不含任何 PII）。
2. `ShipmentRepository.findByCarrierAndTrackingNo` 参数改为 `(String carrier, String trackingNo)`；新增 `findByBiteshipOrderId(String)`、`findActiveByShopOrderId(long)`（`superseded_at IS NULL AND status <> 'CANCELLED'`，按 id 倒序）、`findLatestByShopOrderId(long)`（同上取第一条，供详情）。
3. `ShopOrder`：映射 AC1.2 全部新列；新增枚举 `ShippedSource { MANUAL, BITESHIP_PICKED }`、`LogisticsException { PICKUP_FAILED, RETURNED, STALLED, LOST, DAMAGED }`（5.2 使用；C-6，dev 对齐）；`DeliverySource` 加 `BITESHIP`（注释：Biteship 回传签收）。新增 `markShipped(at, ShippedSource)` 重载（原 `markShipped(at)` 委托它并传 `MANUAL`），`recordWaybillFailure(at, reason)` / `clearWaybillFailure()` / `incrementWaybillAttempts()`。
4. `AUTO_DELIVER_AFTER` 由 `Duration.ofDays(7)` 改为 **`Duration.ofDays(14)`**（D-12，PRD 旅程 D 第 6 条）；`AUTO_COMPLETE_AFTER` 仍 7 天；`RETURN_WINDOW` 不变。同步改类注释与 `ShopOrderFulfillmentService` / `ShopOrderExpiryScanner` 注释里的「M=7」字样。
5. 改动所有 `getCarrier()` 调用点（共 4 处，见 Dev Notes「现状」表）及后台模板 `drawer-shop-order.html:137` 的 `p.carrier.displayName()` → `p.carrierDisplayName()`，否则订单抽屉 500。
6. `ddl-auto=validate` 下实体与表一致（`INTEGER ↔ int`、`BIGINT ↔ long`、`VARCHAR ↔ String/enum`、`JSONB` 走 `@JdbcTypeCode(SqlTypes.JSON)`，写法照 `ShopProduct.java:71`）。

### AC3 — Biteship 客户端补全 `[L0]` `[L1]` `[L2]`（D-9、P-7）
1. 在 1.1 已建的 `B/shared/logistics/biteship/BiteshipClient` 上把 `createOrder / cancelOrder / getOrder` 从签名 + stub 补成 live / sandbox 真实现（`RestClient`，连接 3s / 读 8s 超时，沿用 1.1 的 key 注入与 `BITESHIP_MODE`）：
   - `createOrder(BiteshipCreateOrderRequest)` → `CreateOutcome`：`Created(BiteshipOrderSnapshot)` / `Rejected(String code, String message)`（4xx，含 `40002060` 重复 reference）/ `Unknown(String cause)`（超时、5xx、网络错误——**结果不确定**）。
   - `cancelOrder(String biteshipOrderId, String reasonCode)` → `CancelOutcome`：`Cancelled` / `NotCancellable(String providerStatus)` / `Unknown(String cause)`。
   - `getOrder(String biteshipOrderId)` → `BiteshipOrderSnapshot`；失败抛 `BiteshipException`（1.1 已有类，dev 对齐：不另建 `BiteshipUnavailableException`；调用方计入重试，不当成「状态没变」）。
   - `BiteshipOrderSnapshot(String id, String status, String waybillId, String trackingId, String courierCompany, String courierType, Long price, String link, Instant updatedAt)`：只取这些字段，**driver 姓名 / 电话、收发件人信息一律不读入对象**。
2. stub 模式（L0 / L1 / 云端默认）：`createOrder` 返回 `id = "stub_" + referenceId`、`waybill_id = "STUBWB" + 10 位数字`、`status = "confirmed"`、`price` = 请求里的运费；`getOrder` 返回内存态（初始 `confirmed`）；`cancelOrder` 对 `confirmed / scheduled / allocated / picking_up` 返回 `Cancelled` 否则 `NotCancellable`。另提供**仅 stub 模式可用**的测试钩子 `BiteshipStubScript`（`setStatus(id, status)`、`failNextCreate(code)`、`timeoutNextCreate()`、`failNextGetOrder()`），供 L1 集成测试推进状态；live / sandbox 下该 bean 不存在。
3. 日志：请求 / 响应体不记日志；只记 `referenceId`、Biteship 订单 id、HTTP 状态、Biteship 错误码；**key、手机号、地址、联系人姓名不进日志**（P-7）。
4. `.env.example` 加占位 `BITESHIP_WEBHOOK_SECRET=`（1.1 已加的 `BITESHIP_API_KEY / BITESHIP_MODE / BITESHIP_COURIERS` 不重复加）；`BiteshipProperties` 加 `webhookSecret`、`deliveryType`（默认 `now`）、`webhookMaxAttempts`（默认 10）。

### AC4 — 下运单 `BiteshipShipmentService.createWaybill` `[L0]` `[L1]` `[L2]`（D-12、FR-172.6、P-7）
新类 `B/shop/logistics/BiteshipShipmentService`（供后台线调用，也供 AC10 stag 入口），方法均带 `Long actorAccountId` 并写审计（`AdminAuditService.record`，新常量见 AC10.3）：
1. `createWaybill(long orderId, Long actorAccountId)` 前置校验（**任何一条不过都不调 Biteship**，抛 `AppException.conflict` / `validation`，不写失败标记）：
   - 订单 `PENDING_SHIPMENT`；无 `isActive()` 的运单（有则 409「该订单已有运单」）；
   - 订单有 `courier_code / courier_service_code` 快照（3.2 列）——为空的是 1.4.2 前的存量订单或旧结算模式订单之外的异常数据 → 409「存量订单请走手工运单」；
   - 收件快照 `ship_receiver_name / ship_receiver_phone / ship_address_line` 非空（注销用户的订单已被清成空串 → 409）。
2. 组装请求（纯函数 `BiteshipWaybillRequestAssembler`，L0 单测）：
   - origin：`shipping_settings.origin_contact_name / origin_contact_phone / origin_address / origin_postal_code`（1.1 列），任一为空 → **记运单失败**（原因 `ORIGIN_NOT_CONFIGURED`，见 AC5），不调用；
   - destination：收件人姓名、电话（去掉 `+`、空格、连字符，只留数字；`+62812…` → `62812…`，L2 再核 Biteship 接受的格式）、地址（`address_line, kecamatan, kota_kabupaten, provinsi` 逗号拼接）、`postal_code = ship_kode_pos`；
   - `courier_company = 订单 courier_code`、`courier_type = 订单 courier_service_code`、`delivery_type = BiteshipProperties.deliveryType`；
   - `items`：按订单行逐项，`name = 商品名 + " " + 规格名`（截断 100 字）、`value = unit_price`、`quantity = qty − refunded_qty`（**部分取消的件不发**；剩 0 的行跳过；全部为 0 → 409「订单已无可发货商品」）、`weight / length / width / height` 取 SKU 当前包裹参数（1.1 列）；任一 SKU 包裹参数为空 → 记运单失败（原因 `SKU_PACKAGE_MISSING`），不调用；
   - `courier_insurance`：`insurance_selected = true` 时 = 商品实付 = `goods_subtotal − discount_coupon_amount − 该单 LINE 级退款单的 goods_refund 之和`（4.1 表），下限 0；未勾选不传；
   - `reference_id = display_no + "-" + (waybill_attempts + 1)`（如 `TOKO-20261105-ABC123-1`），**每次调用前先在短事务里 `waybill_attempts + 1` 并提交**，保证重试换新 reference。
3. 调用顺序（P-7，**外部调用不在持有行锁的事务内**）：短事务 ① 校验 + `waybill_attempts + 1` → 提交 → 调 `createOrder` → 短事务 ② 落结果（重读订单并重新校验仍为 `PENDING_SHIPMENT`；若期间订单被取消 → 立即 `cancelOrder(..., "others")` 并 WARN，不建运单行）。
4. `Created` → 写 `Shipment.biteship(...)`：`carrier = courier_code 大写`、`carrier_cost = snapshot.price`（承运成本，AB-13A 毛利口径）、`insurance_amount = 上面的保价额`、`tracking_no = waybill_id`（可空）、`provider_status = snapshot.status`、`provider_status_at = now`、`tracking_url = snapshot.link`（仅 `https://` 开头才存）；清订单 `waybill_failed_at / waybill_fail_reason`；**订单仍 `PENDING_SHIPMENT`**（PRD 旅程 D 第 1 条，2026-11-05 拍板）。
5. 审计摘要只记订单 token、`referenceId`、Biteship 订单 id、快递码；不记收件人任何字段。

### AC5 — 运单失败、重试与告警 `[L0]` `[L1]`（D-12、PRD「研发须对重新下单做报警」）
1. `Rejected` / `Unknown` / 前置数据缺失（`ORIGIN_NOT_CONFIGURED`、`SKU_PACKAGE_MISSING`）→ 订单写 `waybill_failed_at = now`、`waybill_fail_reason`（`<类别>:<Biteship 错误码或简述>`，≤ 200 字，**不含地址 / 电话 / 姓名**），订单仍 `PENDING_SHIPMENT`；返回给调用方一个结果对象（`WaybillResult{created, failReason}`），**不抛异常**（后台据此提示运营：重下 / 取消 / 手工运单）。
2. `Unknown`（超时等结果不确定）的原因前缀固定 `UNKNOWN_RESULT`，并在 WARN 日志里提示「Biteship 侧可能已建单，重试前请在 Biteship 后台按 referenceId 核对」——因为每次重试换新 reference，盲目重试可能出两张运单。
3. `retryWaybill(long orderId, Long actorAccountId)`：前置同 AC4.1（无活动运单）；实现即再走一遍 `createWaybill`，但**计入全局重试计数**。
4. 全局重试告警：每次 `retryWaybill` 调用（不论成败）对 Redis 键 `shop:biteship:waybill-retry` `INCR`，首次设 TTL 5 分钟；计数 **≥ 10** 时写 ERROR 日志 + 发飞书告警（AC9 告警通道），同一 5 分钟窗口只告警一次（`SETNX shop:biteship:waybill-retry-alerted` TTL 5 分钟）。Redis 不可用时只记 ERROR，不影响重试本身。
5. `cancelWaybill(long orderId, String reasonCode, Long actorAccountId)`：取该单活动的 Biteship 运单；无 → 幂等返回；`provider_status` 已是 `cancelled` → 只落本地 `CANCELLED`；否则外部调用 `cancelOrder`（事务外）→ `Cancelled` 落本地 `markCancelled` / `NotCancellable` 抛 409（「Biteship 运单已在 {status}，无法取消」，由运营决定后续）/ `Unknown` 抛 503 不改本地。`reasonCode` 只接受 Biteship 四值（`change_courier | pickup_delay | change_address | others`），否则 422。本方法供 5.2 揽收失败处置调用。

### AC6 — Webhook 接收 `POST /api/v1/webhooks/biteship/{secret}` `[L0]` `[L1]`（D-12、I14）
1. 新 `B/shop/logistics/BiteshipWebhookController`；`SecurityConfig` 精确放行 `.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/biteship/*").permitAll()`（注释写明：路径密钥 + 回查，载荷只当通知）。
2. 密钥校验：`BiteshipProperties.webhookSecret` 为空 → **一律 404**（fail closed）；否则 `MessageDigest.isEqual(secret.getBytes(UTF_8), configured.getBytes(UTF_8))` 常量时间比较，不等 → 404。404 **不带 ProblemDetail 体**（`ResponseEntity.notFound().build()`），避免 `instance` 字段回显路径。
3. 请求体 > 64 KB → 413 不落表；JSON 解析失败 → 400 不落表；**体内无 `order_id`（Biteship 配置 Webhook 时的连通性测试）→ 200、不落表**。
4. 正常：先在短事务里写 `biteship_webhook_events`（`event` 取载荷 `event`，缺省 `order.status`；`biteship_order_id` 取 `order_id`；`payload` **只保留白名单键** `event, order_id, courier_tracking_id, courier_waybill_id, courier_company, courier_type, status, order_price, updated_at`，其余键（含 `courier_driver_*` 等司机信息）丢弃）→ 提交后回 **200**；随后发布 `BiteshipWebhookReceivedEvent(eventId)`。
5. 控制器内部任何异常（落表失败除外）都不向外抛 ProblemDetail：落表失败 → 500 空体（Biteship 若重发则下次再收）；落表成功后的任何问题都已与响应无关。

### AC7 — 异步处理与回查 `[L0]` `[L1]`（D-12：不信任载荷）
1. 监听 `BiteshipWebhookReceivedEvent`：`@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)`，**监听方法本身不开事务**，调 `BiteshipTrackingService.process(eventId)`。
2. `process(eventId)`：读收件行（已处理 → 返回）→ **事务外**调 `getOrder(biteship_order_id)` → 新开短事务（`REQUIRES_NEW`）调 `applySnapshot(shipment, snapshot)` 并写 `processed_at = now`；**载荷里的 `status / waybill / price` 一律不用**（伪造载荷不改状态，单测断言）。
3. 失败（`getOrder` 抛错、乐观锁冲突、找不到运单）→ `attempts + 1`、`last_error`（类名或 `UNKNOWN_ORDER`，≤ 200 字），留待重试；`attempts` 达 `webhookMaxAttempts`（默认 10）：
   - 找不到对应运单（`UNKNOWN_ORDER`，可能是 Biteship 测试事件或别的环境）→ 置 `processed_at`、WARN，**不告警**；
   - 其他原因 → 置 `alerted_at`、ERROR 日志 + 飞书告警，**不置 `processed_at`**（留给人工排查，不再自动重试：重试任务只捞 `alerted_at IS NULL` 的行）。
4. 重试任务 `BiteshipWebhookRetryJob`：`@Scheduled(fixedDelayString = "${petgo.shop.biteship.webhook-retry-ms:300000}")`，~~事务内 `pg_try_advisory_xact_lock(hashtext('biteship_webhook_retry'))`~~（dev 对齐：改用 Redis `SET NX PX` 锁，照 3.3 `ShopPaymentReconcileJob` 拍板⑤——xact 锁要求整轮在事务内，与回查须在事务外冲突）拿不到即跳过；每轮最多 100 行（`processed_at IS NULL AND alerted_at IS NULL AND received_at < now() - 1 分钟`，按 `received_at`）；逐行调 `process`（事务外调网关，同 AC7.2）。同任务顺带删除 `processed_at < now() - 30 天` 的行（每轮最多 1000 行）。
5. 在途回查 `BiteshipActivePollJob`：`@Scheduled(cron = "${petgo.shop.biteship.poll-cron:0 0 */6 * * *}", zone = "UTC")`，防重入锁（dev 对齐：同上改 Redis 锁）；对所有 `provider='BITESHIP' AND superseded_at IS NULL AND status IN ('CREATED','SHIPPED')` 的运单逐条 `getOrder` → `applySnapshot`（单次最多 500 条，单条失败只 WARN 不中断）。兜底 Biteship 不重发 Webhook。
6. **幂等**：`applySnapshot` 对同一 `status` 重复到达无副作用（`provider_status` 相同且 `waybill / price / link` 不变 → 不写库、不发事件）；`updatedAt` 早于已存 `provider_status_at` 的快照不回退状态。

### AC8 — 状态映射与订单推进 `[L0]` `[L1]`（D-12 映射表、FR-172.6）
1. `BiteshipStatusMapper`（纯函数，**单测穷举 15 值 + 未知值**）输出 `Mapping(userStatusKey | null, OrderAction)`：

   | Biteship `status` | `user_status_key` | `OrderAction` |
   |---|---|---|
   | confirmed / scheduled / allocated / picking_up | `awaiting_pickup` | `NONE` |
   | picked | `picked` | `SHIP` |
   | in_transit / on_hold | `in_transit` | `SHIP`（兜底） |
   | dropping_off | `out_for_delivery` | `SHIP`（兜底） |
   | delivered | `delivered` | `DELIVER` |
   | courier_not_found | null（不变） | `FLAG_PICKUP_FAILED` |
   | return_in_transit / returned | null（不变） | `FLAG_RETURNED` |
   | rejected / disposed / cancelled | null（不变） | `ALERT_OPS` |
   | 其他未知值 | null（不变） | `ALERT_OPS`（WARN，原值照存 `provider_status`） |
2. `applySnapshot` 在短事务内（订单行 `@Version` 乐观锁）：
   - 总是更新运单 `provider_status / provider_status_at`（只前进）、`waybill_id`（非空才覆盖，同步写 `tracking_no`）、`tracking_id`、`carrier_cost = price`（`order.price` 事件即由此落地；用户已付运费不变）、`tracking_url`（https 才存）；`user_status_key` 仅在映射非 null 时更新。
   - **只对活动运单推进订单**（`isActive()`；已 superseded / CANCELLED 的运单只记 `provider_status`）。
   - `SHIP`：运单 `markPicked`；订单为 `PENDING_SHIPMENT` → `markShipped(at, BITESHIP_PICKED)`（`at` = 快照 `updatedAt`，缺省 now）并在提交后发 `SHOP_ORDER_SHIPPED` 通知（AC8.4）；订单已 `SHIPPED / DELIVERED / COMPLETED` → 不动订单；订单为其他状态（`CANCELLED / REFUNDING / REFUNDED`）→ 不动订单、WARN + 飞书告警「已取消订单的运单被揽收」。
   - `DELIVER`：运单 `markDelivered`；订单为 `PENDING_SHIPMENT` 先 `markShipped(at, BITESHIP_PICKED)`；然后若**该单全部活动运单都已送达** → `markDelivered(at, DeliverySource.BITESHIP)`（签收时刻取最后一个活动运单的 `delivered_at`，同现有 S-2 口径）；订单不在 `PENDING_SHIPMENT / SHIPPED` → 不动订单、WARN。**订单带 `RETURNED` / `LOST` / `DAMAGED` 物流异常标记时不推进订单**（C-6，dev 对齐），只记运单状态并告警（交运营判断；5.2 细化）。
   - `FLAG_PICKUP_FAILED` / `FLAG_RETURNED`：订单 `logistics_exception` 为空或为 `STALLED` 时写入 `PICKUP_FAILED` / `RETURNED`、`logistics_exception_at = now`、`logistics_exception_confirmed = false`；已有其他异常不覆盖；写入后飞书提醒运营。（标记之后的暂停计时、确认、清除由 5.2 实现。）
   - `ALERT_OPS`：只记运单状态 + 飞书提醒运营（订单 token、Biteship 状态），订单不动。
3. 订单状态推进全部经 `ShopOrder` 领域方法与 `ShopOrderStatus.canTransitionTo`，不开新边。
4. Biteship 推动的「已发货」：发布 `ShopOrderShippedEvent(orderId, userId, orderToken)`（dev 对齐：带 userId / token 免监听方回读），监听 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 调 `NotificationService.send(userId, SHOP_ORDER_SHIPPED, …)`（参数照 `AdminShopOrderService.notifyShipped`:258-262，失败只 WARN）；后台手工发货路径**不改**（仍在 `AdminShopOrderService` 内同步发）。不新增通知类型。

### AC9 — 飞书告警通道 `[L0]` `[L1]`
1. 复用电商飞书通道（`shop/order/notify`：`LarkMessageClient.sendText` + `ShopOrderNotifyProperties`）。**dev 对齐：3.3 已建 `shop/order/service/ShopPaymentAlerts.alert(String)`（mode off / 收件人空不出网、失败只 WARN、事务内挂 afterCommit），本 story 直接复用，未新建 `ShopOpsAlertService`**（以下为原计划，未实施）： `B/shop/order/notify/ShopOpsAlertService.alert(String dedupeKey, String text)`：
   - `mode != live` 或 `receiveId` 为空 → 只写 ERROR 日志、不出网；
   - 同一 `dedupeKey` 30 分钟内只发一次（Redis `SETNX shop:ops-alert:<key>` TTL 30 分钟；Redis 不可用时照发）；
   - 发送失败只 WARN，**绝不向调用方抛异常**（不能让告警失败回滚业务）；
   - 告警文本只含订单展示号、Biteship 订单 id / 状态、计数，**不含收件人信息**。
2. 本 story 的告警点：AC5.4 重试激增、AC7.3 Webhook 处理 10 次失败、AC8.2 已取消订单被揽收 / `ALERT_OPS` 状态 / 物流异常标记写入。

### AC10 — 手工发货、标记发货与 stag 入口 `[L0]` `[L1]`
1. 手工运单（`provider=MANUAL`）路径保留（`ShopOrderFulfillmentService.ship` / 后台 `POST /admin/shop/orders/{token}/ship`）：写 `shipped_source = MANUAL`；**订单存在活动的 Biteship 运单时拒绝**（409「该订单已有 Biteship 运单，请先取消」，由 5.2 的处置方法先取消再转手工）。
2. 新增 `ShopOrderFulfillmentService.markShippedWithoutWaybill(String orderToken, Long actorAccountId)`（供后台线「标记已发货」，PRD 旅程 D：运营确认打包后标记）：要求订单 `PENDING_SHIPMENT` 且存在活动的 Biteship 运单；订单 → `SHIPPED`、`shipped_source = MANUAL`；运单状态不动（等 Biteship 回传 `picked`）；发 `SHOP_ORDER_SHIPPED`（同 AC8.4 事件）；审计。
3. 新审计常量（`AuditActions`）：`SHOP_WAYBILL_CREATED`、`SHOP_WAYBILL_FAILED`、`SHOP_WAYBILL_CANCELLED`、`SHOP_ORDER_MARKED_SHIPPED`；四个 `messages*.properties` 补 `admin.audit.action.*` 键（照 `SHOP_BANNER_CREATED` 写法）。
4. 手工运单与「标记已发货」两种来源只在后台可查（`shipped_source`），**App 侧一律不区分**（PRD 旅程 D 第 1 条）。
5. stag 入口（D-24，`@StagOnly`）：在 2.1 建立的 `ShopStagController` 上加「下运单」薄包装，调 `createWaybill(orderId, null)` 并返回 `WaybillResult`；另加「推进 stub 状态」入口（仅 `BITESHIP_MODE=stub` 时可用，调 `BiteshipStubScript.setStatus` 后立即 `applySnapshot`），供 App story 与 stag 验收造数。**路径前缀沿用 2.1 实际落地的前缀**（见「待确认」1）。

### AC11 — 自动送达兜底 14 天 `[L0]` `[L1]`
1. `AUTO_DELIVER_AFTER = 14 天` 后，`isAutoDeliverDue` 第 14 天整不到期、超过才到期；自动完成仍送达后 7 天。
2. 自动送达 / 后台标记送达 / 用户确认收货三条出口逐运单 `markDelivered` 时**只处理活动运单**（跳过 superseded / CANCELLED，否则 `CANCELLED → DELIVERED` 违反领域规则）；`markShipmentDelivered`（后台逐包裹）对非活动运单返回 409。
3. 「全部包裹都送达」判定改为「全部**活动**运单都送达」（`ShopOrderFulfillmentService.markShipmentDelivered`:132-140 与 AC8.2 共用一个私有方法）。
4. 存量手工发货订单同样按 14 天（D-12 全局改）。

### AC12 — 快递原因重发（新建运单、旧运单保留）`[L0]` `[L1]`（FR-172.8）
1. `reshipForCourierFault(long orderId, Long actorAccountId)`：要求订单 `SHIPPED`（或 `DELIVERED` 且带 `RETURNED` 异常）且有活动 Biteship 运单；对旧运单 `supersede(now)`（**不调 Biteship 取消**：已揽收的运单 Biteship 不允许取消）；然后按 AC4.2 组装新运单（同快递同服务、新 reference）调用 `createOrder`；订单状态不变。失败按 AC5 记失败（订单状态不变）。
2. 物流异常标记与 `claim_pending` 的清除 / 保留由 5.2 处理，本方法不碰。
3. 订单详情 `logistics` 取最新活动运单（AC13）；旧运单保留在 `packages`（现有字段）中供追溯。

### AC13 — 订单详情 `logistics` 出参 `[L0]` `[L1]`（D-19 物流部分、F7）
1. `ShopOrderDetailView` **末尾追加** `Logistics logistics`（无运单 = null）：
   ```java
   public record Logistics(String provider,            // MANUAL | BITESHIP
                           String courierName,          // Biteship：订单 courier_name 快照；手工：carrierDisplayName()
                           String courierServiceName,   // Biteship：订单 courier_service_name；手工：null
                           String waybillId,            // Biteship：waybill_id（可空，尚未分配）；手工：tracking_no
                           String trackingUrl,          // 手工：Carrier 官网；Biteship：tracking_url（可空）
                           String statusKey,            // 手工恒 null（不显示物流状态）；Biteship：user_status_key
                           String exception,            // 5.2 填；本 story 恒 null
                           String exceptionType) { }    // 5.2 填；本 story 恒 null
   ```
   取「最新活动运单」；该单只有非活动运单（全被取消）→ null。
2. 现有 `packages` 字段保留（存量 App 在用），按 id 升序列出全部运单（含 superseded / CANCELLED），`Package` 记录追加 `provider`、`superseded`（boolean）两字段于末尾。
3. 原有 `of(...)` 重载签名不变（测试直接调用 5 次），新字段由新重载 / 组装方法填，旧重载填 null（全局 NON_NULL 不下发）。

### AC14 — 接口请求日志脱敏 `[L0]`（§1.2 第 25 条、I14）
1. `ApiAccessLoggingFilter` 新增 `static String redactPath(String uri)`：匹配 `^/api/v1/webhooks/biteship/[^/?]+` → 替换为 `/api/v1/webhooks/biteship/***`，其余原样返回；`logExchange`（:90-99）中 `original.getRequestURI()` 改为 `redactPath(...)`。
2. 同样的 `redactPath` 用在 `GlobalExceptionHandler`（:137、:165 两处 WARN 日志与 :331 `pd.setInstance(...)`）——方法不支持 / 媒体类型不支持时这两处会把带密钥的 URI 写进日志或响应体。抽到 `B/shared/logging/LogPaths.redact(String)` 公共静态方法，两处共用。
3. `LogSanitizer.SENSITIVE_KEYS` 追加 `courier_driver_name`、`courier_driver_phone`、`courier_driver_plate_number`、`courier_driver_photo_url`（Biteship 载荷里的司机信息；现有 `phone` 是整键匹配，`courier_driver_phone` 不会被命中）。
4. 本 story 新增代码的所有日志：只记订单 token / 展示号、Biteship 订单 id、referenceId、快递码、状态；不记收发件人姓名 / 电话 / 地址、key、Webhook 密钥。

### AC15 — 回归 `[L0]` `[L1]`
1. `./mvnw -B clean package -DskipTests` 绿（**必须 clean**）。
2. 按设计被打破、需改断言的既有测试（改断言表达新规则，不删、不 skip）：`ShopOrderFulfillmentStateMachineTest.exitThreeAutoDeliverAfterSevenDays`（7 → 14 天）、`autoCompleteAfterSevenDays` 若引用 `AUTO_DELIVER_AFTER` 一并核对；`ShopOrderFulfillmentIntegrationTest.exitThreeAutoDeliverByScan`（`backdateShipped(…, 8)` → 15）与 `freshShipmentNotAutoDelivered`（6 → 13）；所有以 `Carrier.ANTERAJA` 等枚举调 `fulfillment.ship(...)` 的集成测试签名不变（`ship` 仍收 `Carrier`），只有 `Shipment.getCarrier()` 返回类型变了的断言要改为比较字符串。
3. 后台订单抽屉（`AdminShopOrderEndpointIntegrationTest`）渲染不 500。

## Tasks / Subtasks

- [x] **T0 开工核对**：在 `feat/1.4.2-shop`；`db/migration` 无 `V20261105_1640` 撞号；对照 1.1 / 2.1 / 3.2 / 3.3 / 4.1 **已提交代码**核对本文件引用的名字（`BiteshipClient` 结构、`BiteshipProperties` 前缀、订单头快递列字段名、`ShopStagController` 路径、3.3 是否已建飞书告警组件、4.1 `ShopOrderRefund` 的 LINE 级 `goods_refund` 字段名），有出入先改本文件并在 Completion Notes 记一句
- [x] **T1 迁移 + 实体**（AC1、AC2）：迁移；`Shipment` / `ShipmentStatus` / `ShipmentProvider` / `Carrier.fromCode`；`ShopOrder` 新列与枚举；`DeliverySource.BITESHIP`；`AUTO_DELIVER_AFTER = 14`；4 处 `getCarrier()` 调用点 + 抽屉模板
- [x] **T2 客户端**（AC3）：`createOrder / cancelOrder / getOrder` live 实现 + stub 内存态 + `BiteshipStubScript`；`BiteshipProperties` 新字段；`.env.example`
- [x] **T3 下运单**（AC4、AC5、AC12）：`BiteshipWaybillRequestAssembler`（纯函数）、`BiteshipShipmentService`（create / retry / cancel / reship）、重试计数与告警
- [x] **T4 告警通道**（AC9）：复用或新建 `ShopOpsAlertService`
- [x] **T5 Webhook**（AC6、AC7）：`BiteshipWebhookController`、`BiteshipWebhookEvent` 实体 + 仓库、`BiteshipWebhookReceivedEvent` + 监听、`BiteshipTrackingService`、`BiteshipWebhookRetryJob`、`BiteshipActivePollJob`；`SecurityConfig` 一条规则
- [x] **T6 映射与推进**（AC8、AC11）：`BiteshipStatusMapper`；`applySnapshot`；`ShopOrderShippedEvent` + 监听；活动运单口径改造 `ShopOrderFulfillmentService` 三条出口
- [x] **T7 手工 / 标记发货 / stag**（AC10）：`ship` 加活动 Biteship 运单拦截与 `shipped_source`；`markShippedWithoutWaybill`；审计常量 + messages；stag 入口
- [x] **T8 详情出参**（AC13）：`Logistics` 记录与装配；`Package` 追加字段
- [x] **T9 日志脱敏**（AC14）：`LogPaths.redact`；`ApiAccessLoggingFilter`、`GlobalExceptionHandler` 接入；`LogSanitizer` 加键
- [x] **T10 测试**：L0 全写并跑绿；L1 写好能编译（云端不跑）
- [x] **T11** `./mvnw -B clean package -DskipTests` + 指定 L0；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（改动前必读，2026-11-05 核 `dev_1.4.2` @ 2c507755）

路径前缀 `B/` = `petgo-backend/src/main/java/com/tailtopia/`，`R/` = `petgo-backend/src/main/resources/`。

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `R/db/migration/V20260818_0357__init_shipments.sql` | `shipments`：`carrier VARCHAR(16) NOT NULL` + `ck_shipments_carrier IN (JNE,SICEPAT,ANTERAJA)`；`tracking_no NOT NULL`；`status` 只 `SHIPPED/DELIVERED`；`shipped_at NOT NULL DEFAULT now()`；`uq_shipments_carrier_tracking(carrier, tracking_no)`；`ck_shipments_delivered_at`；`shop_orders.delivery_source` 约束 4 值 | 新迁移按 AC1 放宽 / 重建（**不改本文件**，已应用） | `ck_shipments_delivered_at`、唯一索引 |
| `B/shop/order/domain/Shipment.java` | `@Enumerated Carrier carrier`（:34-36）；只有 `ship(...)` 工厂（:64-85，status=SHIPPED、shippedAt=now）；`markDelivered`（:93-100）幂等 | AC2.1 | `markDelivered` 不改写已送达时刻 |
| `B/shop/order/domain/Carrier.java` | 三值 + `parse` 未知值抛错（:42-51） | 加 `fromCode`（不抛） | `parse` 行为（后台手工发货仍只三家） |
| `B/shop/order/domain/ShipmentStatus.java` | 两态，注释写「不接承运商 API 所以不建中间态」 | 加 `CREATED / CANCELLED`，改注释 | — |
| `B/shop/order/domain/DeliverySource.java` | 4 值 | 加 `BITESHIP` | — |
| `B/shop/order/domain/ShopOrder.java` | `markShipped(at)`（:316-323）一单多包第二次不改写；`AUTO_DELIVER_AFTER = 7 天`（:310）；`isAutoDeliverDue`（:372-376）；`@Version`（:188） | AC2.3 / AC2.4 | 发货时刻只写一次 |
| `B/shop/order/domain/ShopOrderStatus.java` | 边集中定义（:61-79）；无 `SHIPPED → COMPLETED` | **不改** | 全部 |
| `B/shop/order/repository/ShipmentRepository.java` | `findByCarrierAndTrackingNo(Carrier, String)`（:14） | 参数改 String；新增 3 个查询 | — |
| `B/shop/order/service/ShopOrderFulfillmentService.java` | `ship`（:67-92）；`markDeliveredByAdmin`（:102-114）遍历全部包裹；`markShipmentDelivered`（:122-142）「全部包裹送达」；`confirmReceipt`（:155-178）SHIPPED 时遍历全部包裹置送达；`autoDeliverOne`（:206-220）同 | AC10.1 / AC10.2 / AC11.2-3：只处理活动运单；`ship` 拦截活动 Biteship 运单、写 `shipped_source` | 三条出口都写签收时刻 |
| `B/shop/order/service/ShopOrderExpiryScanner.java` | `scanFulfillment` 每 10 分钟（:75-111）；注释「M=7」 | 只改注释 | 节奏与分段 try/catch |
| `B/shop/order/repository/ShopOrderRepository.java` | `findAutoDeliverDue`（:186-193）、`findAutoCompleteDue`（:196-202） | 本 story 不改查询（5.2 加异常排除与 `timer_resumed_at`） | — |
| `B/shop/order/dto/ShopOrderDetailView.java` | 记录 22 字段；`Package` 用 `s.getCarrier().name()/displayName()/trackingUrl()`（:164-168）；三个 `of` 重载（:108-172） | AC13 | 旧重载签名 |
| `B/admin/shop/service/AdminShopOrderService.java` | `ship`（:65-81）`Carrier.parse` → `fulfillment.ship` → 审计 → `notifyShipped`（:258-269，同步发、失败只 WARN） | 不改调用；审计摘要 `carrier.name()` 不变 | 发货推送行为 |
| `B/admin/shop/web/AdminShopOrderController.java` | `s.getCarrier().displayName()`（:260、:267）；`model.addAttribute("carriers", Carrier.values())`（:234） | 改 `s.carrierDisplayName()` | 下拉仍三家 |
| `R/templates/admin/fragments/drawer-shop-order.html` | :137 `${p.carrier.displayName()}`（注释警告写错整个抽屉 500） | 改 `${p.carrierDisplayName()}` | 其余不动 |
| `B/shop/order/notify/LarkMessageClient.java` | `sendText(text)`（:64-95），失败抛 `LarkSendException`；`ShopOrderNotifyProperties` `mode` 默认 off、`receiveId` 空即不发 | AC9 复用 | 新订单汇总提醒行为 |
| `B/shared/logging/ApiAccessLoggingFilter.java` | 只记 `/api/**`；`logExchange`（:76-100）原样打印 `getRequestURI()`；`redactQuery`（:123-148）只管 query | AC14.1 | query 打码行为 |
| `B/shared/logging/LogSanitizer.java` | `SENSITIVE_KEYS`（:27-48）整键小写匹配（:107-109） | 加 4 个司机键 | 现有键 |
| `B/shared/error/GlobalExceptionHandler.java` | :137 / :165 WARN 日志带 URI；:331 `pd.setInstance(URI.create(req.getRequestURI()))` | 接 `LogPaths.redact` | 其余 |
| `B/shared/security/SecurityConfig.java` | `/pay/callback` permitAll（:198）；`/api/v1/shop/**` 各条（:263-282）；`/internal/**` 是商家内部接口链（:64-74，`InternalApiGuardFilter` 只认独立端口 + 共享密钥） | 加 webhook 放行一条 | 其余顺序 |
| `B/admin/shared/StagOnly.java` | `@Profile("stag")` 元注解 | stag 入口使用 | — |
| `B/shared/ratelimit/RedisRateLimiter.java` | `check` 超限抛 429（:30-38） | **不用于告警计数**（告警计数不能抛 429），直接用 `StringRedisTemplate.increment / setIfAbsent` | — |
| `B/topic/service/TopicHeatJob.java` | advisory lock 先例 `SELECT pg_try_advisory_xact_lock(hashtext('topic_heat'))`（:47） | 两个新任务照写 | — |
| `B/shop/domain/ShopProduct.java:71` | `@JdbcTypeCode(SqlTypes.JSON)` 先例 | 收件表 `payload` 照写 | — |
| `B/admin/audit/service/AdminAuditService.java:69` | `record(actor, action, targetType, targetId, summary)`；shop 模块已有依赖先例（`AdminShippingZoneService` 等） | 新审计常量 | — |

### 拍板点（按建议实现，Completion Notes 复述）

- **① 运单行状态加 `CREATED / CANCELLED`、`shipped_at` 放开可空**：架构只列了新列，没说状态与 NOT NULL 约束；不放开则「运单已建、未揽收」只能写成 `SHIPPED + shipped_at=now`，与「运单创建后订单仍待发货」（PRD 旅程 D）自相矛盾。
- **② `carrier` 列存 Biteship 快递码大写**：与存量 `JNE / SICEPAT / ANTERAJA` 同形，`Carrier.fromCode` 命中三家时还能复用官网链接；原始小写码另存 `courier_code`。
- **③ 新增 `user_status_key`、`tracking_url`、`reference_id` 三列**（架构列表外）：映射表里多个 Biteship 状态对应「（不变）」，不存上一次的用户侧状态就无法下发；F7 稿要求 Biteship 运单也给「Lacak di situs kurir」；`reference_id` 用于追溯与唯一约束。已列入「待确认」2。
- **④ 每次下运单换新 `reference_id`，超时结果按「不确定」处理并提示运营核对**：Biteship 无按 reference 查单接口（调研未见），复用同一 reference 只会拿到 `40002060` 而拿不到已建单的 id。
- **⑤ Webhook 收件表只存白名单字段**：载荷含司机姓名 / 电话；处理只靠 `order_id` 回查，其余字段只用于排障。
- **⑥ 有 `RETURNED / LOST_DAMAGED` 标记的订单收到 `delivered` 不自动推进**：退回途中的 `delivered` 语义不明（可能是退回发件地），交运营判断。
- **⑦ 新人工「标记已发货」不登记新运单**：Biteship 订单已有运单号，运营只是确认打包；登记第二个运单会让「全部运单送达」永远等不到。

### 实现提示

- `createWaybill` 骨架（P-7：外部调用不在持锁事务内）：
  ```java
  public WaybillResult createWaybill(long orderId, Long actor) {
      Prepared p = tx.execute(s -> prepare(orderId));          // 校验 + waybill_attempts+1 + 组装请求；数据缺失返回 Prepared.failed(reason)
      if (p.failed()) return tx.execute(s -> recordFailure(orderId, p.reason(), actor));
      CreateOutcome out = client.createOrder(p.request());      // 事务外
      return tx.execute(s -> settle(orderId, p, out, actor));   // 重读订单、重新校验、落运单或失败标记
  }
  ```
  `tx` 用 `TransactionTemplate`（`PROPAGATION_REQUIRES_NEW`），**方法本身不加 `@Transactional`**；调用方（后台 Controller）也不能在外层开事务（Javadoc 写明）。
- `applySnapshot` 里的订单推进与 `ShopOrderFulfillmentService` 共用「全部活动运单是否送达」私有逻辑——抽到 `ShopOrderFulfillmentService` 的包内方法或 `Shipments.allActiveDelivered(list)` 静态工具，别写两份。
- `BiteshipStatusMapper` 用 `switch` 穷举字符串，未知值走 default；测试用 `@ParameterizedTest @CsvSource` 列 15 行 + 2 个未知值（`""`、`"foo"`）+ null。
- Webhook 控制器读原始 body：`@RequestBody byte[] body`，自己用 `JsonMapper` 解析（同 `LarkMessageClient` 的 `tools.jackson.databind.json.JsonMapper`），便于做体积限制与白名单。
- Biteship `updated_at` 可能是带时区的 ISO 字符串，解析失败用 `now`。
- 飞书告警文本示例：`[Biteship] 5 分钟内重下运单 12 次，请技术排查`、`[Biteship] 订单 TOKO-20261105-ABC123 运单状态 rejected，请处理`。

### 架构约束

- D-12：运单服务、Webhook 路径密钥 + 回查、收件表、5 分钟重试、6 小时在途回查、映射表、手工发货保留、自动送达 14 天、快递原因重发新建运单。
- D-13（部分）：物流异常列由本 story 迁移建立、映射写入 `PICKUP_FAILED / RETURNED`；其余归 5.2。
- P-4：Biteship 客户端在 `B/shared/logistics/biteship/`，运单与物流在 `B/shop/logistics/`。
- P-7：外部调用在事务外；日志脱敏。P-12：Webhook 回查（伪造载荷不改状态）必须有测试。
- 护栏：不引 MQ / 缓存 / 新依赖（`RestClient` 直连）；`ddl-auto=validate`；对外不暴露运单自增 id（`Package` 现有字段里没有 id，保持）；写库监听 AFTER_COMMIT + REQUIRES_NEW；`BITESHIP_MODE=stub` 为默认。

### 与前后 story 的边界（本 story 不做）

- **1.1**：`rates` 询价、报价落库、stub 模式骨架（本 story 只补 `createOrder / cancelOrder / getOrder` 与 stub 状态脚本）。
- **4.1**：取消原因、退款单；本 story 不改任何取消 / 退款路径。平台原因取消已有活动运单的订单时是否先 `cancelWaybill`，由后台线编排（见「待确认」4）。
- **5.2**：异常标记后的暂停计时、`timer_resumed_at`、停滞检测、运营确认 / 清除、确认收货清标记、揽收失败三种处置、`logistics.exception` 出参。
- **6.2**：订单详情其余字段（金额明细、`paymentMethods`、`refunds[]`、`displayStatus` 等）与订单列表。
- **9.1**：App 物流块与五段状态展示。
- **10.2**：Biteship sandbox 全链路联调（L2）。

### 测试

**L0（云端可跑，纯单元，无 DB / Spring 上下文）**
- `shop/logistics/BiteshipStatusMapperTest`：15 值逐一 + 未知值 / 空串 / null。
- `shop/logistics/BiteshipWaybillRequestAssemblerTest`：`reference_id` 序号；电话归一；地址拼接；部分取消件数扣减、剩 0 行跳过、全 0 拒绝；SKU 包裹参数缺失 → `SKU_PACKAGE_MISSING`；发货地缺失 → `ORIGIN_NOT_CONFIGURED`；保价额 = 商品实付 − LINE 级已退商品额、未勾选不传。
- `shop/logistics/BiteshipShipmentServiceTest`（Mockito，`BiteshipClient` mock）：前置不过不调用客户端；`Created` 落运单且订单仍 `PENDING_SHIPMENT`；`Rejected / Unknown` 写失败标记不抛异常；`Unknown` 原因前缀；第二短事务发现订单已取消 → 调 `cancelOrder`；`retryWaybill` 计数达 10 告警一次、第 11 次不重复告警；`cancelWaybill` 三种结果；`reshipForCourierFault` 旧运单 superseded 且不调 Biteship 取消。
- `shop/logistics/BiteshipWebhookControllerTest`：配置密钥为空 → 404；密钥不符 → 404 且无响应体；无 `order_id` → 200 不落表；超 64KB → 413；落表 payload 不含 `courier_driver_phone`；发布 `BiteshipWebhookReceivedEvent`。
- `shop/logistics/BiteshipTrackingServiceTest`：**载荷写 `delivered`、回查返回 `confirmed` → 订单不变**（伪造载荷）；`picked` → 订单 `SHIPPED` + `BITESHIP_PICKED` + 发 `ShopOrderShippedEvent`；`delivered` → `DELIVERED` + `BITESHIP`；同状态重复到达不写库不发事件；旧 `updatedAt` 不回退；superseded 运单状态不推进订单；`courier_not_found` → `PICKUP_FAILED`；`returned` → `RETURNED`；已有 `LOST` 时不覆盖（C-6）；`rejected` → 告警；已取消订单被揽收 → 告警不改订单；`getOrder` 抛错 → attempts+1；第 10 次非 UNKNOWN_ORDER → `alerted_at` + 告警；UNKNOWN_ORDER 第 10 次 → `processed_at` 不告警。
- `shop/order/ShipmentDomainTest`：`CREATED → SHIPPED → DELIVERED`；`CREATED → DELIVERED` 补 `shipped_at`；`markCancelled` 仅 CREATED；`isActive`；`provider_status_at` 只前进；`carrierDisplayName` 三家与未知码。
- `shop/order/ShopOrderFulfillmentStateMachineTest`（改）：自动送达 14 天边界（第 14 天整不到期、+1 秒到期）。
- `shop/logistics/ShipmentsBiteshipMigrationTest`（照 `notify/preference/UserPushMutesMigrationTest` 读文件去注释）：`ck_shipments_status` 四值、`ck_shipments_provider_fields`、`ck_shop_orders_delivery_source` 含原 4 值 + `BITESHIP`、`biteship_webhook_events` 与部分索引、无 `SMALLINT`。
- `shop/order/dto/ShopOrderDetailViewLogisticsTest`：无运单 → `logistics` null；手工运单 → `statusKey` null、`trackingUrl` 为官网；Biteship 取最新活动运单、superseded 不选；全取消 → null；`packages` 含全部运单且带 `superseded`。
- `shared/logging/ApiAccessLogPathRedactionTest`：`/api/v1/webhooks/biteship/abc123` → `***`；带 query 的也打码；其他路径原样；`LogSanitizerTest`（补）：四个司机键被打码。
- `shop/order/service/ShopOpsAlertServiceTest`（若本 story 新建）：mode off 不出网；同 key 30 分钟只发一次；发送异常不外抛。

**L1（需 Docker postgres + redis，云端不跑，待本地验收）**
- `shop/logistics/BiteshipShipmentIntegrationTest`（`extends ApiIntegrationTest`，`BITESHIP_MODE=stub`）：已付款订单下运单 → 运单 `CREATED`、订单 `PENDING_SHIPMENT`；`failNextCreate` → 失败标记；重试成功清标记；`reshipForCourierFault` 旧运单 superseded、新运单 CREATED；`cancelWaybill`；手工 `ship` 遇活动 Biteship 运单 409；`markShippedWithoutWaybill` → `SHIPPED + MANUAL`，之后 `picked` 不再改订单。
- `shop/logistics/BiteshipWebhookIntegrationTest`：错误密钥 404；正确密钥落表 200；stub 推进 `picked` 后 Webhook → 订单 `SHIPPED`、通知中心出现 `SHOP_ORDER_SHIPPED`；`delivered` → `DELIVERED / BITESHIP`；伪造载荷不改状态；重复 Webhook 无副作用；`getOrder` 失败留表、重试任务处理后 `processed_at` 写入；在途回查任务推进状态；access log 里路径为 `***`。
- `shop/order/ShopOrderFulfillmentIntegrationTest`（改）：15 天回拨自动送达、13 天不送达；后台标记送达 / 用户确认跳过已取消运单。
- `admin/shop/web/AdminShopOrderEndpointIntegrationTest`：抽屉含 Biteship 运单时渲染正常。
- 迁移：`ddl-auto=validate` 启动通过；存量 `shipments` 行 `provider='MANUAL'`。
- **L2**：sandbox key 下运单 / 取消 / 查单、真实 Webhook 推送到 stag（归 10.2）。
- 云端只跑 L0（dev 最终列表）：`./mvnw -B test -Dtest='BiteshipStatusMapperTest,BiteshipWaybillRequestAssemblerTest,BiteshipShipmentServiceTest,BiteshipWebhookControllerTest,BiteshipTrackingServiceTest,ShipmentDomainTest,ShopOrderFulfillmentStateMachineTest,ShipmentsBiteshipMigrationTest,ShopOrderDetailViewLogisticsTest,ShopOrderDetailViewContractTest,ApiAccessLogPathRedactionTest,ApiAccessLogQueryRedactionTest,LogSanitizerTest,LiveBiteshipClientTest,LiveBiteshipClientWaybillTest,StubBiteshipClientTest,BiteshipConfigTest,AuditActionLabelsTest,AdminMessagesParityTest' -Dsurefire.failIfNoSpecifiedTests=false`（`LANG=C.UTF-8 LC_ALL=C.UTF-8`）。Completion Notes 标「**L1/L2 待本地验收**」。

### Project Structure Notes

- 新文件：`B/shop/logistics/`（`BiteshipShipmentService`、`BiteshipWaybillRequestAssembler`、`BiteshipWebhookController`、`BiteshipTrackingService`、`BiteshipStatusMapper`、`BiteshipWebhookRetryJob`、`BiteshipActivePollJob`、`domain/BiteshipWebhookEvent`、`repository/BiteshipWebhookEventRepository`、`event/BiteshipWebhookReceivedEvent`、`WaybillResult`）；`B/shop/order/domain/ShipmentProvider`、`ShippedSource`、`LogisticsException`；`B/shop/order/event/ShopOrderShippedEvent` + 监听；`B/shared/logging/LogPaths`；（如需）`B/shop/order/notify/ShopOpsAlertService`。
- `B/shared/logistics/biteship/` 补 DTO：`BiteshipCreateOrderRequest`、`BiteshipOrderSnapshot`、`CreateOutcome`、`CancelOutcome`、`BiteshipUnavailableException`、`BiteshipStubScript`。
- 接口路径 `/api/v1/webhooks/biteship/{secret}`（无鉴权，路径密钥）。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-shop.md#Story 5.1]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md#§1.2 第 2/10/25 条、D-9、D-12、D-13、D-24、P-4、P-7、P-8 ④、P-12、§3.6 第 4 条]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md#§3.0 旅程 D、§3.10 FR-172（报价、运单与订单状态规则 / 物流状态与减免额展示）]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-shop.md#S-2、11-05 按推荐定下（Webhook 路径密钥 + 回查、自动送达 14 天）、I14]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html#F7（物流块：快递名·服务、运单号 Salin、Lacak di situs kurir、五段状态；手工运单不显示状态）]
- [Source: _bmad-output/implementation-artifacts/v1.4.2/cloud-rules-shop.md]
- [Source: CLAUDE.md#实现一个 story 的纪律 第 5 / 6 条、强制护栏]

## 待确认 / 发现的规划冲突

1. ~~stag 入口路径与商家内部接口链冲突~~：2026-11-05 收口为 `/admin/stag/shop/**`，挂在后台链 `/admin/**` 下（会话登录 + `hasRole('SUPER_ADMIN')`，与 `AdminPaymentSimulateController` 同），类上 `@StagOnly`（生产不注册）；`SecurityConfig` 后台链只对 `/admin/stag/**` 免 CSRF（便于验收时带后台会话 cookie 用 curl 调用），不走商家内部端口 / `X-Internal-Secret`（架构 D-24）。
2. **架构列外新增三列**（`shipments.user_status_key`、`tracking_url`、`reference_id`）与 `shop_orders.waybill_attempts`、运单状态加 `CREATED / CANCELLED`、`tracking_no / shipped_at` 放开可空：均为落地映射表「（不变）」、F7「Lacak di situs kurir」、重试计数与「运单已建未揽收」所必需，已按最保守方式加在本 story 的预分配迁移里。
3. **Biteship 运单的「去承运商官网查询」链接来源未定**：本 story 取 `getOrder` 的 `courier.link`（https 才存），拿不到时为 null（App 不显示该链接）；L2 用 sandbox 核对该字段是否存在。
4. **平台原因取消（缺货等）时已存在的未揽收运单**：4.1 的取消服务不会调 Biteship 取消。本 story 提供 `cancelWaybill`，但不改 4.1 的取消路径；建议后台线在「取消订单」按钮里先调 `cancelWaybill(…, "others")` 再调取消服务（与 5.2 揽收失败处置同一编排）。若希望服务层强制，需回规划确认在 4.1 的 `cancelWhole` 前加钩子（外部调用须在事务外）。
5. **重试换新 reference 的重复建单风险**：超时（结果不确定）后重试可能在 Biteship 留下两张运单，本 story 只能提示运营核对；若 Biteship 支持按 `reference_id` 查单，可改为先查后建（L2 核实）。
6. **收件人电话格式**：订单快照为 `+62…`，本 story 只去掉符号传 `62…`；Biteship 是否要求 `08…` 本地格式未查到，L2 核实。
7. **告警接收方**：PRD 说重下单激增「通知技术排查」，本 story 复用电商飞书通道（运营发货群）；若要单独发给技术群，需新增配置项（未发明）。

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5（claude-opus-5-5），bmad-dev-story 流程，云端 L0。

### Debug Log References

- `./mvnw -B clean package -DskipTests`：BUILD SUCCESS。
- 最终 L0 `-Dtest`（19 类）：`BiteshipStatusMapperTest,BiteshipWaybillRequestAssemblerTest,BiteshipShipmentServiceTest,BiteshipWebhookControllerTest,BiteshipTrackingServiceTest,ShipmentDomainTest,ShopOrderFulfillmentStateMachineTest,ShipmentsBiteshipMigrationTest,ShopOrderDetailViewLogisticsTest,ShopOrderDetailViewContractTest,ApiAccessLogPathRedactionTest,ApiAccessLogQueryRedactionTest,LogSanitizerTest,LiveBiteshipClientTest,LiveBiteshipClientWaybillTest,StubBiteshipClientTest,BiteshipConfigTest,AuditActionLabelsTest,AdminMessagesParityTest` → 复审修复后 193 用例全绿（`ShopOpsAlertServiceTest` 未建，见名字对齐）。
- 全量非集成单测（`unit-tests.sh` 列出的 733 类）：5836 用例全绿（4-2 遗留的 `ConsultShopBoundaryTest` 已由协调者 4d6843fc 修复）。
- `bash scripts/ci/check-flyway-versions.sh origin/main`：OK（275 个迁移，无重号）。
- 调试记录：Mockito `verify(events).publishEvent(any())` 会解析到 `publishEvent(ApplicationEvent)` 重载、`never()` 断言空转，测试统一改为 `any(Object.class)`。

### Completion Notes List

- **L1/L2 待本地验收**：AC1.6 / AC2.6（`ddl-auto=validate` 启动、迁移在真库执行、存量 shipments 行 `provider='MANUAL'`）、AC3 live / sandbox 真调（L2，10.2）、AC4~AC8、AC10~AC12 的真链路，以及 AC15.3 后台抽屉渲染。对应测试：`BiteshipShipmentIntegrationTest`、`BiteshipWebhookIntegrationTest`（新写，已编译未运行）、`ShopOrderFulfillmentIntegrationTest`（改 14 天断言与 `getCarrier()` 字符串）、`Epic4ChainIntegrationTest`（8 → 15 天）、`AdminShopOrderEndpointIntegrationTest`（未改，需本地确认抽屉不 500）。
- 迁移 `V20261105_1640__shipments_biteship_and_logistics_exception.sql`（预分配号）：按 AC1 落地；**物流异常 CHECK 按 C-6 用 `LOST`、`DAMAGED` 取代 `LOST_DAMAGED`**；`ck_shop_orders_delivery_source` 取 V20260818_0357 原文 4 值 + `BITESHIP`（树内唯一定义该约束的迁移）；无 SMALLINT。
- 拍板点①~⑦按建议实现：运单 `CREATED / CANCELLED`、`shipped_at / tracking_no` 可空；`carrier` 存快递码大写；新增 `user_status_key / tracking_url / reference_id`；每次调用换新 reference、`Unknown` 前缀 `UNKNOWN_RESULT` 并 WARN 提示核对；收件表只存白名单键；`RETURNED / LOST / DAMAGED` 标记下 `delivered` 不推进（运单也不置送达，只记 provider_status + 告警）；「标记已发货」不登记新运单。
- 4.1 `ShopWaybillCanceller` 由 `BiteshipShipmentService` 实现：`cancelForPlatformCancel` **只发布 `ShopWaybillCancelRequestedEvent`**，`BiteshipPlatformCancelListener`（`@Async` + `AFTER_COMMIT`，`fallbackExecution=true`）在取消事务提交后才调 Biteship；只取消 `CREATED`（未揽收）运单；失败由本 story 写 WARN + 运营告警（不再向 4.1 调用方抛——那时取消已提交，与「不阻断取消与退款」一致）。未改 4.1 对外契约与 `ShopOrderRefundService`。
- AC12 实现偏差（更保守）：旧运单 `supersede` 放在**新运单建成功之后**的短事务②里；新运单失败时旧运单仍为活动运单（否则订单 SHIPPED 却无活动运单，`retryWaybill` 又要求待发货，形成死路），运营可再次 `reshipForCourierFault`。
- AC13.3 实现：`Logistics` 在主 `of(...)` 里按传入 `shipments` 计算；旧重载传 `List.of()` 时自然为 null（NON_NULL 不下发）——签名全部不变；`Package` 追加 `provider`、`superseded`，并按 id 升序。
- 自动送达兜底 `AUTO_DELIVER_AFTER` 7 → 14 天；三条出口与 `markShipmentDelivered` 只处理活动运单，「全部送达」判定抽到 `Shipments.allActiveDelivered` 与 Biteship 签收共用。手工 `ship` 遇活动 Biteship 运单 409，写 `shipped_source=MANUAL`。
- `BiteshipStubScript` 为包装类（不实现 `BiteshipClient`，避免按类型注入出现两个候选），由 `BiteshipConfig#biteshipStubScript` 在实际装配为桩时返回实例、否则返回 null（空 bean，`ObjectProvider` 取不到）。
- `ShopOrderFulfillmentService` 构造器新增 `ApplicationEventPublisher`、`AdminAuditService`（`markShippedWithoutWaybill` 发事件 + 审计），全仓无手工 new 该类。
- 后台抽屉：`p.carrierDisplayName()`；运单状态 `CREATED` 复用 `admin.v130.shopOrders.status.*` 命名空间新增一键（4 份 messages）；`CANCELLED` 键已存在。
- 日志脱敏：`LogPaths.redact` 供 access log 与 `GlobalExceptionHandler`（405 / 415 WARN、ProblemDetail `instance`）共用；`LogSanitizer` 加 4 个司机键。
- `.env.example` 加 `BITESHIP_WEBHOOK_SECRET=` 占位；`application.yml` 加 `webhook-secret / delivery-type / webhook-max-attempts / webhook-retry-ms / poll-cron`。
- `LiveBiteshipClient.cancelOrder` 遇 4xx 时额外回查一次 `getOrder` 取当前状态填 `NotCancellable(status)`（回查失败为 `unknown`）。

#### 复审修复（2026-11-05）

1. **[中] 物流异常下的 delivered**：`BiteshipTrackingService.applyInTx` 在订单带 `RETURNED / LOST / DAMAGED` 时收到 `delivered` → **不写 `provider_status` / `user_status_key`**（也不置运单送达），只 WARN + 告警；因此下一次回查 / Webhook 仍判为有变化、重新判定，5.2 清标记后（或 AC12 新运单签收）正常推进。单测 `deliveredBlockedByReturned` 断言用户侧状态未变、第二次回查仍处理、清标记后转 DELIVERED。代价：标记未清期间每次回查都会再告警一次。
2. **[低] 告警 / WARN 用展示号**：`BiteshipShipmentService` 孤儿运单的日志与告警改用 `display_no`（`Prepared` 带展示号），不再出现订单自增 id。
3. **[低] 建单成功但短事务②抛错**：外层捕获 → 按孤儿运单处理（事务外 `cancelOrder(..., others)`，取消不成功告警，用展示号），返回 `failed("SETTLE_FAILED")`；短事务②改用 `ShopOrderRepository.findByIdForUpdate`（`PESSIMISTIC_WRITE`，该段无外部调用）防并发写两条活动运单。单测 `settleFailureCancelsOrphan`、`settleLocksOrderAndOrphanAlertUsesDisplayNo`。
4. **[低] 本地取消后 Biteship 先推 cancelled**：迁移给 `shipments` 加 `cancel_requested_at`（本迁移从未应用，可改），`cancelWaybill` 调 Biteship 前先登记；回查到 `cancelled` 时若运单仍 `CREATED` 且（已登记本地取消 或 订单 `CANCELLED / REFUNDING / REFUNDED`）→ 直接落本地 `CANCELLED`、不告警；其余仍告警。单测 `expectedCancelNoAlert`、`unexpectedCancelAlerts`、`cancelMarksRequestedBeforeCall`。
5. **[低] Webhook 读体**：`BiteshipWebhookController.receive` 改收 `HttpServletRequest`：先校验密钥（不符 404，不读体）、`Content-Length` > 64 KB 直接 413，再用 `readNBytes(64KB + 1)` 有上限读取（chunked 超限同样 413）。单测 `rejectsBeforeReadingBody` 及 chunked 用例。
6. **[记录不改]** 见「待确认」5~7。

#### 对本 story 文件做的名字对齐（以前序已提交代码为准）

- `BiteshipUnavailableException` → 沿用 1.1 的 `BiteshipException`。
- `LogisticsException` / 迁移 CHECK：`LOST_DAMAGED` → `LOST`、`DAMAGED`（收口补充 C-6）。
- AC9 `ShopOpsAlertService` → 复用 3.3 `ShopPaymentAlerts`（因此无 `ShopOpsAlertServiceTest`；30 分钟去重未做：各告警点本身已幂等——状态无变化不重复处理、收件行告警一次即停、重试激增有独立 SETNX 窗口）。
- AC7.4 / 7.5 advisory xact 锁 → Redis 锁（3.3 拍板⑤先例）。
- `ShopOrderShippedEvent(orderId)` → `(orderId, userId, orderToken)`。
- 1.1 `BiteshipCreateOrderRequest` 已有 4 参 record：扩为全量字段并保留 4 参构造器（桩 / 旧调用）。
- stag 入口实际前缀 `/admin/stag/shop/**`：`POST /admin/stag/shop/orders/{token}/waybill`、`POST /admin/stag/shop/orders/{token}/waybill/stub-status`（body `{status}`）。

#### 待确认

1. 原「待确认」2~7 仍有效（新增列、Biteship 链接字段、平台取消编排、重试重复建单风险、电话格式、告警接收方）；其中 4 已由本 story 以 4.1 钩子 + 提交后异步取消落地，只取消未揽收运单。
2. AC9 30 分钟去重未实现（见上），如需严格去重需在 `ShopPaymentAlerts` 上加 key 版本（会碰 3.3 组件）。
3. 带 `RETURNED / LOST / DAMAGED` 时收到 `delivered`：复审后连 `provider_status / user_status_key` 都不写、运单不置送达，每次回查重新判定并告警，直到 5.2 / 运营处理标记；若希望改为只告警一次需另加字段（未做）。
4. 物流异常标记（`logistics_exception_at`）、Biteship 建单 / 取消时刻用本地 `now`，状态推进时刻用 Biteship `updated_at`（缺省 now）——两种时钟混用，停滞判定（5.2）与签收时刻若需统一口径，10.2 用 sandbox 核对 `updated_at` 是否可靠后再定。
5. 在途回查每轮最多 500 条，按 `provider_status_at` 升序（最久未更新优先）；在途 > 500 时依赖下一轮轮转，未做游标分页，量级上来后（10.2 / 运营反馈）再评估。
6. 旧版 App 的 `packages[].status` 会看到新值 `CREATED / CANCELLED`、并多出 `provider / superseded` 字段：9.1 需确认旧版 App 对未知包裹状态的兼容（不崩、显示合理），必要时服务端对旧版过滤。

### File List

后端（main）：
- `petgo-backend/src/main/resources/db/migration/V20261105_1640__shipments_biteship_and_logistics_exception.sql`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/domain/{Shipment,ShipmentStatus,Carrier,DeliverySource,ShopOrder}.java`（改）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/domain/{ShipmentProvider,ShippedSource,LogisticsException,Shipments}.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/repository/{ShipmentRepository,ShopOrderRepository}.java`（改；后者复审加 `findByIdForUpdate`）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/service/{ShopOrderFulfillmentService,ShopOrderExpiryScanner}.java`（改）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/event/ShopOrderShippedEvent.java`、`shop/order/notify/ShopOrderShippedNotifyListener.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/order/dto/ShopOrderDetailView.java`（改）
- `petgo-backend/src/main/java/com/tailtopia/shop/logistics/{BiteshipShipmentService,BiteshipWaybillRequestAssembler,BiteshipStatusMapper,BiteshipTrackingService,BiteshipWebhookController,BiteshipWebhookListener,BiteshipPlatformCancelListener,BiteshipWebhookRetryJob,BiteshipActivePollJob,BiteshipJobLock,WaybillResult}.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/logistics/domain/BiteshipWebhookEvent.java`、`repository/BiteshipWebhookEventRepository.java`、`event/{BiteshipWebhookReceivedEvent,ShopWaybillCancelRequestedEvent}.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shared/logistics/biteship/{BiteshipProperties,BiteshipConfig,LiveBiteshipClient,StubBiteshipClient}.java`、`dto/BiteshipCreateOrderRequest.java`（改）；`BiteshipStubScript.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/shop/stag/ShopStagController.java`（改）
- `petgo-backend/src/main/java/com/tailtopia/admin/audit/service/AuditActions.java`、`admin/shop/web/AdminShopOrderController.java`（改）
- `petgo-backend/src/main/java/com/tailtopia/shared/logging/{ApiAccessLoggingFilter,LogSanitizer}.java`（改）、`LogPaths.java`（新）；`shared/error/GlobalExceptionHandler.java`、`shared/security/SecurityConfig.java`（改）
- `petgo-backend/src/main/resources/templates/admin/fragments/drawer-shop-order.html`、`i18n/messages{,_en,_id,_zh_CN}.properties`、`application.yml`（改）；`petgo-backend/.env.example`（改）

后端（test）：
- 新：`shop/logistics/{LogisticsFixtures,BiteshipStatusMapperTest,BiteshipWaybillRequestAssemblerTest,BiteshipShipmentServiceTest,BiteshipWebhookControllerTest,BiteshipTrackingServiceTest,ShipmentsBiteshipMigrationTest,BiteshipIntegrationSupport,BiteshipShipmentIntegrationTest,BiteshipWebhookIntegrationTest}.java`、`shop/order/ShipmentDomainTest.java`、`shop/order/dto/ShopOrderDetailViewLogisticsTest.java`、`shared/logging/ApiAccessLogPathRedactionTest.java`、`shared/logistics/biteship/LiveBiteshipClientWaybillTest.java`
- 改：`shop/order/ShopOrderFulfillmentStateMachineTest.java`、`shop/order/ShopOrderFulfillmentIntegrationTest.java`、`shop/Epic4ChainIntegrationTest.java`、`shop/order/dto/ShopOrderDetailViewContractTest.java`、`shared/logging/LogSanitizerTest.java`、`shared/logistics/biteship/{LiveBiteshipClientTest,StubBiteshipClientTest}.java`

### Change Log

- 2026-11-05：创建 story 文件（ready-for-dev）。
- 2026-11-05：dev-story 实现（后端 L0 全绿），名字按前序代码对齐，Status → review；L1/L2 待本地验收。
- 2026-11-05：复审修复 1~5（物流异常下 delivered 不写状态、告警用展示号、落库失败孤儿运单处理 + 订单行写锁、预期 cancelled 不告警 + `cancel_requested_at`、Webhook 先鉴权再有上限读体），待确认补 4~6；L0 193 / 全量单测 5836 全绿。
