-- V1.4.2 shop-admin Story 4.1 · AD-13（AB-38）：Biteship 预估余额 + 预警阈值 + 运单实际保价费。
-- 🔴 不重复建 shipping_settings.origin_* / free_shipping_cap / shipping_zones.postal_code（已在 V20261105_1605）。
-- 预估 = 最新快照 amount − Σ(此后创建、provider=BITESHIP、未取消运单的 carrier_cost + insurance_cost)。

CREATE TABLE biteship_balance_snapshots (
    id              BIGSERIAL   PRIMARY KEY,
    amount          BIGINT      NOT NULL,
    estimate_before BIGINT,
    recorded_by     BIGINT      REFERENCES admin_accounts (id) ON DELETE SET NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_biteship_balance_snapshots_amount CHECK (amount >= 0)
);
CREATE INDEX ix_biteship_balance_snapshots_recorded ON biteship_balance_snapshots (recorded_at DESC, id DESC);

COMMENT ON TABLE biteship_balance_snapshots IS '运营手填的 Biteship 实际余额（Biteship 无余额接口）；最新一条为预估起点';
COMMENT ON COLUMN biteship_balance_snapshots.amount IS '录入的实际余额（IDR）';
COMMENT ON COLUMN biteship_balance_snapshots.estimate_before IS '录入时系统的预估余额（IDR）；此前从未录入为空';
COMMENT ON COLUMN biteship_balance_snapshots.recorded_by IS '录入人 admin_accounts.id；账号删除置空';
COMMENT ON COLUMN biteship_balance_snapshots.recorded_at IS '录入时刻（UTC）；此后创建的运单从此数往下扣';

ALTER TABLE shipping_settings ADD COLUMN balance_alert_threshold BIGINT NOT NULL DEFAULT 0;
ALTER TABLE shipping_settings ADD CONSTRAINT ck_shipping_settings_balance_alert_threshold
    CHECK (balance_alert_threshold >= 0);
COMMENT ON COLUMN shipping_settings.balance_alert_threshold IS 'Biteship 预估余额预警阈值（IDR）；0 = 不预警';

ALTER TABLE shipments ADD COLUMN insurance_cost BIGINT NOT NULL DEFAULT 0;
ALTER TABLE shipments ADD CONSTRAINT ck_shipments_insurance_cost CHECK (insurance_cost >= 0);
COMMENT ON COLUMN shipments.insurance_cost IS 'Biteship 实际收取的保价费（IDR，courier.insurance.fee，6.1 写入）；不同于 insurance_amount（申报保价额，不得算钱）';
-- 预估查询「此后创建的 Biteship 运单」用（部分索引，只含 BITESHIP 行）
CREATE INDEX ix_shipments_biteship_created ON shipments (created_at) WHERE provider = 'BITESHIP';
