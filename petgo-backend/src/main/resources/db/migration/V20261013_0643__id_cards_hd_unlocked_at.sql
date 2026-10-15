-- V1.3.4 ktp Story 2.1（AD-2 / 决策日志 D-8）：每张卡的 HD 解锁（到账）时刻 hd_unlocked_at。
-- 用途：编号沿用（Story 2.3）在多张已解锁学生卡都满足条件时，取最早解锁的那张。
-- 解锁真相仍是 hd_unlocked；本列由 IdCard.markHdUnlocked() 在首次解锁时写入（PawCoin 当场、QRIS 到账同事务），
-- 已解锁卡再被调用不改写。ddl-auto=validate，schema 归 Flyway；时间一律 UTC（timestamptz）。
--
-- 存量回填口径（D-8，订正 PRD【建议】「取最早一条 purchased_at」）——与 AdminUserPurchasesQuery.KTP_SQL 的
-- unlocked_at 子查询同一个表达式：
--   PawCoin 行 → 扣币时间 purchased_at；
--   QRIS 行   → 其支付意图到账（status = 'PAID'）的 updated_at（不用 QRIS 行的 purchased_at，那是下单时间）；
--   一卡多行取最早；都没有（V92 回填生成的老卡等）→ 卡的 created_at 兜底。
-- 未解锁卡保持 NULL。建列与回填在同一支迁移里（拆开上线中间态 = 全体已解锁卡无时间）。
--
-- 回填自检（本地 / petgo_stag 只读执行，结果补记于 story 2-1 Completion Notes；云端会话无库可连，待本地验收）：
--   SELECT count(*) FILTER (WHERE hd_unlocked)                         AS unlocked_total,
--          count(*) FILTER (WHERE hd_unlocked AND hd_unlocked_at = created_at) AS created_at_fallback
--     FROM id_cards;
-- 末尾 DO 块断言回填结果：已解锁必有时间、未解锁必为空，否则 RAISE 让迁移失败。

ALTER TABLE id_cards ADD COLUMN hd_unlocked_at TIMESTAMPTZ;

UPDATE id_cards c
   SET hd_unlocked_at = COALESCE(
         (SELECT MIN(CASE WHEN h.pay_channel = 'PAWCOIN' THEN h.purchased_at
                          WHEN pi.status = 'PAID'      THEN pi.updated_at END)
            FROM id_card_hd_purchases h
            LEFT JOIN payment_intents pi ON pi.id = h.payment_intent_id
           WHERE h.card_id = c.id),
         c.created_at)
 WHERE c.hd_unlocked;

DO $$
DECLARE
    missing BIGINT;
    stray   BIGINT;
BEGIN
    SELECT count(*) INTO missing FROM id_cards WHERE hd_unlocked AND hd_unlocked_at IS NULL;
    SELECT count(*) INTO stray   FROM id_cards WHERE NOT hd_unlocked AND hd_unlocked_at IS NOT NULL;
    IF missing > 0 OR stray > 0 THEN
        RAISE EXCEPTION 'hd_unlocked_at 回填异常：已解锁无时间 % 张，未解锁有时间 % 张', missing, stray;
    END IF;
END $$;

COMMENT ON COLUMN id_cards.hd_unlocked_at IS
    'HD 解锁（到账）时刻，UTC；未解锁为 NULL；存量按 D-8 回填（PawCoin purchased_at / QRIS 到账 updated_at / 兜底 created_at）；用于编号沿用取最早解锁的学生卡';
