-- 内容审核 cm-7（通知文案 i18n + 隐藏才通知收口）。冻结基线 V46；本 story 增量 ALTER，实际号按 CI 合并顺序单调顺延。
-- 占位号 V53；ddl-auto=validate：schema 归 Flyway。
-- 新增 CONTENT_REVIEW_TIMED_OUT（帖子人工审核超时丢弃，§8.8 文案与 §8.7 REJECTED 不同故拆型）。
--   NAME_RESET 由 V50、AVATAR_RESET 由 V51 已加入本 CHECK；本迁移在完整列表基础上追加 CONTENT_REVIEW_TIMED_OUT。
-- 护栏：保留 CONTENT_REVIEW_APPROVED（D-CM6 后不再发送，但历史行/枚举对称保留，不从 CHECK 移除）。
--   判别位（POST/COMMENT、USER/PET）复用既有 notifications.target_ref，无新列。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED', 'NAME_RESET', 'AVATAR_RESET',
    'CONTENT_REVIEW_TIMED_OUT'));
