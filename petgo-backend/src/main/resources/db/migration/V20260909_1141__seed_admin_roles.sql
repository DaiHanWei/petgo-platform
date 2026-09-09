-- V1.3.0 后台线 Story 1.4 数据迁移：四个预置角色 + 权限码（逐条抄自迁移前 AdminRole.java，勿改动）+ 存量账号回填。
-- 不含 place.manage / comment.virtual_post（新码不预授予，AD-4 / 架构安全节；超管隐式拥有，运营需要时在角色页勾选）。
-- OPS_MANAGER 不迁移（D-13，仍读枚举）；SUPER_ADMIN / CUSTOM 账号 role_id 保持 NULL。
-- 本支是数据迁移，应用到任何环境后即冻结；清单有误只能再加一支修正迁移。

INSERT INTO admin_roles (code, name, name_key, role_type) VALUES
    ('OPERATIONS',  '运营专员', 'role.OPERATIONS',  'SYSTEM'),
    ('FULFILLMENT', '发货专员', 'role.FULFILLMENT', 'SYSTEM'),
    ('SUPPORT',     '客服',     'role.SUPPORT',     'SYSTEM'),
    ('FINANCE',     '财务',     'role.FINANCE',     'SYSTEM');

-- OPERATIONS（19）
INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, c FROM admin_roles r, unnest(ARRAY[
    'content.view_reports','content.view','content.takedown','content.restore',
    'content.proactive_takedown','content.manual_review','user.view','vet.view',
    'vet.qualify_view','rating.view','consult.view_anomalies','consult.view_sessions',
    'config.view','order.view','virtual_account.view','virtual_account.manage',
    'shop.product_view','shop.inventory_view','shop.order_view']) AS c
WHERE r.code = 'OPERATIONS';

-- FULFILLMENT（5）
INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, c FROM admin_roles r, unnest(ARRAY[
    'shop.product_view','shop.inventory_view','shop.inventory_edit','shop.order_view',
    'shop.order_fulfill']) AS c
WHERE r.code = 'FULFILLMENT';

-- SUPPORT（15）
INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, c FROM admin_roles r, unnest(ARRAY[
    'user.view','content.view','content.view_reports','vet.view','rating.view',
    'consult.view_anomalies','consult.handle','consult.view_sessions','support.view',
    'support.handle','refund.view','refund.submit','order.view','shop.order_view',
    'shop.order_phone_search']) AS c
WHERE r.code = 'SUPPORT';

-- FINANCE（15）
INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, c FROM admin_roles r, unnest(ARRAY[
    'config.view','order.view','order.export','settlement.view','settlement.payout',
    'payment.view','risk.view','refund.view','refund.payout','shop.order_view',
    'shop.inventory_view','shop.product_view','shop.cost_view','shop.cost_edit',
    'shop.finance_view']) AS c
WHERE r.code = 'FINANCE';

-- 存量账号回填（role 列是 V20260821_1449 加的 varchar 枚举）
UPDATE admin_accounts a SET role_id = r.id FROM admin_roles r WHERE a.role = r.code;
