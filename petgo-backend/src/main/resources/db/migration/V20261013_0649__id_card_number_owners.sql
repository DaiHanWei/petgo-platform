-- V1.3.4 ktp Story 2.2（AD-3 / NFR2 / NFR6）：编号唯一性改为「同一用户内可重复、跨用户不可重复」。
-- 去掉 id_cards.card_no / passport_no 的全局 UNIQUE（V95），为 Story 2.3 编号沿用让路；
-- 「跨用户不撞号」的兜底改由归属表 id_card_number_owners 的主键 + CardNumberService.claimNumbers 承担：
-- 建卡写号时 INSERT .. ON CONFLICT DO NOTHING 再查归属，非本人 → 抛错整笔回滚。
-- 不用排他约束（需 btree_gist 扩展，生产库装扩展有权限与发布风险），不用触发器（逻辑藏库里不易测）。
-- serial_id 与 uq_id_cards_serial 不动（每卡新取、全局唯一）。ddl-auto=validate；归属表不建 JPA 实体，只经原生 SQL 读写。
--
-- 🔴 顺序不得调换（cloud-rules）：① 自检存量（有冲突 RAISE）→ ② 建表 → ③ 回填 → ④ 删两条全局 UNIQUE。
--
-- 回填自检（本地 / petgo_stag 只读执行，结果补记于 story 2-2 Completion Notes；云端会话无库可连，待本地验收）：
--   SELECT count(card_no)                          AS card_no_rows,
--          count(DISTINCT (card_no, user_id))      AS card_no_owners,
--          count(passport_no)                      AS passport_no_rows,
--          count(DISTINCT (passport_no, user_id))  AS passport_no_owners
--     FROM id_cards;
--   期望：V95 起两列全局 UNIQUE，故 rows = owners。

-- ① 自检：同一个号被不止一个用户持有 → 迁移失败（理论上不会出现：两列此前全局 UNIQUE；NULL 不计）。
-- 报错信息只列类别、冲突号数量与涉及的 user_id，**不输出号码本身**（号码编码了宠物生日，迁移报错会进启动日志）；
-- 定位具体号请用同口径 SQL 在库内查询。
DO $$
DECLARE
    r RECORD;
    msg TEXT := '';
BEGIN
    FOR r IN
        SELECT kind, count(*) AS numbers, string_agg(DISTINCT users, ',') AS users
          FROM (SELECT 'C' AS kind, string_agg(DISTINCT user_id::text, ',') AS users
                  FROM id_cards WHERE card_no IS NOT NULL
                 GROUP BY card_no HAVING COUNT(DISTINCT user_id) > 1
                UNION ALL
                SELECT 'P', string_agg(DISTINCT user_id::text, ',')
                  FROM id_cards WHERE passport_no IS NOT NULL
                 GROUP BY passport_no HAVING COUNT(DISTINCT user_id) > 1) d
         GROUP BY kind
    LOOP
        msg := msg || format(' [%s 冲突号 %s 个 users=%s]', r.kind, r.numbers, r.users);
    END LOOP;
    IF msg <> '' THEN
        RAISE EXCEPTION 'id_cards 存在跨用户同号，先人工核查再迁移：%', msg;
    END IF;
END $$;

-- ② 建表。FK 与 id_cards.fk_id_cards_user 同为 ON DELETE CASCADE：号的归属与卡同生命周期
-- （注销走匿名化不删 users 行，不受影响）。
CREATE TABLE id_card_number_owners (
    kind    CHAR(1)     NOT NULL,   -- C = 身份码 card_no；P = 护照号 passport_no
    number  VARCHAR(16) NOT NULL,
    user_id BIGINT      NOT NULL,
    CONSTRAINT pk_id_card_number_owners PRIMARY KEY (kind, number),
    CONSTRAINT ck_id_card_number_owners_kind CHECK (kind IN ('C', 'P')),
    CONSTRAINT fk_id_card_number_owners_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- ③ 回填：现有卡的号按 (号, 用户) 去重登记。V95 前旧卡（两列 NULL）不登记。
INSERT INTO id_card_number_owners (kind, number, user_id)
SELECT DISTINCT 'C', card_no, user_id FROM id_cards WHERE card_no IS NOT NULL;

INSERT INTO id_card_number_owners (kind, number, user_id)
SELECT DISTINCT 'P', passport_no, user_id FROM id_cards WHERE passport_no IS NOT NULL;

-- ④ 删全局唯一（同一用户的多张卡从此可共用一个号）。
ALTER TABLE id_cards DROP CONSTRAINT uq_id_cards_card_no;
ALTER TABLE id_cards DROP CONSTRAINT uq_id_cards_passport_no;

COMMENT ON TABLE id_card_number_owners IS
    '身份证编号归属（V1.3.4 ktp AD-3）：同一个号只属于一个用户；同一用户多张卡可共用（编号沿用）；建卡写号时登记；V95 前旧卡不登记';
COMMENT ON COLUMN id_card_number_owners.kind IS 'C = 身份码 id_cards.card_no；P = 护照号 id_cards.passport_no';
COMMENT ON COLUMN id_card_number_owners.number IS '号码本身（仅展示用，不作分享 / 深链 / 资源定位键）';
COMMENT ON COLUMN id_card_number_owners.user_id IS '号的唯一归属用户；跨用户冲突 → 建卡回滚（发号出错兜底）';
