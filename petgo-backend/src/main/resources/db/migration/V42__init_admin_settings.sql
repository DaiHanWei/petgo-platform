-- Story 4.3（管理后台 Epic 4）：单行系统配置 admin_settings（AB-3C）。
-- 实测最大号+1 = V40（V39=manual_review_queue）。固定主键 id=1 保证单行；缺省插入种子行。
-- manual_review_enabled 默认 false ＝ V1.0.0 维持现网「自动审核拦截即发布失败」行为不变（AC2）。
CREATE TABLE admin_settings (
    id                    BIGINT      PRIMARY KEY,
    manual_review_enabled BOOLEAN     NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_settings_singleton CHECK (id = 1)
);

INSERT INTO admin_settings (id, manual_review_enabled) VALUES (1, false);
