-- V1.4.1 商家平台对接（tailtopia-contract.md 第 2 节）：支付用途 MERCHANT_PO（门店进货单 QRIS 付款）。
--
-- 🔴 全量重建共享 CHECK ck_payment_intents_purpose：清单照抄当前树里最后一条重建它的
--    V20261018_1401（11 值，与 PaymentPurpose.java 一致），末尾追加 MERCHANT_PO（11 字符 ≤ VARCHAR(16)）= 12 值。
--    并集核查（2026-10-24）：dev_1.4.1 与 main 之后没有其它迁移改过这条约束。
--
-- growth_spend_tiers 同步一行：NONE + locked —— 进货单的付款人是门店不是 App 用户，不给任何 App 用户加成长值
-- （PRD §10 Q5）。GrowthSpendListener 另有代码层排除，两道都在。
--
-- ⚠️ 下次改这条约束：先对枚举全集 → 再查并行分支 → DROP + ADD 重列全集，永远不要只加一行。

ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_purpose;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_purpose
    CHECK (purpose IN ('VET_CONSULT', 'PAWCOIN_TOPUP', 'AI_UNLOCK', 'ID_HD', 'SHOP_ORDER',
                       'TAILSONALITY', 'PASSPORT_SNAP', 'BOARDING_PASS', 'TS_MATCH',
                       'GIFT', 'PET_SLOT_RENT',
                       -- V1.4.1 商家平台门店进货单。
                       'MERCHANT_PO'));

INSERT INTO growth_spend_tiers (purpose, tier, locked) VALUES ('MERCHANT_PO', 'NONE', true);
