-- V47__init_moderation_keyword_rules.sql
-- Story 内容审核-1：L1/L2/L3 分层词库 + 宠物场景白名单（方案 §9）。
-- rule_kind UPPER_SNAKE 落 varchar；白名单优先级最高（§9.3），命中白名单不触发同步硬拦截。
-- 无 length=1 列（避免 Hibernate CHAR(1) → validate 全红）；enabled 用 boolean。
CREATE TABLE moderation_keyword_rules (
    id          BIGSERIAL    PRIMARY KEY,
    rule_kind   VARCHAR(16)  NOT NULL,   -- L1_BLOCK / L2_ADJUSTABLE / L3_WHITELIST
    match_type  VARCHAR(16)  NOT NULL DEFAULT 'SUBSTRING', -- SUBSTRING / REGEX / EXACT
    pattern     VARCHAR(512) NOT NULL,   -- 词或正则（大小写不敏感由应用层保证）
    category    VARCHAR(32)  NOT NULL,   -- DRUGS/GAMBLING/PORN/POLITICS/AD_SPAM/HARASSMENT/WEAPON/PET_SAFE...
    lang        VARCHAR(8)   NOT NULL DEFAULT 'ALL',  -- id / en / zh / ALL
    enabled     BOOLEAN      NOT NULL DEFAULT true,
    note        VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_mkr_rule_kind  CHECK (rule_kind  IN ('L1_BLOCK','L2_ADJUSTABLE','L3_WHITELIST')),
    CONSTRAINT ck_mkr_match_type CHECK (match_type IN ('SUBSTRING','REGEX','EXACT'))
);

CREATE INDEX idx_mkr_kind_enabled ON moderation_keyword_rules (rule_kind, enabled);
CREATE INDEX idx_mkr_lang         ON moderation_keyword_rules (lang);

-- 种子：§9 初稿（L1 违禁/赌博/武器 + L2 引流 + L3 宠物白名单）。
-- 印尼语 L1（毒品/赌博/武器）与 L3 白名单来自方案 §9.1/§9.3；
-- L3 宠物白名单（anjing/gendut/hitam/nakal...）供印尼语兜底与误判防护。
INSERT INTO moderation_keyword_rules (rule_kind, match_type, pattern, category, lang, note) VALUES
  ('L1_BLOCK','SUBSTRING','narkoba','DRUGS','id','毒品总称'),
  ('L1_BLOCK','SUBSTRING','sabu','DRUGS','id','冰毒'),
  ('L1_BLOCK','SUBSTRING','ganja','DRUGS','id','大麻'),
  ('L1_BLOCK','SUBSTRING','judi','GAMBLING','id','赌博总称'),
  ('L1_BLOCK','SUBSTRING','togel','GAMBLING','id','非法数字彩'),
  ('L1_BLOCK','SUBSTRING','bom','WEAPON','id','炸弹'),
  ('L2_ADJUSTABLE','SUBSTRING','wa.me/','AD_SPAM','ALL','WhatsApp 引流链接'),
  ('L2_ADJUSTABLE','REGEX','\b08[0-9]{2}[-\s]?[0-9]{4}[-\s]?[0-9]{4}\b','AD_SPAM','id','印尼手机号（中风险，非硬拦截）'),
  ('L3_WHITELIST','EXACT','anjing','PET_SAFE','id','狗（宠物语境正常词，优先于黑名单）'),
  ('L3_WHITELIST','EXACT','gendut','PET_SAFE','id','胖（高频宠物名）'),
  ('L3_WHITELIST','EXACT','hitam','PET_SAFE','id','黑（黑毛宠物名）'),
  ('L3_WHITELIST','EXACT','nakal','PET_SAFE','id','调皮（宠物名）');
