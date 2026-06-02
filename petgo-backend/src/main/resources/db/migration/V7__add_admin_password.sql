-- Story 3.1：运营后台地基。为 ADMIN 账号补 password_hash 列（复用 users 表 + role=ADMIN）。
-- Flyway 序号：接 V6__add_og_image_url 之后单调分配（决策 E2，Epic 3 从 V7 顺延）。
-- 设计：OAuth 用户 password_hash 为 NULL；仅 ADMIN 走账密表单登录（BCrypt）。
-- 凭证不入库：本迁移不内置任何明文/哈希；ADMIN 账号由 env 注入的 AdminBootstrap 在运行时落库
-- （ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD），符合「凭证 env 注入绝不入库」护栏。

ALTER TABLE users ADD COLUMN password_hash VARCHAR(255);  -- 仅 ADMIN 用，OAuth 用户为 NULL
