-- V1.5.0 onboarding-recall Story 2.1 · FR-176 · OR-1：新手任务「做过」的账号记录。
-- 行为发生时写、永不撤（取消点赞 / 删评 / 取关 / 删帖 / 删宠物都不删行）；进度按账号算，没宠物也能推进。
-- 不复用 user_milestone_flags：那张表的每个 key 都会被解析成里程碑并扇到宠物上，而新手任务 ④–⑦ 只做任务、不新增里程碑。
--
-- 🔴 ck_user_newbie_marks_key 是本表**自有约束**（只有本线改）：加值须新迁移 DROP + ADD **重列全集**，
--    值取当前树里最后一条定义它的迁移原文（本文件为首条）。
-- 注销：AccountDeletionService 显式删（users 行只匿名化不删，FK CASCADE 在注销路径不触发）。
-- 幂等可重放（IF NOT EXISTS）。时间戳一律 UTC。
CREATE TABLE IF NOT EXISTS user_newbie_marks (
    user_id     BIGINT      NOT NULL,
    task_key    VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    source      VARCHAR(16) NOT NULL DEFAULT 'RUNTIME',
    CONSTRAINT pk_user_newbie_marks PRIMARY KEY (user_id, task_key),
    CONSTRAINT fk_user_newbie_marks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_newbie_marks_key CHECK (task_key IN ('FIRST_DIARY', 'FIRST_LIKE_RECEIVED', 'FIRST_FOLLOW',
        'FIRST_LIKE_GIVEN', 'FIRST_COMMENT', 'FIRST_REPLY', 'ONE_WEEK')),
    CONSTRAINT ck_user_newbie_marks_source CHECK (source IN ('RUNTIME', 'BACKFILL'))
);

COMMENT ON TABLE user_newbie_marks IS '新手任务账号记录（V1.5.0 FR-176）：做过即记、永不撤';
COMMENT ON COLUMN user_newbie_marks.task_key IS 'NewbieTask 名（7 值，见 ck_user_newbie_marks_key）';
COMMENT ON COLUMN user_newbie_marks.occurred_at IS '首次完成时刻（UTC）';
COMMENT ON COLUMN user_newbie_marks.source IS 'RUNTIME 运行时监听 / BACKFILL 上线补算（只供排查与埋点区分）';
