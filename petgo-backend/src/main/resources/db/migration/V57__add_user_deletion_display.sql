-- 注销用户后台「仅展示」列（运营诉求）：注销时把 email/display_name 快照到这两列，
-- 原 email/display_name 仍按 7-3 匿名化置空（业务/公开/vet 侧看到的仍是 null，不泄漏、不影响同邮箱重注册）。
-- 仅运营后台用户列表/详情读取这两列展示「谁注销了」。合规风险已由业务负责人确认承担。
ALTER TABLE users ADD COLUMN deleted_email        VARCHAR(320);
ALTER TABLE users ADD COLUMN deleted_display_name VARCHAR(255);

COMMENT ON COLUMN users.deleted_email IS '注销前 email 快照，仅运营后台展示；原 email 列注销后置空';
COMMENT ON COLUMN users.deleted_display_name IS '注销前 display_name 快照，仅运营后台展示；原列注销后置空';
