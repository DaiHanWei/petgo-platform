-- analytics-pipeline Story 4.8：口径表补列 + agg_progress 只读授权（只在埋点库执行）。
-- metric_definitions 是 resources/analytics/metric-definitions.yaml 的投影（D-9），1.1 首条迁移缺两列：
--   team_ref  团队编号（如 S1、电商#6；code 是条目唯一键，一个团队编号可有多条，如 S1 / S1-登录 / S1-GA4）
--   tables    该条目用到的汇总表 / 快照表名（字符串数组；CI 的 MetricDefinitionsCoverageTest 校验每张 agg_* 汇总表都被引用）
-- 已有列沿用 1.1 的名字，不改名：code（条目 id）、known_bias（已知偏差）、sql_path（SQL 文件位置）、changelog（变更记录）。
-- 新列由后端启动同步（CatalogSync）写入；迁移后到首次同步之间为默认值。
-- 🔴 本文件一旦被任何环境应用即冻结。

ALTER TABLE metric_definitions ADD COLUMN team_ref varchar(32);
ALTER TABLE metric_definitions ADD COLUMN tables jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE metric_definitions
    ADD CONSTRAINT ck_metric_definitions_tables_array CHECK (jsonb_typeof(tables) = 'array');

COMMENT ON COLUMN metric_definitions.team_ref IS '团队编号（S1、电商#6 等；_rules 通用规则条目为空）';
COMMENT ON COLUMN metric_definitions.tables IS '该指标用到的汇总表 / 快照表名（字符串数组）';

-- agg_progress 让 AI 判断「数据截至哪天」：Story 4.1（V20261113_1300）已授，这里按 story 再授一次（GRANT 可重复执行），
-- 保证本 story 的白名单在任何一条迁移漏掉时也成立。写法沿用 Story 1.2 的条件授权；角色名由占位符 readerRole 传入。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'agg_progress', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
