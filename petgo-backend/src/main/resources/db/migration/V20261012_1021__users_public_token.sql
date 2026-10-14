-- V1.3.4 follow · Story 1.1 —— 用户对外不可枚举标识（决策 D-2，架构 delta AD-2）。
--
-- · 新接口（关注 / 关注列表 / 推荐 / 新粉丝通知）一律用 public_token 标识用户；现有数字 id 路由与字段不动。
-- · 形态 32 位 base62（与 places / place_checkins 的 public_token 同族）。新用户由应用层 SecureRandom
--   生成（User.@PrePersist → UserTokenGenerator）；本迁移的 gen_base62_token 只用于存量回填与 DB 默认值。
-- · 必须有 DB 默认值：测试代码里有大量 jdbc 直插 `INSERT INTO users (nickname, status)`，不带默认值会撞 NOT NULL。

CREATE OR REPLACE FUNCTION gen_base62_token(len INT) RETURNS VARCHAR AS $$
DECLARE
    chars  CONSTANT TEXT := '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    result TEXT := '';
    i      INT;
BEGIN
    FOR i IN 1..len LOOP
        result := result || substr(chars, 1 + floor(random() * 62)::INT, 1);
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql VOLATILE;

COMMENT ON FUNCTION gen_base62_token(INT) IS
    '随机 base62 串（V1.3.4 AD-2）。仅供 users.public_token 存量回填与 DB 默认值；应用层新建用户用 SecureRandom。';

ALTER TABLE users ADD COLUMN public_token VARCHAR(32);

UPDATE users SET public_token = gen_base62_token(32) WHERE public_token IS NULL;

ALTER TABLE users
    ALTER COLUMN public_token SET NOT NULL,
    ALTER COLUMN public_token SET DEFAULT gen_base62_token(32),
    ADD CONSTRAINT uq_users_public_token UNIQUE (public_token);

COMMENT ON COLUMN users.public_token IS
    '对外不可枚举用户标识（32 位 base62，V1.3.4 D-2 / AD-2）；自增 id 不外露。注销后保留不变。';
