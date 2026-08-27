-- 批次「已保存」标记（bug 20260826）。
--
-- 修复前：运营点「新建批次」的那一刻批次就已落库并出现在列表里 —— 哪怕他进去什么都没填、
-- 直接退出，列表里也留下一条 0 行的空批次。运营的心智是「填完点保存才算数」，
-- 与实现完全相反，于是列表被点错的空批次占满，且没有删除入口。
--
-- 修复后：批次仍在点击时创建（工作台的所有子表单都按 batchId 提交，没有 id 就一个都发不出去），
-- 但**列表只展示 saved_at 非空的批次** —— 第一次真正保存（保存批次设置 / 粘贴 / 加行 / Excel 导入）
-- 时才写入这个时间戳。
--
-- ⚠️ 存量批次一律回填为 created_at：它们是在旧语义下建的，此刻已经在列表里，
--    留空会让运营眼前的批次**集体消失**，那是比原 bug 更严重的事故。
ALTER TABLE seed_batches ADD COLUMN saved_at timestamptz NULL;
COMMENT ON COLUMN seed_batches.saved_at IS '首次真正保存的时刻；为空=只点了「新建批次」还没填任何东西，不进批次列表';

UPDATE seed_batches SET saved_at = created_at WHERE saved_at IS NULL;
