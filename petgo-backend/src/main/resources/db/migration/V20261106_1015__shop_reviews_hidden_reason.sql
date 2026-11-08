-- V1.4.2 shop-admin Story 3.3 · AD-16（AB-39）：隐藏评价记原因与操作人。hidden_at 已在 V20261105_1650 建。
-- 原因：ABUSE 辱骂 | AD 广告 | PRIVACY 隐私 | OTHER 其他。存量已隐藏行（stag 工具隐藏的）原因为空，不回填。
ALTER TABLE shop_reviews
    ADD COLUMN hidden_reason      VARCHAR(16),
    ADD COLUMN hidden_by_admin_id BIGINT REFERENCES admin_accounts (id) ON DELETE SET NULL;
ALTER TABLE shop_reviews
    ADD CONSTRAINT ck_shop_reviews_hidden_reason
        CHECK (hidden_reason IS NULL OR hidden_reason IN ('ABUSE', 'AD', 'PRIVACY', 'OTHER')),
    ADD CONSTRAINT ck_shop_reviews_hidden_reason_needs_hidden
        CHECK (hidden_reason IS NULL OR hidden_at IS NOT NULL);

COMMENT ON COLUMN shop_reviews.hidden_reason IS '后台隐藏原因 ABUSE/AD/PRIVACY/OTHER（V1.4.2 shop-admin 3.3）；hidden_at 为空时必为空';
COMMENT ON COLUMN shop_reviews.hidden_by_admin_id IS '执行隐藏的后台账号 admin_accounts.id；恢复显示时清空；账号删除置空';
