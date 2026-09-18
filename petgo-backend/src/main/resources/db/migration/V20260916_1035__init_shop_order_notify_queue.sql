-- V1.3.0 shop-v2 Story 3-4（SHOP-FR-24 · 主线 C）：新订单 Lark 提醒的待发队列。时间戳版本号（决策 E7）。
--
-- 运营今天接单靠「人工去打开后台页面盯着」，零通知 —— 发货时效取决于有没有人想起来刷。
-- 本表是「付款成功 → 攒够一个窗口 → 汇总发一条 Lark」中间的那个攒。
--
-- 🔴 **它不是通知中心**：不发给用户、不进 notifications、不做 App 推送。
--    这是给运营的**运维信号**，收件人是一个 Lark 群，没有任何用户侧感知。
--
-- 🔴 **`shop_order_id` 上的唯一约束就是幂等键**：支付回调有双通道（回调 + 轮询），
--    同一订单的到账事件可能到两次。靠唯一约束兜底，**不引入任何分布式锁**
--    （SHOP-NFR-06：禁 Quartz / Kafka / Redis Stream / 任何队列或调度中间件）。
--
-- 🔴 **不加到 shop_orders 的外键**：登记发生在支付回调事务里，而登记失败**绝不允许**
--    回滚那个事务（回滚会连同意图的 markPaid 一起没掉，那才是真的丢账）。
--    外键会把「队列写失败」变成「支付事务失败」，与这条要求直接冲突。

CREATE TABLE shop_order_notify_queue (
    id            BIGSERIAL    PRIMARY KEY,
    shop_order_id BIGINT       NOT NULL,
    -- PENDING / SENT / FAILED（UPPER_SNAKE，与全仓枚举落库约定一致）
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count   INTEGER      NOT NULL DEFAULT 0,
    sent_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_shop_order_notify_queue_order UNIQUE (shop_order_id),
    CONSTRAINT ck_shop_order_notify_queue_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- 扫描器每轮按 (status, created_at) 取窗口内待发行。
CREATE INDEX ix_shop_order_notify_queue_pending
    ON shop_order_notify_queue (status, created_at);

COMMENT ON TABLE shop_order_notify_queue IS
    '新订单 Lark 提醒的**汇总待发队列**（Story 3-4 / SHOP-FR-24）。不是通知中心：不发用户、不进 notifications、无 App 推送，收件人是运营的 Lark 群。';
COMMENT ON COLUMN shop_order_notify_queue.shop_order_id IS
    'shop_orders.id。唯一约束即幂等键 —— 支付回调双通道（回调 + 轮询）会让同一订单的到账事件到两次。刻意无外键：登记在支付回调事务内，外键会把「队列写失败」升级成「支付事务回滚」。';
COMMENT ON COLUMN shop_order_notify_queue.status IS
    'PENDING=待发 / SENT=已发 / FAILED=重试超限放弃。mode=off 时扫描器静默跳过，行保持 PENDING。';
COMMENT ON COLUMN shop_order_notify_queue.retry_count IS
    '投递失败次数。超过上限转 FAILED 并记 error，不再重试 —— 一条发不出去的运维提醒不值得永远占着队列。';
