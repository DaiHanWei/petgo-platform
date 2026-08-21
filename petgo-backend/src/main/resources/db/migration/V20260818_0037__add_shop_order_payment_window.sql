-- V1.4.0 精选自营电商 · Story 3.8 —— 支付窗与电商支付用途（FR-100 / AD-8 / AD-9）。
-- 工作线：V1.4.0 电商（分支 shawn/oneline-ecommerce）· 独占号段 V101–V139。取当前最大号 V112 + 1。
--
-- 🔴🔴 **本迁移放宽共享 CHECK `ck_payment_intents_purpose`。** 🔴🔴
--    这是 Story 3.3 那次改动的**另一半**：3.3 为电商加了 coin_amount / cash_amount / coin_ratio
--    三列并放宽了 channel，却没有给 purpose 加上电商自己的取值 —— 于是那三列至今无人可写
--    （任何 purpose 为电商的意图都插不进去）。本迁移补上。
--    ⚠️ **临时授权（HEX-SIGNOFF.md）覆盖的是 ck_payment_intents_channel 与 ck_notifications_type，
--       并未点名 ck_payment_intents_purpose。** 须在补签时一并认领。
--
-- 🔴 同 V97 / V110 的写法：DROP + ADD 时**显式列全五个值**，并注明本次新增的只有 SHOP_ORDER。
--    2026-07-30 事故的剧本是「两条线各自 DROP + ADD 同一个 CHECK，合并后一方取值整类失效」——
--    git 不冲突、编译不报错、两边测试全绿，只在真跑那条路径时才炸。
--
-- 🔴 本迁移不回填、不改写任何既有行。

ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_purpose;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_purpose
    CHECK (purpose IN ('VET_CONSULT', 'PAWCOIN_TOPUP', 'AI_UNLOCK', 'ID_HD', 'SHOP_ORDER'));

-- ---------------------------------------------------------------------------
-- 订单支付窗（AD-8：下单后 60 分钟未支付则取消并释放库存）
-- ---------------------------------------------------------------------------
-- 🔴 **服务端时刻是唯一权威**：客户端倒计时只是展示。用客户端本地计时做判定，
--    改一下手机时间就能无限延长锁库存的时间 —— 库存是别人也想买的东西。
ALTER TABLE shop_orders ADD COLUMN expires_at TIMESTAMPTZ;
COMMENT ON COLUMN shop_orders.expires_at IS
    '支付窗截止时刻（下单 +60min，AD-8）。仅 PENDING_PAYMENT 有意义；超时由扫描任务取消并释放库存';

-- 🔴 可空而非 NOT NULL：本列上线前已存在的订单没有支付窗，回填一个假截止时刻
--    会让它们在下一次扫描时被"超时取消"。历史订单没有窗 = 不参与超时扫描，这是对的。
--    新建单一律写入（ShopOrder.place 里赋值）。

-- 关联的支付意图（一对一：同一订单重复点「去支付」经幂等键取回同一个意图）。
-- 🔴 存 token 不存 id：对外可见标识一律不可枚举，内部关联也没有理由用自增 id（NFR-3）。
ALTER TABLE shop_orders ADD COLUMN payment_intent_token VARCHAR(64);
COMMENT ON COLUMN shop_orders.payment_intent_token IS
    '本单当前的支付意图 public_token（到账事件据此回找订单）。纯 PawCoin 单无意图，恒 NULL';

-- 超时扫描用（部分索引：只有待支付且有窗的订单需要被扫）
CREATE INDEX idx_shop_orders_pending_expiry ON shop_orders (expires_at)
    WHERE status = 'PENDING_PAYMENT' AND expires_at IS NOT NULL;

-- 到账事件按意图 token 回找订单
CREATE INDEX idx_shop_orders_payment_intent ON shop_orders (payment_intent_token)
    WHERE payment_intent_token IS NOT NULL;
