-- Story 2.2：profile 模块地基。创建 pet_profiles 表（宠物档案，承载成长记忆 + 对外名片）。
-- Flyway 序号：接 V2__init_auth 之后单调分配（决策 E2）。schema 归 Flyway，ddl-auto=validate。
-- V1 限制：单账号单宠物（uq_pet_profiles_owner_id）。对外标识用不可枚举 card_token，绝不外露顺序 id。

CREATE TABLE pet_profiles (
    id         BIGSERIAL    PRIMARY KEY,
    owner_id   BIGINT       NOT NULL,
    avatar_url VARCHAR(1024),                 -- 复用 Story 2.1 公开桶 CDN URL（已客户端剥 EXIF）
    name       VARCHAR(20)  NOT NULL,
    breed      VARCHAR(60),
    birthday   DATE,
    intro      VARCHAR(30),                   -- 一句话介绍 ≤30
    card_token VARCHAR(64)  NOT NULL,         -- 不可枚举对外名片 token（SecureRandom ≥128bit base62），供 2.6 /p/{cardToken}
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    -- 单账号单宠物（FR-11，V1 硬约束）
    CONSTRAINT uq_pet_profiles_owner_id  UNIQUE (owner_id),
    CONSTRAINT uq_pet_profiles_card_token UNIQUE (card_token),
    -- 注销级联前置（FR-20，架构不可砍地基#4）：注销时此表 + 头像对象需级联删除。
    CONSTRAINT fk_pet_profiles_owner FOREIGN KEY (owner_id) REFERENCES users (id)
);
