-- V1.4.0 精选自营电商 · Story 1.3 —— shop_skus 加进货价（AB-10B / NFR-11）。
-- 工作线：V1.4.0 电商（分支 shawn/oneline-ecommerce）· 独占号段 V101–V139。
-- 本迁移【只改本工作线自有表 shop_skus（V101 建），不触碰共享表】→ 无需认领。
-- ⚠️ 号必须 > V102（Flyway 默认 outOfOrder=false）。
--
-- 🔒 进货价是【商业敏感数据】：
-- · 需单独权限位 shop.cost_view / shop.cost_edit，默认仅财务与管理层持有（NFR-11）
-- · 🔴 无权限时【服务端就不下发】，不是前端隐藏——前端隐藏可通过看源码/直调接口绕过
-- · 🔴 审计详情【不写进货价数值】，只记「更新了进货价」——审计日志页的可见范围与
--   进货价权限不同，写进去等于绕过权限位
-- · 金额同 price：最小币种单位 BIGINT（IDR 无小数），禁 DECIMAL/double（NFR-9）
--
-- 可空：1.1 建表时未含此列，存量 SKU 无进货价；且商品可先录入后补成本。

ALTER TABLE shop_skus ADD COLUMN cost_price BIGINT;

ALTER TABLE shop_skus ADD CONSTRAINT ck_shop_skus_cost_price
    CHECK (cost_price IS NULL OR cost_price >= 0);

COMMENT ON COLUMN shop_skus.cost_price IS
    '进货价（最小币种单位）。🔒 商业敏感：需 shop.cost_view 权限，服务端按权限决定是否下发；审计不记数值。';
