-- V1.1.4 修复清单 #4：统一工单队列入口换了新权限点 content.view_tickets（Story 3.1），
-- 但存量审核员手里只有旧的 content.view_reports —— 不回填的话，部署当天所有非 SUPER_ADMIN
-- 审核员：侧栏看不到队列入口，旧书签 /admin/reports 又被 redirect 到 /admin/tickets → 403，
-- 直到有人手工重新授权前无人能处理举报。
--
-- 回填规则：持有 content.view_reports 的账号一律补发 content.view_tickets。
-- 旧权限码不回收（/admin/reports 的 GET 与两个 POST 仍 gate 在旧码上）。
INSERT INTO admin_account_permissions (account_id, permission_code)
SELECT account_id, 'content.view_tickets'
  FROM admin_account_permissions
 WHERE permission_code = 'content.view_reports'
ON CONFLICT (account_id, permission_code) DO NOTHING;
