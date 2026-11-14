-- V1.5.0 onboarding-recall Story 2.2 · FR-177 · OR-8：成长值规则「新手毕业」+20（后台可调）。
-- 独立迁移只插本线这一行，不改已有规则行（运营活动线也往 growth_rules 插它自己的行，互不冲突）。
-- growth_rules 主键是 code（V20261018_1500），ON CONFLICT 幂等可重放。
INSERT INTO growth_rules (code, points, unit_amount, daily_cap, cap_group, enabled)
VALUES ('NEWBIE_GRADUATED', 20, NULL, NULL, NULL, true)
ON CONFLICT (code) DO NOTHING;
