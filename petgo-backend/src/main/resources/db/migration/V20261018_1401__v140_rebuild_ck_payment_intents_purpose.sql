-- V1.4.0 地基 · Story 0.1 AC1（② 支付用途；四条线共用：① Tailrent Level / ② 多宠物 / ③ 送礼 / ④ 推送开关与收藏）。
--
-- 🔴🔴 本迁移全量重建共享 CHECK ck_payment_intents_purpose。🔴🔴 四条线的功能分支一律不再改这条约束
--      （归属见 CROSS-STORY-DECISIONS.md「2026-10-18 V1.4.0 四条线共用地基」）。
--
-- 本文件的清单是这样来的：
--
--   ① 照抄当前树里最后一条重建它的 V20261009_0113（9 值，与 PaymentPurpose.java 一致），
--      末尾追加 GIFT（③ 送礼）、PET_SLOT_RENT（② 宠物名额月租）= 11 值。purpose 列宽 VARCHAR(16)，两个新值都不超。
--   ② 并集核查（2026-10-18，同 V20261018_1400）：相对 main 没有其它分支同期加的值需要纳入。
--   ③ 通知类型约束在同批的 V20261018_1400（一约束一文件，见该文件说明）。
--
-- 不回填、不改写任何既有行。ck_keepsake_purchases_sku 不动（送礼与月租不走 keepsake）。
--
-- ⚠️ 下次改这条约束：先对枚举全集 → 再查并行分支 → DROP + ADD 重列全集，永远不要只加一行。

ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_purpose;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_purpose
    CHECK (purpose IN ('VET_CONSULT', 'PAWCOIN_TOPUP', 'AI_UNLOCK', 'ID_HD', 'SHOP_ORDER',
                       'TAILSONALITY', 'PASSPORT_SNAP', 'BOARDING_PASS', 'TS_MATCH',
                       -- V1.4.0 ③ 送礼（不走消费返成长值）/ ② 宠物名额月租。
                       'GIFT', 'PET_SLOT_RENT'));
