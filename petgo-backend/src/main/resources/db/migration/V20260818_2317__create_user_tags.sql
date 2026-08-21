-- 工作线：V1.1.6（本分支迁移从 V105 起，V101–V104 归 hex/v1.1.4）
--
-- V1.1.6 Story 5.1 · FR-74：用户标签（运营认定的身份标识）。
--
-- 设计要点（架构 AD-10 / AD-9）：
--   1. 🛡 **两套标签用两套独立表**：本文件只建用户标签这两张；内容装饰标签那两张归 Story 5.2，
--      **不共用配置表、不用多态外键**。
--      多态外键（一张分配表既能指用户又能指内容）在 PostgreSQL 里**建不出外键约束** ——
--      完整性只能靠代码自律，而"靠自律"就是迟早会有脏数据。分开之后两张分配表各自
--      指向单一实体，都能建真实外键。
--
--   2. 🛡 **不落状态列、不建定时扫描器**（AD-9）：生效与否一律查询时按
--      [starts_at, ends_at) 判定。**ends_at 可空 = 永久分配**（本表比顶置排期多这一种情况）。
--
--   3. 分配记录**只增不改**：超过展示上限（3 个）的记录**保留在库里、只是不展示**，
--      所以这里不做任何"每人最多 N 条"的约束。
--
--   4. 时间一律 timestamptz、UTC 绝对时刻；运营配的 WIB 墙上时间在入库前换算（AD-9 Rule 4）。

CREATE TABLE user_tags (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(48)  NOT NULL,
    name        VARCHAR(48)  NOT NULL,
    icon        VARCHAR(255) NOT NULL,
    description VARCHAR(140) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- code 是运营侧的稳定标识（改名不影响引用），全局唯一。
ALTER TABLE user_tags ADD CONSTRAINT uq_user_tags_code UNIQUE (code);

CREATE TABLE user_tag_assignments (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    tag_id     BIGINT      NOT NULL,
    starts_at  TIMESTAMPTZ NOT NULL,
    -- 🛡 可空 = **永久分配**（不设结束时间）。
    ends_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_user_tag_assignments_tag FOREIGN KEY (tag_id) REFERENCES user_tags (id),
    CONSTRAINT fk_user_tag_assignments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- 排期本身必须是个正区间；不设结束时间时不作要求。
ALTER TABLE user_tag_assignments
    ADD CONSTRAINT ck_user_tag_assignments_window CHECK (ends_at IS NULL OR ends_at > starts_at);

-- 取数形态固定是「一批用户 id + 当前时刻」：user_id 前导等值，其后按时间窗筛。
CREATE INDEX idx_user_tag_assignments_user_window
    ON user_tag_assignments (user_id, starts_at, ends_at);

COMMENT ON TABLE user_tags IS
    '用户标签配置（FR-74）。description 是点标签后 tooltip 里显示的那句说明。';
COMMENT ON COLUMN user_tag_assignments.ends_at IS
    '结束时刻；NULL = 永久分配。生效与否查询时按 [starts_at, ends_at) 判定，无状态列、无扫描器（AD-9）。';
COMMENT ON TABLE user_tag_assignments IS
    '用户标签分配（FR-74）。超过展示上限（3 个）的记录保留在库、仅不展示，故不设每人条数上限。';
