-- 工作线：V1.1.6（本分支迁移从 V105 起，V101–V104 归 hex/v1.1.4）
--
-- V1.1.6 Story 6.1 · FR-76 / AD-13：S/M 级里程碑达成的通知类型。
--
-- 背景：只有 L 级（重大节点）完成时会写通知中心 + 发系统推送；S/M 级只触发前端庆祝动效。
-- 「用户打卡」「去发布」这两种主动触发下用户当场就看到了庆祝，没问题；但由**别人的互动**
-- 触发的 S/M（第一次被评论、第一次收到点赞），本人当时不在现场，**完全不会被告知**。
--
-- 🛡 新增**独立类型**而不是复用 L 级那一种（AD-13 Rule 1）：
--    复用会让 S/M 也走系统推送 —— 而 S/M 数量多得多，那是打扰。
--
-- ⚠️ notifications.type 有 CHECK 约束，新增取值必须在这里放行，否则发这类通知会直接违反约束。
--
-- 🔴 **本文件的取值列表是并集，含本分支用不到的两个值** —— 这不是笔误，是刻意的：
--
--    该约束的修改方式是「DROP 掉再按完整清单 ADD 回去」。两条工作线各写一份完整清单时，
--    **合并后跑在后面的那份会把另一份的取值悄悄踢掉** —— V97 就是为了修这个才存在的
--    （当时审核线与 v1.1 线各加各的，合并后审核类通知全断）。
--
--    并行的 hex/v1.1.4 加了 ACCOUNT_SUSPENDED / ACCOUNT_WARNED（警告/封号两档处置的用户侧通知）。
--    本分支的代码用不到这两个值（枚举里没有），但**把它们写进白名单是零成本的**：
--    多几个允许值不会让本分支写出脏数据（能写什么由 Java 枚举决定），
--    却能保证两条线无论谁先合入都不会互相踢掉对方的通知类型。
--
--    ⚠️ 下次再有人改这个约束：**先查一遍并行分支加了什么**，不要只照抄上一份清单。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    -- 并行工作线 hex/v1.1.4（警告/封号两档处置的用户侧通知）—— 本分支用不到，
    -- 写进来纯粹是为了合并时不把对方踢掉，见上方说明。
    'ACCOUNT_WARNED', 'ACCOUNT_SUSPENDED',
    -- V1.1.6 Story 6.1（FR-76）：S/M 级里程碑达成 —— 写通知中心、**不发系统推送**。
    'MILESTONE_SM_NODE'));
