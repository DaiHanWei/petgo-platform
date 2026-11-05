-- V1.4.2 social-topic · Story 4.1 · 迁移 ②（架构 D-2 / P-8 ②）：话题 + 帖子 ↔ 话题关联。
--
-- 标准化规则与 com.tailtopia.topic.domain.TopicNameRules 同源（P-1）：
--   name_norm = Normalizer.normalize(raw, NFC).toLowerCase(Locale.ROOT)，对外唯一标识（不暴露 id）。
--   本迁移不做任何标准化计算，写入一律经 TopicService / TopicNameRules，库里只靠唯一约束兜并发。
--
-- post_count / heat_score 只由 4.2 热度任务每小时重算写入（D-2「不做事务计数」）；
--   发帖 / 删帖 / 下架 / 恢复都不维护它们，后台列表最多滞后 1 小时（决策日志 R-5 已接受）。
--
-- content_topics.post_id 的 ON DELETE CASCADE 只为兜底测试库 deleteAll —— 帖子业务上是软删，
--   读时按 content_posts.status = 'PUBLISHED' 与 deleted_at IS NULL 过滤（审核中的帖也写关联）。
--
-- 整数列一律 INTEGER（Hibernate int → int4），禁止更小的整数类型（validate 全红的老坑）。
-- 新功能，存量为空，不回填。

CREATE TABLE topics (
    id             BIGSERIAL     PRIMARY KEY,
    name_display   VARCHAR(30)   NOT NULL,                 -- 显示写法：用户话题=首次出现写法；官方=后台填写
    name_norm      VARCHAR(30)   NOT NULL,                 -- 标准名：NFC + toLowerCase(Locale.ROOT)；对外唯一标识
    type           VARCHAR(16)   NOT NULL,                 -- OFFICIAL | USER
    status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',-- ACTIVE | HIDDEN | DISABLED
    desc_id        VARCHAR(500),                           -- 官方话题说明（印尼语，选填）
    desc_en        VARCHAR(500),                           -- 官方话题说明（英语，选填）
    post_count     INTEGER       NOT NULL DEFAULT 0,       -- 由 4.2 热度任务每小时重算，非事务计数
    heat_score     NUMERIC(12,1) NOT NULL DEFAULT 0,       -- 由 4.2 热度任务每小时重算
    featured_order INTEGER,                                -- 首页话题行序号 1–5（6.1 维护），NULL = 不在行内
    disabled_at    TIMESTAMPTZ,                            -- UTC
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),   -- UTC
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_topics_name_norm UNIQUE (name_norm),
    CONSTRAINT ck_topics_type CHECK (type IN ('OFFICIAL', 'USER')),
    CONSTRAINT ck_topics_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'DISABLED')),
    CONSTRAINT ck_topics_featured_order CHECK (featured_order IS NULL OR featured_order BETWEEN 1 AND 5),
    CONSTRAINT ck_topics_featured_active CHECK (featured_order IS NULL OR status = 'ACTIVE')
);

-- 首页话题行序号唯一（部分唯一索引，D-2）。
-- ⚠️ 6.1 做「改序号互换」时须三步：A→NULL、B→a、A→b（非 DEFERRABLE，单条 UPDATE 互换会撞索引）。
CREATE UNIQUE INDEX uq_topics_featured_order ON topics (featured_order) WHERE featured_order IS NOT NULL;
-- 4.2 联想前缀匹配 name_norm LIKE 'q%'：默认排序规则的唯一索引走不了 LIKE 前缀，另建 text_pattern_ops。
CREATE INDEX idx_topics_name_norm_prefix ON topics (name_norm text_pattern_ops);
CREATE INDEX idx_topics_status_heat ON topics (status, heat_score DESC);

CREATE TABLE content_topics (
    post_id    BIGINT      NOT NULL,
    topic_id   BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),         -- UTC
    CONSTRAINT pk_content_topics PRIMARY KEY (post_id, topic_id),
    CONSTRAINT fk_content_topics_post FOREIGN KEY (post_id) REFERENCES content_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_topics_topic FOREIGN KEY (topic_id) REFERENCES topics (id)
);

-- 话题页取帖（4.2）：按 topic_id 定位后再按 post_id 回表。
CREATE INDEX idx_content_topics_topic_post ON content_topics (topic_id, post_id);
