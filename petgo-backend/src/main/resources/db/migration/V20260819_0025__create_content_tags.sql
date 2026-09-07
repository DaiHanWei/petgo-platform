-- 工作线：V1.1.6（本分支迁移从 V105 起，V101–V104 归 hex/v1.1.4）
--
-- V1.1.6 Story 5.2 · FR-75：内容装饰标签（运营给优质内容发的荣誉标签）。
--
-- 设计要点（架构 AD-10 / AD-9）：
--   1. 🛡 **与用户标签那两张表完全独立、不共用**。一张表加类型字段会让分配表的外键
--      同时指向两张不同的实体表 —— PostgreSQL 建不出约束，完整性只能靠代码自律。
--      而且两类的校验规则本就不同（"仅公开内容可打标"只对内容标签成立）。
--
--   2. 🛡 **不落状态列、不建定时扫描器**（AD-9）：生效与否查询时按
--      [starts_at, ends_at) 判定；ends_at 可空 = 永久。
--
--   3. ⚠️ 打标是**流量动作**：标签生效中时该内容在推荐排序上有 ×1.3 加权（AD-10 Rule 6）。
--      加权由同一份查询时判定推导，因此"标签到期 → 加成一并消失"自动成立、没有状态可漂移。
--      ⚠️ 本版本首页是纯时间倒序、无排序算法，该加权**尚无施加处**，见 ContentTagQueryService。

CREATE TABLE content_tags (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(48)  NOT NULL,
    name        VARCHAR(48)  NOT NULL,
    icon        VARCHAR(255) NOT NULL,
    description VARCHAR(140) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE content_tags ADD CONSTRAINT uq_content_tags_code UNIQUE (code);

CREATE TABLE content_tag_assignments (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT      NOT NULL,
    tag_id     BIGINT      NOT NULL,
    starts_at  TIMESTAMPTZ NOT NULL,
    -- 🛡 可空 = 永久。
    ends_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_content_tag_assignments_tag FOREIGN KEY (tag_id) REFERENCES content_tags (id),
    CONSTRAINT fk_content_tag_assignments_post FOREIGN KEY (post_id) REFERENCES content_posts (id)
        ON DELETE CASCADE
);

ALTER TABLE content_tag_assignments
    ADD CONSTRAINT ck_content_tag_assignments_window CHECK (ends_at IS NULL OR ends_at > starts_at);

-- 取数形态固定是「一批内容 id + 当前时刻」。
CREATE INDEX idx_content_tag_assignments_post_window
    ON content_tag_assignments (post_id, starts_at, ends_at);

COMMENT ON TABLE content_tags IS
    '内容装饰标签配置（FR-75）。与用户标签表完全独立（AD-10）。description 是 tooltip 里那句说明。';
COMMENT ON COLUMN content_tag_assignments.ends_at IS
    '结束时刻；NULL = 永久。生效与否查询时判定，无状态列、无扫描器（AD-9）。到期后 ×1.3 加权一并消失。';
