-- V1.3.0 shop-v2 Story 3-1（AD-S8 / SHOP-FR-26）：客服联系方式配置化。时间戳版本号（决策 E7）。
--
-- 今天客服号写死在 2 处（App 的 customer_service_sheet.dart、后端 AuthService 的停用提示），
-- 换号要发版。本迁移建单一事实源，让「一处配置、多处消费」成立。
--
-- 🔴 **刻意不建通用 key-value 设置表**（AD-S8）：仓内既有配置一律是「单行强类型表 +
--    CHECK(id=1) + 种子 INSERT」（pricing_config / pawcoin_config / feed_rank_config）。
--    再引一套 key-value 会让「配置在哪张表里是真的」变成需要对账的问题，而且 key-value
--    天然丢类型与约束（号码列就上不了 VARCHAR(20)）。代价是每加一类配置要一支迁移 ——
--    这个项目一年也就几支。
--
-- 🔴 **本迁移做两件事是刻意的**：建表 + 放开 config_change_logs 的类型白名单。
--    它们是同一次能力上线的原子前提，拆两支反而会出现「表在、约束没放开」的中间态 ——
--    那个中间态的表现是「保存客服配置报 500」。

-- 客服联系方式（单行 id=1）——范式逐字照 V78 的 pricing_config。
CREATE TABLE support_contact_config (
    id              BIGINT       PRIMARY KEY,
    whatsapp_number VARCHAR(20)  NOT NULL,          -- 运营输入的原样写法（印尼人认 08xx 这个形式）
    email           VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_contact_config_singleton CHECK (id = 1)
);

COMMENT ON TABLE support_contact_config IS
    '客服联系方式（单行 id=1）。Story 3-1 / AD-S8：改后台配置即生效，不需发版、不需重启。';
COMMENT ON COLUMN support_contact_config.whatsapp_number IS
    '运营输入的原样号码（展示与复制用）。E.164 形态由 IndonesiaPhone.normalize 读时派生，不落库 —— 存两份必然走散。';
COMMENT ON COLUMN support_contact_config.email IS
    '对外客服邮箱。⚠️ 与 resources/legal/*.html 里的邮箱是两回事：那些是法务文本，不由本表驱动。';

-- 种子 = 现网正在用的值（SD-10 已定用 081290906953）。行为零变化。
INSERT INTO support_contact_config (id, whatsapp_number, email)
VALUES (1, '081290906953', 'cs@tailtopia.id');

-- ---------------------------------------------------------------------------
-- config_change_logs.config_type 放开 SUPPORT_CONTACT
--
-- 🔴 **必须 DROP + ADD 重列全集，不是 append 一个**。值集取自**当前树里最后一条重建它的
--    迁移** V20260824_1655__config_change_log_add_feed_rank.sql（四值），不是 V78 的三值版本。
--    CLAUDE.md 记录此处已出过三次事故，每次都是照着一份过期列表抄，丢掉一批值。
--
-- 🔴 只在 Java 枚举里加值**不够**：写日志时会撞 DB 约束，表现是「保存客服配置报 500」，
--    而错误栈指向 config_change_logs 不指向配置模块，极易被误判成审计模块坏了。
--
-- ⚠️ V78 与 V20260824_1655 都已合入 main，属**冻结**迁移：不改它们，另起一条替换约束。
-- ---------------------------------------------------------------------------
ALTER TABLE config_change_logs DROP CONSTRAINT IF EXISTS ck_config_change_type;
ALTER TABLE config_change_logs ADD CONSTRAINT ck_config_change_type
    CHECK (config_type IN ('PRICING', 'PAWCOIN', 'TOPUP_TIER', 'FEED_RANK', 'SUPPORT_CONTACT'));
