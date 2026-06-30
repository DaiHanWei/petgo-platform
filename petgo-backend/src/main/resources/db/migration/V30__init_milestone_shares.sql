-- 里程碑庆祝对外分享（P-35 分享链接 → 公开 H5 GET /m/{share_token}）。
-- 复用名片分享范式（决策 F16）：对外标识用不可枚举 share_token（≥128bit base62），绝不外露顺序 id。
-- 存「渲染好」的庆祝文案（title/body/locale）——沿用「显示文案归客户端按 locale 出、后端只存稳定数据」约定，
-- 后端在创建时按 JWT 补 pet_profile_id / code / level / completed_at（不信任客户端），客户端只传已本地化文案。
-- 归 profile 域，随档案 / 账号注销级联清理（见 V20 账号删除路径与名片同范式）。
CREATE TABLE milestone_shares (
    id              BIGSERIAL    PRIMARY KEY,
    share_token     VARCHAR(64)  NOT NULL,
    pet_profile_id  BIGINT       NOT NULL REFERENCES pet_profiles (id),
    code            VARCHAR(16)  NOT NULL,
    level           VARCHAR(8)   NOT NULL,
    pet_name        VARCHAR(80)  NOT NULL,
    title           TEXT         NOT NULL,
    body            TEXT         NOT NULL DEFAULT '',
    locale          VARCHAR(8)   NOT NULL,
    -- 「已解锁合集」快照：分享时已完成里程碑的级别串（按合集顺序，每字符 S/M/L）。H5 复刻 P-35 KOLEKSI 区。
    collection_levels VARCHAR(64) NOT NULL DEFAULT '',
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_milestone_shares_token UNIQUE (share_token),
    -- 同一宠物同一里程碑只一条分享（幂等）：重复分享复用同一 token，仅刷新文案 / locale。
    CONSTRAINT uq_milestone_shares_pet_code UNIQUE (pet_profile_id, code),
    CONSTRAINT ck_milestone_shares_level CHECK (level IN ('S', 'M', 'L'))
);
