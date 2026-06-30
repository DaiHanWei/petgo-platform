-- FR-44（Apple 登录，PRD 排期 1.1.0）：users 表支持多 IdP 身份（Google + Apple）。
-- 设计依 Story 1.3「身份字段按多 IdP 可扩展」：在现有 google_sub 之外预留 apple_sub VARCHAR NULL。
-- google_sub 由 NOT NULL 放宽为可空——Apple-only 用户无 Google 身份；二者唯一约束并存，
-- 「至少其一非空」由应用层保证（建号工厂只设其一）。schema 归 Flyway，ddl-auto=validate。
-- Flyway 序号：接 V30 之后单调分配（决策 E2）。

ALTER TABLE users ALTER COLUMN google_sub DROP NOT NULL;

ALTER TABLE users ADD COLUMN apple_sub VARCHAR(255);

-- Postgres 唯一约束视多个 NULL 互异，故 Google-only 用户（apple_sub 为 NULL）不会互相冲突。
ALTER TABLE users ADD CONSTRAINT uq_users_apple_sub UNIQUE (apple_sub);
