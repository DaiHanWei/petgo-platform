-- KTP宠物身份码+护照号按《宠物身份码护照编码规则》生成（spec-ktp-pet-idcode-numbering，2026-07-28）。
-- id_cards 加三列快照（旧卡三列=NULL 即「保留旧号」：前端继续旧拼号展示，绝不回填改号）：
--   card_no     身份码 TT+DDMMYY+SP+XXXX（日=生日日+性别加码 母50/公10/未知0；月/年取生日；
--               SP 狗01/猫02/其他00；XXXX=同一 WIB 登记日+同物种顺序号，从 0001 起）
--   passport_no 护照号 TT+SP+P+YY+XXXXX（YY=WIB 签发年后两位；XXXXX=当年顺序号，从 00001 起）
--   gender      性别快照 MALE/FEMALE/UNKNOWN（与 pet_type 同为字符串快照，建卡后不变）
-- 号码仅展示，不作分享/深链/资源定位键；legacy serial_id 号池（V91/V92）照旧，与新号并存。
-- ddl-auto=validate，schema 归 Flyway。

ALTER TABLE id_cards ADD COLUMN card_no     VARCHAR(14);
ALTER TABLE id_cards ADD COLUMN passport_no VARCHAR(12);
ALTER TABLE id_cards ADD COLUMN gender      VARCHAR(8);

-- 撞号终极兜底：身份码日期段=生日、顺序号却按登记日计，不同登记日可能拼出同号——
-- 生成侧循环取下一个序号（≤20 次）+ UNIQUE 双保险。NULL 不参与唯一性，旧卡无影响。
ALTER TABLE id_cards ADD CONSTRAINT uq_id_cards_card_no UNIQUE (card_no);
ALTER TABLE id_cards ADD CONSTRAINT uq_id_cards_passport_no UNIQUE (passport_no);

-- 身份码顺序号计数器：按 (WIB 登记日, 物种 SP 码) 一行，INSERT..ON CONFLICT..RETURNING 单语句
-- 原子取号，并发靠行锁串行（与 legacy 号池的 advisory 锁互不相干）。
-- ⚠️ 小整数列用 INT 不用 SMALLINT（Hibernate int 实体映 int4，SMALLINT 会 validate 红）。
CREATE TABLE id_card_no_counters (
    reg_date DATE       NOT NULL,               -- WIB（Asia/Jakarta）登记日
    species  VARCHAR(2) NOT NULL,               -- SP 码：01 狗 / 02 猫 / 00 其他
    next_seq INT        NOT NULL,               -- 已发出的最新顺序号（上限 9999，超限 500）
    PRIMARY KEY (reg_date, species)
);

-- 护照号顺序号计数器：按 WIB 签发年一行，同样单语句 upsert 取号（上限 99999，超限 500）。
CREATE TABLE passport_no_counters (
    issue_year INT PRIMARY KEY,                 -- WIB（Asia/Jakarta）签发年，如 2026
    next_seq   INT NOT NULL
);
