-- v1.4.2 shop Story 5.1 —— Biteship 运单、Webhook 收件表与订单物流异常列（D-12 / D-13 / P-8 ④）。
--
-- ① shipments 改造：
--    · 存量 shipments 行全部是 1.4.2 前的手工运单 → 新列 provider 默认 'MANUAL'，迁移后语义不变；
--    · carrier 放宽为 VARCHAR(32) 并去掉三值 CHECK：Biteship 运单存快递码大写（JNE / JNT / SICEPAT …），
--      原三值 JNE / SICEPAT / ANTERAJA 仍合法（后台手工发货仍只允许三家，由应用层 Carrier.parse 把关）；
--    · Biteship 运单创建时可能还没有运单号（order.waybill_id 事件稍后补），也还没揽收 → tracking_no /
--      shipped_at 放开可空；状态加 CREATED（已建单未揽收）/ CANCELLED（运单已取消）。
--    · 保留原 ck_shipments_delivered_at、ck_shipments_cost、uq_shipments_carrier_tracking
--      （PG 唯一索引对 NULL 不冲突，tracking_no 为空的 Biteship 行不受影响）。
-- ② shop_orders 新列：发货来源、运单失败标记与重试计数（5.1）；物流异常组（由 Story 5.2 使用，本迁移一并建）。
--    一律可空或带默认，存量订单不变；存量已发货订单 shipped_source 不回填（NULL = 1.4.2 前手工发货）。
--    物流异常取值按决策 C-6：LOST_DAMAGED 拆为 LOST、DAMAGED。
--    ck_shop_orders_delivery_source 加 BITESHIP：DROP + ADD 重列全集，基数取 V20260818_0357 原文 4 值
--    （SHIPMENTS_ALL_DELIVERED / ADMIN_MARK / USER_CONFIRM / AUTO_TIMEOUT，当前树内唯一定义该约束的迁移）+ BITESHIP。
-- ③ biteship_webhook_events：Webhook 收件表（路径密钥 + 回查，载荷只当通知；只存白名单键，不存司机信息）。
--
-- 整数列一律 INTEGER、金额 BIGINT（不用短整型）。

-- ---------- ① shipments ----------
ALTER TABLE shipments DROP CONSTRAINT ck_shipments_carrier;
ALTER TABLE shipments ALTER COLUMN carrier TYPE VARCHAR(32);
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
    ADD COLUMN superseded_at        TIMESTAMPTZ,
    -- 本地发起取消的时刻（复审修复：Biteship 先推 cancelled 时据此判为预期取消、不告警）
    ADD COLUMN cancel_requested_at  TIMESTAMPTZ;
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

-- ---------- ② shop_orders ----------
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
    logistics_exception IN ('PICKUP_FAILED', 'RETURNED', 'STALLED', 'LOST', 'DAMAGED'));
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_waybill_attempts CHECK (waybill_attempts >= 0);
-- DeliverySource 加 BITESHIP：DROP + ADD 重列全集（V20260818_0357 原文 4 值 + BITESHIP）
ALTER TABLE shop_orders DROP CONSTRAINT ck_shop_orders_delivery_source;
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_delivery_source CHECK (
    delivery_source IS NULL OR delivery_source IN (
        'SHIPMENTS_ALL_DELIVERED', 'ADMIN_MARK', 'USER_CONFIRM', 'AUTO_TIMEOUT', 'BITESHIP'));
CREATE INDEX ix_shop_orders_logistics_exception ON shop_orders (logistics_exception_at)
    WHERE logistics_exception IS NOT NULL;

-- ---------- ③ Webhook 收件表 ----------
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

COMMENT ON COLUMN shipments.provider IS 'MANUAL = 后台手工运单（存量全部）；BITESHIP = Biteship 下单（v1.4.2 shop 5.1）。';
COMMENT ON COLUMN shipments.user_status_key IS '用户侧五段物流状态（D-12 映射表）；手工运单恒 NULL。';
COMMENT ON COLUMN shop_orders.logistics_exception IS '物流异常标记（Story 5.2 使用；C-6：LOST / DAMAGED 已拆分）。';
COMMENT ON TABLE biteship_webhook_events IS 'Biteship Webhook 收件表：只存白名单键（不含司机信息），处理一律回查 GET /v1/orders/:id。';
