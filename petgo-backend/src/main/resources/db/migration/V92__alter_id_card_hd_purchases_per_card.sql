-- Story 6-7：身份证 HD 购买绑到具体卡快照（决策①）。去掉「一账号一行永久解锁」，改为一卡一次购买。
-- 回填：为每个已永久解锁的老用户按其当前档案建一张快照卡（hd_unlocked=true），并把购买行指向它。
-- ddl-auto=validate；幂等（仅 card_id IS NULL 的老行受回填影响）。UTC。

-- 1) 加列 + 换约束：去掉 UNIQUE(user_id)（永久解锁语义），改 UNIQUE(card_id)（一卡至多一次成功购买）。
ALTER TABLE id_card_hd_purchases ADD COLUMN card_id BIGINT;
ALTER TABLE id_card_hd_purchases DROP CONSTRAINT uq_id_card_hd_purchases_user;

-- 2) 回填：老用户当前档案 → 一张快照卡（已解锁）。serial 复用档案号，无号则从号池取新号。
--    pet_profile_id 为 NULL（档案已删）的购买无法快照 → 跳过（card_id 留 NULL，rare）。
INSERT INTO id_cards (user_id, serial_id, name, pet_type, breed, birthday, avatar_url, intro,
                      hd_unlocked, created_at)
SELECT h.user_id,
       COALESCE(p.serial_id, nextval('pet_serial_seq')),
       COALESCE(p.name, 'Pet'),
       p.pet_type,
       p.breed,
       p.birthday,
       p.avatar_url,
       p.intro,
       true,
       COALESCE(h.purchased_at, now())
FROM id_card_hd_purchases h
JOIN pet_profiles p ON p.id = h.pet_profile_id
WHERE h.card_id IS NULL;

-- 3) 购买行指向刚建的卡（老模型：一用户一购买一卡，按 user_id 关联）。
UPDATE id_card_hd_purchases h
SET card_id = c.id
FROM id_cards c
WHERE h.card_id IS NULL AND c.user_id = h.user_id;

-- 4) FK。不设 UNIQUE(card_id)：解锁真相在 id_cards.hd_unlocked，本表是「支付尝试/收据」记录
--    （QRIS 下单即建一条存 card_id+payment_intent_id 供回调反查；超窗重开会新建 intent→新 attempt 行）。
ALTER TABLE id_card_hd_purchases
    ADD CONSTRAINT fk_id_card_hd_purchases_card FOREIGN KEY (card_id)
        REFERENCES id_cards (id) ON DELETE CASCADE;
