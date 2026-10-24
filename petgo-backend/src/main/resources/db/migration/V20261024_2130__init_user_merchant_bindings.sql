-- V1.4.1 商家平台对接（tailtopia-merchant docs/integration/tailtopia-contract.md 第 2 节，FR-7 / FR-40 / FR-41）。
--
-- App 用户填门店邀请码后的绑定关系：一个用户一行，【永不更新】（绑定后不能换店、不能解绑）。
-- merchant_store_id 存 merchant_stores.id —— 那张表归商家平台（tailtopia-merchant），跨仓库【不加外键】（AD-21）。
-- user_id 也不加外键：注销是软删（users.deleted_at），绑定行与 users 行都保留（FR-41），
-- 外键只会让测试清库时多一条删除顺序依赖。
--
-- 商家平台只读本表：绑定用户总数（FR-24）、ops 门店详情绑定用户列表读 user_id / bound_at（FR-5A）。
CREATE TABLE user_merchant_bindings (
    user_id           BIGINT      PRIMARY KEY,
    merchant_store_id BIGINT      NOT NULL,
    bound_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_user_merchant_bindings_store_bound
    ON user_merchant_bindings (merchant_store_id, bound_at);
