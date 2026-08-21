-- V1.4.0 精选自营电商 · Story 3.10 —— 归因链闭合（AB-13B / A-16 / 决策 IR 前移）。
-- 工作线：V1.4.0 电商（分支 shawn/oneline-ecommerce）· 独占号段 V101–V139。取当前最大号 V113 + 1。
-- ⚠️ 台账原把 V114 预留给 4-2 shipments —— 按「取最大号 +1 并回写」规则由本 story 先用掉，4-2 顺延。
--
-- 🔴 **为什么必须落在购物车行上，而不是只靠客户端埋点：**
--    AB-13B 要用「触发卡转化率 vs 普通商品曝光转化率」判定 A-16（复购引擎值不值得做）。
--    客户端事件会被广告拦截、网络丢包、用户杀进程吃掉，而这个判断决定的是**下一个版本做什么**。
--    Story 3.4 已经在 shop_order_lines 上留好了 entry_source / trigger_type 两列，
--    但下单时没有任何地方能提供它们的值 —— 因为**商品是"什么时候、从哪个入口"加进购物车的，
--    只有加购那一刻知道**，而那一刻的信息此前没有被保存。本迁移补上这一环。
--
-- 🔴 两列都可空：历史购物车行没有来源，下单时写 NULL 到订单行 —— 那是诚实的"未知"，
--    比编一个 'TOKO_CART'（"从购物车结算"，等于废话）强得多。错误的归因数据没人能事后识别。

ALTER TABLE shop_cart_items ADD COLUMN entry_source VARCHAR(32);
COMMENT ON COLUMN shop_cart_items.entry_source IS
    '加购入口（TOKO_ALL_FEATURED / TOKO_CATEGORY / PROFILE_RECOMMEND / REFILL_REMINDER …）。下单时抄到 shop_order_lines';

ALTER TABLE shop_cart_items ADD COLUMN trigger_type VARCHAR(32);
COMMENT ON COLUMN shop_cart_items.trigger_type IS
    '触发类型（Epic 6 复购触发用；主动浏览为 NULL）。下单时抄到 shop_order_lines';
