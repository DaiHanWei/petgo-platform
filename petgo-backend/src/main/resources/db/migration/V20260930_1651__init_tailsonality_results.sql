-- V1.3.2 batch-a · Story 2.1 —— Tailsonality 测试结果（FR-117，架构 delta AD-1 / AD-2 / AD-17）。
--
-- · 提交答案时即建一行：它是 Story 3.1 一次性解锁购买的「业务行」（keepsake_purchases.ref_id 指向 id，
--   幂等键用 public_token），因此 public_token 提交时生成、永不改。
-- · 只存代号 + 能量 + 内容版本，不存文案（文案在 App，AD-2）。
-- · 无配额 / 计次 / 付费重测相关列（PRD §3.2「不留状态位」）：重测 = 再插一行。
-- · 删档 / 注销：TailsonalityDeletionService 在删宠物行之前物理删除（AD-17）。

CREATE TABLE tailsonality_results (
    id              BIGSERIAL    PRIMARY KEY,
    public_token    VARCHAR(32)  NOT NULL,
    pet_profile_id  BIGINT       NOT NULL REFERENCES pet_profiles (id),
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    question_set    VARCHAR(16)  NOT NULL,
    answers         JSONB        NOT NULL,
    type_code       CHAR(4)      NOT NULL,
    energy          CHAR(1)      NOT NULL,
    content_version INT          NOT NULL,
    unlocked_at     TIMESTAMPTZ  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tailsonality_results_public_token UNIQUE (public_token),
    CONSTRAINT ck_tailsonality_results_question_set CHECK (question_set IN ('CAT', 'DOG', 'GENERAL')),
    CONSTRAINT ck_tailsonality_results_type_code CHECK (type_code ~ '^[EI][NS][TF][JP]$'),
    CONSTRAINT ck_tailsonality_results_energy CHECK (energy IN ('H', 'L')),
    CONSTRAINT ck_tailsonality_results_content_version CHECK (content_version >= 1)
);

CREATE INDEX ix_tailsonality_results_pet_created ON tailsonality_results (pet_profile_id, created_at DESC);

COMMENT ON TABLE tailsonality_results IS
    'Tailsonality 测试结果（V1.3.2 FR-117）。提交答案时即建，是 Story 3.1 一次性解锁购买的业务行；'
    'unlocked_at 由 Story 3.2 的 grant 置位；删档 / 注销时物理删除。';
COMMENT ON COLUMN tailsonality_results.public_token IS '不可枚举对外标识（32 位 base62），提交时生成、永不改；购买幂等键用它。';
COMMENT ON COLUMN tailsonality_results.question_set IS '题套：服务端按宠物物种选（CAT / DOG / GENERAL），不信客户端。';
COMMENT ON COLUMN tailsonality_results.answers IS '18 题原始选项序号 0..3（键 Q1..Q15、P1..P3），计分复核用，不外露。';
COMMENT ON COLUMN tailsonality_results.type_code IS '四字母代号（社交-探索-情绪-驱动）。';
COMMENT ON COLUMN tailsonality_results.energy IS '能量后缀 H / L。';
COMMENT ON COLUMN tailsonality_results.content_version IS '结果文案内容版本（已解锁结果的购买当时快照由它承载，本版本恒 1）。';
COMMENT ON COLUMN tailsonality_results.unlocked_at IS '解锁时刻（UTC）；null = 未解锁。由 Story 3.2 grant 置位。';
