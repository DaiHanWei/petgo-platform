-- 单条内容对外分享（V1.1.6 Story 9.3 · FR-73 · AD-15 Rule 5）。
--
-- 🔴 与宠物名片分享（/p/{cardToken}）、里程碑分享（/m/{shareToken}）**是三个独立的链接类型**。
-- 单条分享必须另起一套：名片链接的落地页是整本档案的只读视图，
-- 单条分享若复用它，等于「我只想分享一条」变成「我把整本都给你了」——这是隐私边界，不是路由洁癖。
--
-- 对外一律用不可枚举 share_token（≥128bit 熵，绝不由顺序 id 派生）。
-- (content_post_id) 唯一 ⇒ 同一条内容重复分享复用同一 token（幂等，不会每点一次分享就多一条链接）。
CREATE TABLE content_shares (
    id              BIGSERIAL    PRIMARY KEY,
    share_token     VARCHAR(64)  NOT NULL,
    content_post_id BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_content_shares_token UNIQUE (share_token),
    CONSTRAINT uk_content_shares_post  UNIQUE (content_post_id)
);

COMMENT ON TABLE content_shares IS '单条内容对外分享链接（Story 9.3）。落地页只展示这一条，不通往该宠物的其它内容。';
COMMENT ON COLUMN content_shares.share_token IS '不可枚举对外 token（base62，≥128bit 熵）。公开页 GET /c/{share_token} 据此直出。';
