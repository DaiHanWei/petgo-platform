-- Story 9.3（AB-8B）：兽医咨询订单只读管理——建单重播快照 + 运营待核查标记。号 V79（当前 max V78；决策 E2）。
-- 加列不改旧迁移（决策 E2）。rebroadcast_count 建单时从 consult_requests 快照（建单即删 request，故落订单）。
-- admin_verify_* 为纯人工注记（无自动拦截，AB-7A 精神），不改订单业务状态、不触发退款。

ALTER TABLE consult_orders
    ADD COLUMN rebroadcast_count   INT         NOT NULL DEFAULT 0,   -- 建单时该 request 的重播次数快照
    ADD COLUMN admin_verify_status VARCHAR(12),                      -- NULL(未标记) / TO_VERIFY / VERIFIED
    ADD COLUMN admin_verify_note   VARCHAR(255),
    ADD COLUMN admin_verify_by     BIGINT,                           -- admin_accounts.id
    ADD COLUMN admin_verify_at     TIMESTAMPTZ;

ALTER TABLE consult_orders
    ADD CONSTRAINT ck_consult_orders_verify_status
        CHECK (admin_verify_status IS NULL OR admin_verify_status IN ('TO_VERIFY', 'VERIFIED'));

-- 待核查列表过滤（按标记态 + 时间倒序）。
CREATE INDEX ix_consult_orders_verify ON consult_orders (admin_verify_status, created_at DESC);
