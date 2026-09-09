-- V1.3.0 后台线 Story 1.1（AD-1）：admin_accounts 增加账号变更版本号 security_version。
--
-- 账号被停用 / 改岗位角色 / 改账号级权限 / 换绑邮箱 / 所属角色模板改权限时 +1；
-- 登录时快照进 principal，AdminSessionGuardFilter 每请求在既有「重查 ACTIVE」的同一次查库里比对，
-- 不等即失效会话并跳 /admin/login?relogin（撤权 / 移交即时生效，不留空窗）。
-- 存量账号一律 0；版本号是内部机制，不进审计、不对外暴露。

ALTER TABLE admin_accounts ADD COLUMN security_version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN admin_accounts.security_version IS
    '账号安全版本号：停用/改角色/改权限/换绑邮箱时 +1，与会话内快照不等即踢重登（AD-1）';
