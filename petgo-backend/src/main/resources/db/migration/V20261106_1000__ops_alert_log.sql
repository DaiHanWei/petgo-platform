-- V1.4.2 shop-admin Story 1.3 · AD-5：电商运营提醒（飞书 客服 / 运营 / 技术 三群）的去重表。
-- 同一 dedupe_key 只发一次（如 late-paid:{orderToken}、claim:{orderToken}）；去重键为空的提醒（技术类，沿用调用方节流）不进本表。
-- 🔒 dedupe_key 只含业务 token / 时间戳，不含手机号、地址、收款账户。
CREATE TABLE ops_alert_log (
    dedupe_key VARCHAR(200) PRIMARY KEY,
    channel    VARCHAR(16)  NOT NULL,
    sent_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_alert_log_channel CHECK (channel IN ('CS', 'OPS', 'TECH'))
);
