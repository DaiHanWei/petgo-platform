-- V1.3.2 batch-a · Story 2.5 —— Tailsonality 主人类型（FR-117 配型，架构 delta AD-3 / AD-17）。
--
-- · 账号级：一个账号一行（PK = user_id）；覆盖写。
-- · 配型结果（相同字母数 → 5 档 + 差异句 + 逐轴详解）**不落库**，纯客户端现算（AD-3）。
-- · 注销：TailsonalityDeletionService.deleteOwnerTypeByUserId 在 user 行删除前物理删除；删档（只删宠物）不动本表。

CREATE TABLE tailsonality_owner_types (
    user_id     BIGINT       PRIMARY KEY REFERENCES users (id),
    type_code   CHAR(4)      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tailsonality_owner_types_type_code CHECK (type_code ~ '^[EI][NS][TF][JP]$')
);

COMMENT ON TABLE tailsonality_owner_types IS
    'Tailsonality 主人自报的四字母类型（V1.3.2 FR-117 配型）。账号级；配型结果不落库、纯客户端计算（AD-3）；'
    '注销时物理删除，删档不动。';
COMMENT ON COLUMN tailsonality_owner_types.type_code IS '主人四字母（社交-探索-情绪-驱动），无能量后缀。';
