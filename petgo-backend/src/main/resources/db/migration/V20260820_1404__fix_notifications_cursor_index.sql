-- 通知游标索引修复（NOTIFY-CURSOR-TIE，与 shawn 线 V125 同源同内容——合并 shawn 分支时删他那份）。
-- 🔴 号说明（2026-08-21 E6 改号）：原号 V107→V126→本时间戳号；历史撞号考古见 git log 本文件。
-- 本迁移只建索引，幂等（IF NOT EXISTS），从未 applied 在 stag/prod。
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created_id
    ON notifications (recipient_user_id, created_at DESC, id DESC);

-- 旧索引是新索引的严格前缀，完全冗余（留着只是白占写入成本与磁盘）。
DROP INDEX IF EXISTS idx_notifications_recipient_created;
